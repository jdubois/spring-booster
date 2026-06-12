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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integration tests that bootstrap a full application context with parallel
 * bean instantiation enabled.
 *
 * @author Spring Framework Team
 */
class ParallelBootstrapIntegrationTests {

    @Test
    void contextRefreshesAndWiresBeansWithEnableAnnotation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EnabledConfig.class)) {
            assertThat(context.getBean(ServiceA.class)).isNotNull();
            assertThat(context.getBean(ServiceB.class)).isNotNull();
            assertThat(context.getBean(Aggregator.class).getServiceA()).isSameAs(context.getBean(ServiceA.class));
            assertThat(context.getBean(Aggregator.class).getServiceB()).isSameAs(context.getBean(ServiceB.class));
        }
    }

    @Test
    void independentBeansAreInstantiatedOnBootstrapThreads() {
        RecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(RecordingConfig.class)) {
            assertThat(context.getBeansOfType(RecordingBean.class)).hasSize(4);
            // At least one of the independent leaf beans should have been created on a
            // bootstrap pool thread rather than the main thread.
            assertThat(RecordingConfig.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void bootstrapExecutorIsShutDownAfterRefresh() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EnabledConfig.class)) {
            assertThat(context.getBean(ServiceA.class)).isNotNull();
            assertThat(context.getBeanFactory().getBootstrapExecutor()).isNull();
        }
    }

    @Test
    void programmaticInitializerEnablesParallelBootstrap() {
        RecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            new ParallelBootstrapApplicationContextInitializer().initialize(context);
            context.register(PlainRecordingConfig.class);
            context.refresh();
            assertThat(RecordingConfig.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
            assertThat(context.getBeanFactory().getBootstrapExecutor()).isNull();
        }
    }

    @Test
    void killSwitchKeepsBootstrapSequential() {
        RecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(DisabledConfig.class)) {
            assertThat(context.getBean(ServiceA.class)).isNotNull();
            assertThat(RecordingConfig.creationThreads).noneMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void mainThreadByTypeConsumerDoesNotTriggerBackgroundCreationException() {
        RecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ByTypeConsumerConfig.class);
            // Force "consumer" onto the main thread; it injects "leaf" by type. Before
            // by-type edges were modelled, "leaf" was backgrounded and this refresh
            // failed with BeanCurrentlyInCreationException. "independent" has no
            // dependents and is still parallelized.
            ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                    .poolSize(4)
                    .candidateFilter(name -> name.equals("leaf") || name.equals("independent"))
                    .build();
            context.addBeanFactoryPostProcessor(new ParallelBootstrapBeanFactoryPostProcessor(settings));
            context.refresh();

            assertThat(context.getBean(Leaf.class)).isNotNull();
            assertThat(RecordingConfig.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableParallelBootstrap
    static class EnabledConfig {

        @Bean
        ServiceA serviceA() {
            return new ServiceA();
        }

        @Bean
        ServiceB serviceB() {
            return new ServiceB();
        }

        @Bean
        Aggregator aggregator(ServiceA serviceA, ServiceB serviceB) {
            return new Aggregator(serviceA, serviceB);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableParallelBootstrap(poolSize = 4)
    static class RecordingConfig {

        static final Set<String> creationThreads = ConcurrentHashMap.newKeySet();

        @Bean
        RecordingBean one() {
            return new RecordingBean(creationThreads);
        }

        @Bean
        RecordingBean two() {
            return new RecordingBean(creationThreads);
        }

        @Bean
        RecordingBean three() {
            return new RecordingBean(creationThreads);
        }

        @Bean
        RecordingBean four() {
            return new RecordingBean(creationThreads);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainRecordingConfig {

        @Bean
        RecordingBean one() {
            return new RecordingBean(RecordingConfig.creationThreads);
        }

        @Bean
        RecordingBean two() {
            return new RecordingBean(RecordingConfig.creationThreads);
        }

        @Bean
        RecordingBean three() {
            return new RecordingBean(RecordingConfig.creationThreads);
        }

        @Bean
        RecordingBean four() {
            return new RecordingBean(RecordingConfig.creationThreads);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableParallelBootstrap(enabled = false)
    static class DisabledConfig {

        @Bean
        ServiceA serviceA() {
            RecordingConfig.creationThreads.add(Thread.currentThread().getName());
            return new ServiceA();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ByTypeConsumerConfig {

        // Declared before "leaf" on purpose: the main thread instantiates "consumer"
        // and resolves "leaf" by type before "leaf" has been submitted for background
        // creation, which is exactly the situation that used to throw
        // BeanCurrentlyInCreationException when "leaf" was wrongly backgrounded.
        @Bean
        ByTypeConsumer consumer(Leaf leaf) {
            return new ByTypeConsumer(leaf);
        }

        @Bean
        Leaf leaf() {
            return new Leaf();
        }

        @Bean
        RecordingBean independent() {
            return new RecordingBean(RecordingConfig.creationThreads);
        }
    }

    static class Leaf {}

    static class ByTypeConsumer {

        ByTypeConsumer(Leaf leaf) {}
    }

    static class ServiceA {}

    static class ServiceB {}

    static class Aggregator {

        private final ServiceA serviceA;

        private final ServiceB serviceB;

        Aggregator(ServiceA serviceA, ServiceB serviceB) {
            this.serviceA = serviceA;
            this.serviceB = serviceB;
        }

        ServiceA getServiceA() {
            return this.serviceA;
        }

        ServiceB getServiceB() {
            return this.serviceB;
        }
    }

    static class RecordingBean {

        RecordingBean(Set<String> creationThreads) {
            creationThreads.add(Thread.currentThread().getName());
        }
    }
}
