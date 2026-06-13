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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * {@link ImportBeanDefinitionRegistrar} activated by {@link EnableParallelBootstrap}
 * that registers a {@link ParallelBootstrapBeanFactoryPostProcessor} configured from
 * the annotation attributes.
 *
 * <p>Using a registrar (rather than a static {@code @Bean} method) avoids any risk
 * of prematurely instantiating other beans while wiring up the infrastructure
 * post-processor.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see EnableParallelBootstrap
 */
class ParallelBootstrapRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        ParallelBootstrapSettings settings = buildSettings(importingClassMetadata);
        ParallelBootstrapInfrastructure.register(registry, settings);
    }

    private ParallelBootstrapSettings buildSettings(AnnotationMetadata metadata) {
        ParallelBootstrapSettings.Builder builder = ParallelBootstrapSettings.builder();
        Map<String, @Nullable Object> attributes =
                metadata.getAnnotationAttributes(EnableParallelBootstrap.class.getName());
        if (attributes != null) {
            Object enabled = attributes.get("enabled");
            if (enabled instanceof Boolean enabledValue) {
                builder.enabled(enabledValue);
            }
            Object poolSize = attributes.get("poolSize");
            if (poolSize instanceof Number poolSizeValue && poolSizeValue.intValue() > 0) {
                builder.poolSize(poolSizeValue.intValue());
            }
            Object threadNamePrefix = attributes.get("threadNamePrefix");
            if (threadNamePrefix instanceof String prefix && !prefix.isEmpty()) {
                builder.threadNamePrefix(prefix);
            }
            Object backgroundBeanNames = attributes.get("backgroundBeanNames");
            if (backgroundBeanNames instanceof String[] names && names.length > 0) {
                builder.backgroundBeanNames(names);
            }
            Object backgroundFactoryMethodBeans = attributes.get("backgroundFactoryMethodBeans");
            if (backgroundFactoryMethodBeans instanceof Boolean backgroundValue) {
                builder.backgroundFactoryMethodBeans(backgroundValue);
            }
            Object deferProviderEdges = attributes.get("deferProviderEdges");
            if (deferProviderEdges instanceof Boolean deferValue) {
                builder.deferProviderEdges(deferValue);
            }
            Object backgroundSharedInfraConsumers = attributes.get("backgroundSharedInfraConsumers");
            if (backgroundSharedInfraConsumers instanceof Boolean sharedInfraValue) {
                builder.backgroundSharedInfraConsumers(sharedInfraValue);
            }
            Object barrierBeanNames = attributes.get("barrierBeanNames");
            if (barrierBeanNames instanceof String[] names && names.length > 0) {
                builder.barrierBeanNames(names);
            }
            Object coBackgroundGroups = attributes.get("coBackgroundGroups");
            if (coBackgroundGroups instanceof AnnotationAttributes[] groups && groups.length > 0) {
                List<List<String>> parsedGroups = new ArrayList<>();
                for (AnnotationAttributes group : groups) {
                    String[] members = group.getStringArray("value");
                    if (members.length > 0) {
                        parsedGroups.add(List.of(members));
                    }
                }
                if (!parsedGroups.isEmpty()) {
                    builder.coBackgroundGroups(parsedGroups);
                }
            }
            Object bytecodeLookupDetection = attributes.get("bytecodeLookupDetection");
            if (bytecodeLookupDetection instanceof Boolean bytecodeValue) {
                builder.bytecodeLookupDetection(bytecodeValue);
            }
            Object buildTimePlanningEnabled = attributes.get("buildTimePlanningEnabled");
            if (buildTimePlanningEnabled instanceof Boolean buildTimeValue) {
                builder.buildTimePlanningEnabled(buildTimeValue);
            }
            Object runtimePlanningEnabled = attributes.get("runtimePlanningEnabled");
            if (runtimePlanningEnabled instanceof Boolean runtimePlanningValue) {
                builder.runtimePlanningEnabled(runtimePlanningValue);
            }
            Object generatedPlanRequired = attributes.get("generatedPlanRequired");
            if (generatedPlanRequired instanceof Boolean generatedPlanRequiredValue) {
                builder.generatedPlanRequired(generatedPlanRequiredValue);
                Object useVirtualThreads = attributes.get("useVirtualThreads");
                if (useVirtualThreads instanceof Boolean useVirtualThreadsValue) {
                    builder.useVirtualThreads(useVirtualThreadsValue);
                }
            }
        }
        return builder.build();
    }
}
