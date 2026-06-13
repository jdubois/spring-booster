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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;

/**
 * Tests for {@link ConfigurationClassColocationAnalyzer}, which decides via bytecode
 * analysis whether {@code @Bean} co-location can be safely skipped.
 *
 * @author Spring Framework Team
 */
class ConfigurationClassColocationAnalyzerTests {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void emptyFactoryCanSkipColocation() {
        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isTrue();
    }

    @Test
    void provablySafeConfigurationCanSkipColocation() {
        registerConfig("config", SafeConfig.class, "beanOne", "beanTwo");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isTrue();
    }

    @Test
    void configurationThatSelfInvokesABeanMethodIsUnsafe() {
        registerConfig("config", SelfInvokingConfig.class, "beanOne", "beanTwo");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void configurationThatCapturesApplicationContextIsUnsafe() {
        registerConfig("config", ContextCapturingConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void configurationThatHoldsAnObjectProviderIsUnsafe() {
        registerConfig("config", ObjectProviderConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void configurationWithAContextHandleParameterIsUnsafe() {
        registerConfig("config", ContextParameterConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void configurationThatImplementsAnInterfaceIsUnsafe() {
        registerConfig("config", InterfaceImplementingConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void configurationWithANonTrivialSuperclassIsUnsafe() {
        registerConfig("config", SubclassConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    @Test
    void aSingleUnsafeConfigurationKeepsTheWholeContextColocated() {
        registerConfig("safe", SafeConfig.class, "beanOne", "beanTwo");
        registerConfig("unsafe", ContextCapturingConfig.class, "beanOne");

        assertThat(ConfigurationClassColocationAnalyzer.canSkipColocation(this.beanFactory))
                .isFalse();
    }

    private void registerConfig(String configBeanName, Class<?> configClass, String... beanMethods) {
        this.beanFactory.registerBeanDefinition(configBeanName, new RootBeanDefinition(configClass));
        for (String method : beanMethods) {
            RootBeanDefinition bd = new RootBeanDefinition();
            bd.setFactoryBeanName(configBeanName);
            bd.setFactoryMethodName(method);
            this.beanFactory.registerBeanDefinition(configBeanName + "-" + method, bd);
        }
    }

    // --- Test configuration classes (analysed via their bytecode) ---

    static class SafeBean {}

    static class SafeConfig {
        SafeBean beanOne() {
            return new SafeBean();
        }

        SafeBean beanTwo(SafeBean beanOne) {
            return new SafeBean();
        }
    }

    static class SelfInvokingConfig {
        SafeBean beanOne() {
            return new SafeBean();
        }

        SafeBean beanTwo() {
            // CGLIB self-invocation of another @Bean method.
            return beanOne();
        }
    }

    static class ContextCapturingConfig {
        @Autowired
        ApplicationContext applicationContext;

        SafeBean beanOne() {
            return new SafeBean();
        }
    }

    static class ObjectProviderConfig {
        @Autowired
        ObjectProvider<SafeBean> provider;

        SafeBean beanOne() {
            return new SafeBean();
        }
    }

    static class ContextParameterConfig {
        SafeBean beanOne(BeanFactory beanFactory) {
            return new SafeBean();
        }
    }

    interface Callback {
        void onEvent();
    }

    static class InterfaceImplementingConfig implements Callback {
        @Override
        public void onEvent() {}

        SafeBean beanOne() {
            return new SafeBean();
        }
    }

    static class BaseConfig {}

    static class SubclassConfig extends BaseConfig {
        SafeBean beanOne() {
            return new SafeBean();
        }
    }
}
