/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jdubois.springbooster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactoryInitializer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

/**
 * Shared runtime and build-time planner for Spring Booster.
 */
final class ParallelBootstrapPlanner {

    private final ParallelBootstrapSettings settings;

    ParallelBootstrapPlanner(ParallelBootstrapSettings settings) {
        this.settings = settings;
    }

    ParallelBootstrapPlan createPlan(ConfigurableListableBeanFactory beanFactory) {
        List<String> allNames = List.of(beanFactory.getBeanDefinitionNames());
        List<String> singletons = new ArrayList<>();
        for (String beanName : allNames) {
            BeanDefinition bd = safeGetBeanDefinition(beanFactory, beanName);
            if (bd != null && !bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
                singletons.add(beanName);
            }
        }
        BeanDependencyGraph graph =
                BeanDependencyGraph.build(beanFactory, allNames, !this.settings.isBackgroundFactoryMethodBeans());
        Set<String> cyclic = graph.beansInCycles();
        Set<String> forcedMainline = collectForcedMainlineBeans(beanFactory);
        Set<String> eligible = new LinkedHashSet<>();
        for (String beanName : singletons) {
            if (isSafeCandidate(beanFactory, beanName, cyclic, forcedMainline)) {
                eligible.add(beanName);
            }
        }
        Set<String> mainline = new LinkedHashSet<>(graph.getNodes());
        mainline.removeAll(eligible);
        propagateMainline(graph, eligible, mainline);

        List<String> candidates = new ArrayList<>();
        for (String beanName : singletons) {
            if (eligible.contains(beanName)) {
                candidates.add(beanName);
            }
        }
        Map<String, Set<String>> syncDependencies = new LinkedHashMap<>();
        for (String beanName : graph.getNodes()) {
            Set<String> dependencies = graph.getSyncDependencies(beanName);
            if (!dependencies.isEmpty()) {
                syncDependencies.put(beanName, new LinkedHashSet<>(dependencies));
            }
        }
        return new ParallelBootstrapPlan(
                candidates,
                forcedMainline,
                syncDependencies,
                new LinkedHashSet<>(allNames),
                fingerprintSettings(this.settings),
                fingerprintBeanFactory(beanFactory, allNames));
    }

    boolean isPlanCompatible(ConfigurableListableBeanFactory beanFactory, ParallelBootstrapPlan plan) {
        return fingerprintSettings(this.settings).equals(plan.getSettingsFingerprint())
                && fingerprintBeanFactory(beanFactory, List.of(beanFactory.getBeanDefinitionNames()))
                        .equals(plan.getBeanFactoryFingerprint());
    }

    static String fingerprintSettings(ParallelBootstrapSettings settings) {
        return sha256(String.join(
                "|",
                Boolean.toString(settings.isEnabled()),
                Integer.toString(settings.getPoolSize()),
                settings.getThreadNamePrefix(),
                Boolean.toString(settings.isBackgroundFactoryMethodBeans()),
                Boolean.toString(settings.isBuildTimePlanningEnabled()),
                Boolean.toString(settings.isRuntimePlanningEnabled()),
                Boolean.toString(settings.isGeneratedPlanRequired()),
                Boolean.toString(settings.hasDefaultCandidateFilter())));
    }

    private static String fingerprintBeanFactory(
            ConfigurableListableBeanFactory beanFactory, List<String> beanDefinitionNames) {
        List<String> signatures = new ArrayList<>();
        for (String beanName : beanDefinitionNames.stream().sorted().toList()) {
            BeanDefinition bd = safeGetMergedBeanDefinition(beanFactory, beanName);
            signatures.add(beanDefinitionSignature(beanName, bd));
        }
        return sha256(String.join("\n", signatures));
    }

    private static String beanDefinitionSignature(String beanName, @Nullable BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return beanName + "|missing";
        }
        String dependsOn = "";
        if (beanDefinition.getDependsOn() != null) {
            dependsOn = String.join(",", new TreeSet<>(List.of(beanDefinition.getDependsOn())));
        }
        String beanClassName = (beanDefinition.getBeanClassName() != null ? beanDefinition.getBeanClassName() : "");
        String factoryBeanName = (beanDefinition.getFactoryBeanName() != null ? beanDefinition.getFactoryBeanName() : "");
        String factoryMethodName =
                (beanDefinition.getFactoryMethodName() != null ? beanDefinition.getFactoryMethodName() : "");
        return String.join(
                "|",
                beanName,
                beanClassName,
                factoryBeanName,
                factoryMethodName,
                beanDefinition.getScope(),
                Boolean.toString(beanDefinition.isLazyInit()),
                Boolean.toString(beanDefinition.isAbstract()),
                Integer.toString(beanDefinition.getRole()),
                dependsOn);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private boolean isSafeCandidate(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            Set<String> cyclic,
            Set<String> forcedMainline) {

        if (cyclic.contains(beanName) || forcedMainline.contains(beanName)) {
            return false;
        }
        BeanDefinition bd = safeGetBeanDefinition(beanFactory, beanName);
        if (bd == null || ParallelBootstrapSettings.isOptedOut(bd) || !(bd instanceof AbstractBeanDefinition)) {
            return false;
        }
        Class<?> type = safeGetType(beanFactory, beanName);
        if (type != null && isInfrastructureType(type)) {
            return false;
        }
        return this.settings.getCandidateFilter().test(beanName);
    }

    private static boolean isInfrastructureType(Class<?> type) {
        return (BeanPostProcessor.class.isAssignableFrom(type)
                || BeanFactoryPostProcessor.class.isAssignableFrom(type)
                || BeanFactoryInitializer.class.isAssignableFrom(type)
                || SmartInitializingSingleton.class.isAssignableFrom(type));
    }

    private static void propagateMainline(BeanDependencyGraph graph, Set<String> eligible, Set<String> mainline) {
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String node : graph.getNodes()) {
            for (String dependency : graph.getSyncDependencies(node)) {
                dependents.computeIfAbsent(dependency, key -> new LinkedHashSet<>()).add(node);
            }
        }
        Deque<String> worklist = new ArrayDeque<>(mainline);
        while (!worklist.isEmpty()) {
            String current = worklist.poll();
            Set<String> neighbors = new LinkedHashSet<>(graph.getSyncDependencies(current));
            neighbors.addAll(dependents.getOrDefault(current, Collections.emptySet()));
            for (String neighbor : neighbors) {
                if (eligible.remove(neighbor)) {
                    mainline.add(neighbor);
                    worklist.add(neighbor);
                }
            }
        }
    }

    private static Set<String> collectForcedMainlineBeans(ConfigurableListableBeanFactory beanFactory) {
        Set<String> forced = new HashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = safeGetMergedBeanDefinition(beanFactory, beanName);
            if (bd == null) {
                continue;
            }
            if (bd.getFactoryBeanName() != null) {
                forced.add(bd.getFactoryBeanName());
            }
            String[] dependsOn = bd.getDependsOn();
            if (dependsOn != null) {
                Collections.addAll(forced, dependsOn);
            }
        }
        return forced;
    }

    private static @Nullable BeanDefinition safeGetBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static @Nullable BeanDefinition safeGetMergedBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getMergedBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static @Nullable Class<?> safeGetType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
