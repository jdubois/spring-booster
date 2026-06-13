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

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * Shared registration and lookup utilities for the Spring Booster infrastructure
 * beans that enable parallel bootstrap planning.
 */
final class ParallelBootstrapInfrastructure {

    static final String POST_PROCESSOR_BEAN_NAME =
            "io.github.jdubois.springbooster.internalParallelBootstrapPostProcessor";

    private static final String SETTINGS_ATTRIBUTE = ParallelBootstrapInfrastructure.class.getName() + ".settings";

    private ParallelBootstrapInfrastructure() {}

    static void register(BeanDefinitionRegistry registry, ParallelBootstrapSettings settings) {
        if (registry.containsBeanDefinition(POST_PROCESSOR_BEAN_NAME)) {
            return;
        }
        AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder.rootBeanDefinition(
                        ParallelBootstrapBeanFactoryPostProcessor.class)
                .addConstructorArgValue(settings)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
                .getBeanDefinition();
        beanDefinition.setAttribute(SETTINGS_ATTRIBUTE, settings);
        registry.registerBeanDefinition(POST_PROCESSOR_BEAN_NAME, beanDefinition);
    }

    static @Nullable ParallelBootstrapSettings findSettings(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinition beanDefinition = safeGetBeanDefinition(beanFactory, POST_PROCESSOR_BEAN_NAME);
        if (beanDefinition == null) {
            return null;
        }
        Object settingsAttribute = beanDefinition.getAttribute(SETTINGS_ATTRIBUTE);
        if (settingsAttribute instanceof ParallelBootstrapSettings settings) {
            return settings;
        }
        List<ValueHolder> argumentValues =
                beanDefinition.getConstructorArgumentValues().getGenericArgumentValues();
        if (argumentValues.isEmpty()) {
            return null;
        }
        Object value = argumentValues.get(0).getValue();
        return (value instanceof ParallelBootstrapSettings settings ? settings : null);
    }

    private static @Nullable BeanDefinition safeGetBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
