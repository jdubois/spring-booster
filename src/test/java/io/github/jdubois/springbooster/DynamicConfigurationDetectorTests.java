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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;

/**
 * Tests for {@link DynamicConfigurationDetector}.
 *
 * @author Spring Framework Team
 */
class DynamicConfigurationDetectorTests {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void pureConfigurationIsNotDynamic() {
        register("config", PureConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isFalse();
    }

    @Test
    void awareConfigurationIsDynamic() {
        register("config", AwareConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void customizerConfigurationIsDynamic() {
        register("config", CustomizerConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void configurationCapturingApplicationContextIsDynamic() {
        register("config", ContextCapturingConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void configurationWithObjectProviderFieldIsDynamic() {
        register("config", ProviderFieldConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void configurationWithLazyFieldIsDynamic() {
        register("config", LazyFieldConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void configurationCapturingContextViaConstructorIsDynamic() {
        register("config", ConstructorContextConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void fullConfigurationAttributeIsDynamic() {
        RootBeanDefinition bd = new RootBeanDefinition(PureConfig.class);
        bd.setAttribute(
                "org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass", "full");
        this.beanFactory.registerBeanDefinition("config", bd);

        // A pure class becomes dynamic purely by virtue of the "full" configuration
        // attribute (CGLIB self-invocation enabled).
        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
    }

    @Test
    void unknownConfigurationDefaultsToDynamic() {
        // No bean definition registered under this name: introspection fails, so the
        // detector falls back to the safe (dynamic) classification.
        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "missing"))
                .isTrue();
    }

    @Test
    void bytecodeDetectionDowngradesReflectiveFalsePositiveToPure() {
        // ProviderFieldConfig merely declares an ObjectProvider field and never uses it:
        // reflectively dynamic, but the bytecode scan proves it performs no lookup, so with
        // bytecode lookup-detection enabled it is downgraded to pure.
        register("config", ProviderFieldConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config"))
                .isTrue();
        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config", true))
                .isFalse();
    }

    @Test
    void bytecodeDetectionKeepsGenuineLookupDynamic() {
        // ProviderDereferenceConfig actually dereferences its ObjectProvider, so the
        // bytecode scan confirms the reflective dynamic classification.
        register("config", ProviderDereferenceConfig.class);

        assertThat(DynamicConfigurationDetector.isDynamicConfiguration(this.beanFactory, "config", true))
                .isTrue();
    }

    private void register(String beanName, Class<?> type) {
        this.beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(type));
    }

    static class PureConfig {

        Object product() {
            return new Object();
        }
    }

    static class AwareConfig implements BeanFactoryAware {

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {}
    }

    interface SampleCustomizer {}

    static class CustomizerConfig implements SampleCustomizer {}

    static class ContextCapturingConfig {

        private ApplicationContext applicationContext;
    }

    static class ProviderFieldConfig {

        private ObjectProvider<Object> provider;
    }

    static class ProviderDereferenceConfig {

        private final ObjectProvider<Object> provider;

        ProviderDereferenceConfig(ObjectProvider<Object> provider) {
            this.provider = provider;
        }

        Object product() {
            return this.provider.getObject();
        }
    }

    static class LazyFieldConfig {

        @Lazy
        private Object collaborator;
    }

    static class ConstructorContextConfig {

        ConstructorContextConfig(ApplicationContext applicationContext) {}
    }
}
