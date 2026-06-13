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
import org.springframework.context.annotation.Bean;

/**
 * Tests for the experimental {@link BytecodeLookupDetector} prototype.
 *
 * <p>The detector inspects the real bytecode of a configuration class and reports whether it
 * actually performs a dynamic bean lookup, so a conservatively-dynamic class that never looks
 * a bean up can be safely downgraded to <em>pure</em>.
 *
 * @author Spring Framework Team
 */
class BytecodeLookupDetectorTests {

    @Test
    void configurationWithoutAnyLookupIsPure() {
        assertThat(BytecodeLookupDetector.detect(PureBeanConfig.class)).isEqualTo(BytecodeLookupDetector.Result.PURE);
    }

    @Test
    void configurationHoldingAnUnusedProviderIsPure() {
        // The class merely declares an ObjectProvider field (which makes the reflective
        // detector flag it as dynamic) but never dereferences it: provably pure.
        assertThat(BytecodeLookupDetector.detect(UnusedProviderConfig.class))
                .isEqualTo(BytecodeLookupDetector.Result.PURE);
    }

    @Test
    void configurationCallingGetBeanIsDynamic() {
        assertThat(BytecodeLookupDetector.detect(GetBeanConfig.class)).isEqualTo(BytecodeLookupDetector.Result.DYNAMIC);
    }

    @Test
    void configurationDereferencingProviderIsDynamic() {
        assertThat(BytecodeLookupDetector.detect(ProviderDereferenceConfig.class))
                .isEqualTo(BytecodeLookupDetector.Result.DYNAMIC);
    }

    @Test
    void configurationWithBeanSelfInvocationIsDynamic() {
        assertThat(BytecodeLookupDetector.detect(SelfInvocationConfig.class))
                .isEqualTo(BytecodeLookupDetector.Result.DYNAMIC);
    }

    static class PureBeanConfig {

        @Bean
        String first() {
            return "first";
        }

        @Bean
        Integer second() {
            return 42;
        }
    }

    static class UnusedProviderConfig {

        @SuppressWarnings("unused")
        private ObjectProvider<String> provider;

        @Bean
        String value() {
            return "value";
        }
    }

    static class GetBeanConfig {

        private final BeanFactory beanFactory;

        GetBeanConfig(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Bean
        String value() {
            return this.beanFactory.getBean(String.class) + "!";
        }
    }

    static class ProviderDereferenceConfig {

        private final ObjectProvider<String> provider;

        ProviderDereferenceConfig(ObjectProvider<String> provider) {
            this.provider = provider;
        }

        @Bean
        String value() {
            return this.provider.getObject();
        }
    }

    static class SelfInvocationConfig {

        @Bean
        String dependency() {
            return "dependency";
        }

        @Bean
        String consumer() {
            // A direct call to another @Bean method on the same class: in a full
            // (CGLIB-proxied) configuration this is intercepted to return the singleton.
            return dependency() + "-consumer";
        }
    }
}
