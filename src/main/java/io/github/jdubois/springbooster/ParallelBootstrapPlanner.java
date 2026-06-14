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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Shared planner used both at runtime and during Spring AOT generation.
 *
 * <p>The planner is intentionally a thin wrapper around the canonical, fully-featured
 * selection algorithm implemented in {@link ParallelBootstrapBeanFactoryPostProcessor}
 * (including all of this branch's relaxations: per-bean allowlist, completed-leaf
 * barriers, co-background groups, deferred provider edges, and the bytecode
 * lookup-detection refinement). Reusing that single algorithm guarantees that a
 * build-time generated plan selects exactly the same background candidates the runtime
 * planner would, so a precomputed plan can be reused verbatim when its compatibility
 * fingerprints still match.
 */
final class ParallelBootstrapPlanner {

    private final ParallelBootstrapSettings settings;

    ParallelBootstrapPlanner(ParallelBootstrapSettings settings) {
        this.settings = settings;
    }

    /**
     * Compute a complete bootstrap plan: the ordered background candidates (via the
     * canonical algorithm), the forced-mainline beans, the structural sync-dependency
     * view, the full bean-name set, and the settings/bean-factory fingerprints that
     * guard runtime reuse.
     */
    ParallelBootstrapPlan createPlan(ConfigurableListableBeanFactory beanFactory) {
        List<String> allNames = List.of(beanFactory.getBeanDefinitionNames());

        // Single source of truth for candidate selection: the runtime post-processor's
        // rich planCandidates(...), so every relaxation and safety gate is honored.
        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(this.settings).planCandidates(beanFactory);

        Set<String> forcedMainline = ParallelBootstrapBeanFactoryPostProcessor.collectForcedMainlineBeans(beanFactory);

        // The structural sync view mirrors the co-location decision used by planCandidates,
        // so the serialized plan records the same dependency boundary (informational; the
        // load-bearing reuse path keys off the candidate list and the fingerprints).
        boolean relaxSharedInfra = this.settings.isBackgroundSharedInfraConsumers()
                || !this.settings.getBarrierBeanNames().isEmpty();
        boolean colocateFactoryMethodBeans = !this.settings.isBackgroundFactoryMethodBeans() || relaxSharedInfra;
        BeanDependencyGraph graph = BeanDependencyGraph.build(
                beanFactory,
                allNames,
                colocateFactoryMethodBeans,
                this.settings.isDeferProviderEdges(),
                this.settings.isBytecodeLookupDetection());
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

    /**
     * Whether the given generated plan is still compatible with the current settings and
     * bean factory. A plan is reused verbatim only when both fingerprints match.
     */
    boolean isPlanCompatible(ConfigurableListableBeanFactory beanFactory, ParallelBootstrapPlan plan) {
        return fingerprintSettings(this.settings).equals(plan.getSettingsFingerprint())
                && fingerprintBeanFactory(beanFactory, List.of(beanFactory.getBeanDefinitionNames()))
                        .equals(plan.getBeanFactoryFingerprint());
    }

    /**
     * Compute a stable fingerprint of the serializable settings that influence
     * build-time plan compatibility. A runtime-loaded generated plan is only used when
     * this fingerprint still matches the active settings, otherwise the plan is treated
     * as stale and ignored. Every setting that can change the selected candidate set
     * (including this branch's relaxations) is included, so a plan generated under a
     * different configuration is never reused.
     */
    static String fingerprintSettings(ParallelBootstrapSettings settings) {
        return sha256(String.join(
                "|",
                Boolean.toString(settings.isEnabled()),
                Integer.toString(settings.getPoolSize()),
                settings.getThreadNamePrefix(),
                Boolean.toString(settings.isBackgroundFactoryMethodBeans()),
                Boolean.toString(settings.isDeferProviderEdges()),
                Boolean.toString(settings.isBackgroundSharedInfraConsumers()),
                Boolean.toString(settings.isSpringBootWebProfile()),
                Boolean.toString(settings.isBytecodeLookupDetection()),
                Boolean.toString(settings.isBuildTimePlanningEnabled()),
                Boolean.toString(settings.isRuntimePlanningEnabled()),
                Boolean.toString(settings.isGeneratedPlanRequired()),
                Boolean.toString(settings.hasDefaultCandidateFilter()),
                String.join(",", new TreeSet<>(settings.getBackgroundBeanNames())),
                String.join(",", new TreeSet<>(settings.getBarrierBeanNames())),
                fingerprintGroups(settings.getCoBackgroundGroups())));
    }

    private static String fingerprintGroups(List<Set<String>> groups) {
        List<String> normalized = new ArrayList<>();
        for (Set<String> group : groups) {
            normalized.add(String.join(",", new TreeSet<>(group)));
        }
        Collections.sort(normalized);
        return String.join(";", normalized);
    }

    private static String fingerprintBeanFactory(
            ConfigurableListableBeanFactory beanFactory, List<String> beanDefinitionNames) {
        List<String> signatures = new ArrayList<>();
        List<String> sortedBeanDefinitionNames = new ArrayList<>(beanDefinitionNames);
        Collections.sort(sortedBeanDefinitionNames);
        for (String beanName : sortedBeanDefinitionNames) {
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
        String factoryBeanName =
                (beanDefinition.getFactoryBeanName() != null ? beanDefinition.getFactoryBeanName() : "");
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

    private static @Nullable BeanDefinition safeGetMergedBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getMergedBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
