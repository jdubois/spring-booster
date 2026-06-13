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
import org.springframework.beans.factory.support.RootBeanDefinition;
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
            ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                    .backgroundFactoryMethodBeans(true)
                    .build();
            new ParallelBootstrapApplicationContextInitializer(settings).initialize(context);
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
                    .backgroundFactoryMethodBeans(true)
                    .candidateFilter(name -> name.equals("leaf") || name.equals("independent"))
                    .build();
            context.addBeanFactoryPostProcessor(new ParallelBootstrapBeanFactoryPostProcessor(settings));
            context.refresh();

            assertThat(context.getBean(Leaf.class)).isNotNull();
            assertThat(RecordingConfig.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void pureFactoryMethodBeansAreBackgroundedByDefault() {
        RecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Default settings: a pure configuration (no dynamic by-type lookups) no longer
            // co-locates its @Bean beans, so they are free to be backgrounded.
            new ParallelBootstrapApplicationContextInitializer().initialize(context);
            context.register(PlainRecordingConfig.class);
            context.refresh();
            assertThat(context.getBeansOfType(RecordingBean.class)).hasSize(4);
            assertThat(RecordingConfig.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void dynamicFactoryMethodBeansAreKeptMainlineByDefault() {
        DynamicRecordingConfig.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Default settings: a dynamic configuration (here it captures the
            // ApplicationContext) keeps its @Bean beans co-located on the main thread.
            new ParallelBootstrapApplicationContextInitializer().initialize(context);
            context.register(DynamicRecordingConfig.class);
            context.refresh();
            assertThat(context.getBeansOfType(RecordingBean.class)).hasSize(4);
            assertThat(DynamicRecordingConfig.creationThreads)
                    .noneMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void componentBeansAreBackgroundedWithDefaultSettings() {
        ComponentRecordingBean.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Default settings still background beans that are not produced by a @Bean
            // factory method (here, plain root bean definitions standing in for
            // component-scanned beans).
            new ParallelBootstrapApplicationContextInitializer().initialize(context);
            for (int i = 0; i < 4; i++) {
                context.registerBeanDefinition("component" + i, new RootBeanDefinition(ComponentRecordingBean.class));
            }
            context.refresh();
            assertThat(context.getBeansOfType(ComponentRecordingBean.class)).hasSize(4);
            assertThat(ComponentRecordingBean.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void deferredProviderEdgeLetsMainlineConsumerBackgroundItsProvidedBean() {
        ProviderHolder.providedLeaf = null;
        DeferredLeaf.creationThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ParallelBootstrapSettings settings =
                    ParallelBootstrapSettings.builder().deferProviderEdges(true).build();
            new ParallelBootstrapApplicationContextInitializer(settings).initialize(context);
            // "holder" is a SmartInitializingSingleton, so it is infrastructure and stays
            // on the main thread. It reaches "leaf" only through an ObjectProvider it
            // dereferences after the singletons have been instantiated, so with provider
            // edges deferred "leaf" can be backgrounded without a creation exception.
            context.registerBeanDefinition("holder", new RootBeanDefinition(ProviderHolder.class));
            context.registerBeanDefinition("leaf", new RootBeanDefinition(DeferredLeaf.class));
            context.refresh();

            assertThat(context.getBean(DeferredLeaf.class)).isNotNull();
            assertThat(ProviderHolder.providedLeaf).isSameAs(context.getBean(DeferredLeaf.class));
            assertThat(DeferredLeaf.creationThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
        }
    }

    @Test
    void configurationClassPulledByAnnotationOnMainThreadIsNotBackgrounded() {
        ConfigCreation.threads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // AnnotatedConfig is a @Configuration class with no @Bean singletons, so the
            // factory->bean co-location rule does not reach it. PullerConfig's @Bean method
            // runs on the main thread and looks AnnotatedConfig up by annotation through
            // getBeansWithAnnotation(...) -- a dynamic access no static analysis can see.
            // Before configuration classes were forced onto the main thread, AnnotatedConfig
            // was backgrounded and this refresh failed with BeanCurrentlyInCreationException
            // (the failure observed booting Spring Security's EnableWebSecurityConfiguration).
            new ParallelBootstrapApplicationContextInitializer().initialize(context);
            context.register(AnnotatedConfig.class, PullerConfig.class);
            for (int i = 0; i < 4; i++) {
                context.registerBeanDefinition("component" + i, new RootBeanDefinition(ComponentRecordingBean.class));
            }
            context.refresh();

            assertThat(context.getBeansWithAnnotation(Marker.class).values())
                    .hasAtLeastOneElementOfType(AnnotatedConfig.class);
            // The configuration class must have been created on the main thread.
            assertThat(ConfigCreation.threads).containsExactly("main");
        }
    }

    @Test
    void independentConsumersOfSharedInfraRunConcurrentlyWhenEnabled() {
        SharedInfraConfig.consumerThreads.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // sharedInfra is a terminal main-thread leaf (forced mainline by a @DependsOn
            // target). consumerA and consumerB are mutually independent and each only read
            // it -- the Flyway/Liquibase-over-a-shared-DataSource shape. With the
            // relaxation enabled they may be backgrounded and run concurrently while
            // sharedInfra itself stays on the main thread. The flag keeps factory-method
            // @Bean co-location active on its own, so backgroundFactoryMethodBeans is not
            // needed.
            ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                    .poolSize(4)
                    .backgroundSharedInfraConsumers(true)
                    .build();
            new ParallelBootstrapApplicationContextInitializer(settings).initialize(context);
            context.register(SharedInfraConfig.class);
            context.refresh();

            assertThat(context.getBean("consumerA")).isNotNull();
            assertThat(context.getBean("consumerB")).isNotNull();
            assertThat(SharedInfraConfig.consumerThreads).anyMatch(name -> name.startsWith("parallel-bootstrap-"));
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
    @EnableParallelBootstrap(poolSize = 4, backgroundFactoryMethodBeans = true)
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
    static class DynamicRecordingConfig implements org.springframework.context.ApplicationContextAware {

        static final Set<String> creationThreads = ConcurrentHashMap.newKeySet();

        @Override
        public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {}

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

    static class ComponentRecordingBean {

        static final Set<String> creationThreads = ConcurrentHashMap.newKeySet();

        ComponentRecordingBean() {
            creationThreads.add(Thread.currentThread().getName());
        }
    }

    static class DeferredLeaf {

        static final Set<String> creationThreads = ConcurrentHashMap.newKeySet();

        DeferredLeaf() {
            creationThreads.add(Thread.currentThread().getName());
        }
    }

    static class ProviderHolder implements org.springframework.beans.factory.SmartInitializingSingleton {

        static volatile DeferredLeaf providedLeaf;

        private final org.springframework.beans.factory.ObjectProvider<DeferredLeaf> leafProvider;

        ProviderHolder(org.springframework.beans.factory.ObjectProvider<DeferredLeaf> leafProvider) {
            this.leafProvider = leafProvider;
        }

        @Override
        public void afterSingletonsInstantiated() {
            // Dereferenced only after all singletons have been instantiated, never during
            // this bean's own construction.
            providedLeaf = this.leafProvider.getObject();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedInfraConfig {

        static final Set<String> consumerThreads = ConcurrentHashMap.newKeySet();

        // A terminal infrastructure leaf standing in for a shared DataSource.
        @Bean
        SharedInfra sharedInfra() {
            return new SharedInfra();
        }

        // Forces sharedInfra onto the main thread (as Spring Boot's
        // EntityManagerFactoryDependsOnPostProcessor does for the real DataSource).
        @Bean
        @org.springframework.context.annotation.DependsOn("sharedInfra")
        Object entityManagerFactory() {
            return new Object();
        }

        @Bean
        Object consumerA(SharedInfra sharedInfra) {
            consumerThreads.add(Thread.currentThread().getName());
            return new Object();
        }

        @Bean
        Object consumerB(SharedInfra sharedInfra) {
            consumerThreads.add(Thread.currentThread().getName());
            return new Object();
        }
    }

    static class SharedInfra {}

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @interface Marker {}

    static class ConfigCreation {

        static final Set<String> threads = ConcurrentHashMap.newKeySet();
    }

    // A configuration class with no @Bean singletons, so the factory->bean co-location
    // rule does not reach it; it must still be kept on the main thread because it is
    // pulled dynamically by annotation below.
    @Marker
    @Configuration(proxyBeanMethods = false)
    static class AnnotatedConfig {

        AnnotatedConfig() {
            ConfigCreation.threads.add(Thread.currentThread().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PullerConfig {

        // Runs on the main thread and resolves AnnotatedConfig by annotation, a dynamic
        // access that no static dependency analysis can observe.
        @Bean
        String puller(org.springframework.context.ApplicationContext applicationContext) {
            return String.valueOf(
                    applicationContext.getBeansWithAnnotation(Marker.class).size());
        }
    }
}
