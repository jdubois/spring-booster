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

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
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

    private static final String BEAN_NAME = "io.github.jdubois.springbooster.internalParallelBootstrapPostProcessor";

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            return;
        }
        ParallelBootstrapSettings settings = buildSettings(importingClassMetadata);
        AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(
                        ParallelBootstrapBeanFactoryPostProcessor.class)
                .addConstructorArgValue(settings)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
                .getBeanDefinition();
        registry.registerBeanDefinition(BEAN_NAME, beanDefinition);
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
        }
        return builder.build();
    }
}
