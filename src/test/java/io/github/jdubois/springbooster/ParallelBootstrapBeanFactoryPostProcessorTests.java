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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;

/**
 * Planning and marking tests for {@link ParallelBootstrapBeanFactoryPostProcessor}
 * operating directly on a bean factory (without a full application context).
 *
 * @author Spring Framework Team
 */
class ParallelBootstrapBeanFactoryPostProcessorTests {

    private static final String CONFIGURATION_CLASS_ATTRIBUTE =
            "org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass";

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void marksSafeSingletonsForBackgroundInitAndInstallsExecutor() {
        registerSingleton("a");
        registerSingleton("b");

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("a")).isTrue();
        assertThat(isBackgroundInit("b")).isTrue();
        assertThat(this.beanFactory.getBootstrapExecutor()).isNotNull();
    }

    @Test
    void doesNotMarkLazyBeans() {
        RootBeanDefinition lazy = new RootBeanDefinition(Object.class);
        lazy.setLazyInit(true);
        this.beanFactory.registerBeanDefinition("lazy", lazy);
        registerSingleton("eager");

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("lazy")).isFalse();
        assertThat(isBackgroundInit("eager")).isTrue();
    }

    @Test
    void doesNotMarkPrototypeBeans() {
        RootBeanDefinition prototype = new RootBeanDefinition(Object.class);
        prototype.setScope(AbstractBeanDefinition.SCOPE_PROTOTYPE);
        this.beanFactory.registerBeanDefinition("prototype", prototype);

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("prototype")).isFalse();
    }

    @Test
    void doesNotMarkInfrastructurePostProcessors() {
        this.beanFactory.registerBeanDefinition("bpp", new RootBeanDefinition(SampleBeanPostProcessor.class));

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("bpp")).isFalse();
    }

    @Test
    void doesNotMarkBeansInvolvedInCycles() {
        registerWithConstructorRef("a", "b");
        registerWithConstructorRef("b", "a");

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("a", "b");
    }

    @Test
    void doesNotMarkBeansThatOptedOut() {
        RootBeanDefinition optedOut = new RootBeanDefinition(Object.class);
        optedOut.setAttribute(ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE, Boolean.TRUE);
        this.beanFactory.registerBeanDefinition("optedOut", optedOut);
        registerSingleton("included");

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).contains("included").doesNotContain("optedOut");
    }

    @Test
    void respectsCustomCandidateFilter() {
        registerSingleton("keep");
        registerSingleton("drop");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .candidateFilter(name -> name.equals("keep"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).containsExactly("keep");
    }

    @Test
    void excludesBeanPulledByTypeFromMainThreadBean() {
        this.beanFactory.registerBeanDefinition("leaf", new RootBeanDefinition(Leaf.class));
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ConstructorConsumer.class));
        // The consumer is forced onto the main thread by the filter; it pulls "leaf"
        // by type, so "leaf" must not be backgrounded even though it passes the filter.
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .candidateFilter(name -> name.equals("leaf"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).isEmpty();
    }

    @Test
    void excludesBeanPulledViaObjectProviderFromMainThreadBean() {
        this.beanFactory.registerBeanDefinition("leaf", new RootBeanDefinition(Leaf.class));
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ProviderConsumer.class));
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .candidateFilter(name -> name.equals("leaf"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).isEmpty();
    }

    @Test
    void backgroundsBeanPulledViaObjectProviderWhenProviderEdgesAreDeferred() {
        this.beanFactory.registerBeanDefinition("leaf", new RootBeanDefinition(Leaf.class));
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ProviderConsumer.class));
        // "consumer" stays on the main thread but reaches "leaf" only through an
        // ObjectProvider. With provider edges deferred, that edge no longer crosses the
        // boundary, so "leaf" is free to be backgrounded.
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .candidateFilter(name -> name.equals("leaf"))
                .deferProviderEdges(true)
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).containsExactly("leaf");
    }

    @Test
    void backgroundsByTypeConnectedBeansWhenAllAreEligible() {
        this.beanFactory.registerBeanDefinition("leaf", new RootBeanDefinition(Leaf.class));
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ConstructorConsumer.class));

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).containsExactlyInAnyOrder("leaf", "consumer");
    }

    @Test
    void doesNotBackgroundFullConfigurationClassBeans() {
        RootBeanDefinition config = new RootBeanDefinition(Object.class);
        config.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, "full");
        this.beanFactory.registerBeanDefinition("config", config);
        registerSingleton("included");

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        // A configuration class with no @Bean singletons (so it is not reached by the
        // factory->bean co-location rule) must still stay on the main thread, because it
        // can be pulled dynamically by type or by annotation (e.g. getBeansWithAnnotation).
        assertThat(candidates).contains("included").doesNotContain("config");
    }

    @Test
    void doesNotBackgroundLiteConfigurationClassBeans() {
        RootBeanDefinition config = new RootBeanDefinition(Object.class);
        config.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, "lite");
        this.beanFactory.registerBeanDefinition("config", config);

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("config");
    }

    @Test
    void killSwitchDisablesPlanningAndExecutor() {
        registerSingleton("a");
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().enabled(false).build();

        new ParallelBootstrapBeanFactoryPostProcessor(settings).postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("a")).isFalse();
        assertThat(this.beanFactory.getBootstrapExecutor()).isNull();
    }

    @Test
    void doesNotOverrideExistingBootstrapExecutor() {
        registerSingleton("a");
        this.beanFactory.setBootstrapExecutor(Runnable::run);

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        assertThat(isBackgroundInit("a")).isFalse();
    }

    @Test
    void serializesSharedInfraConsumersByDefault() {
        // dataSource is a shared, terminal main-thread leaf (forced mainline by being a
        // depends-on target). Two independent consumers read it by reference. By default
        // the connectivity-safe rule pulls both consumers onto the main thread.
        registerSingleton("dataSource");
        registerWithDependsOn("entityManagerFactory", "dataSource");
        registerWithConstructorRef("flyway", "dataSource");
        registerWithConstructorRef("liquibase", "dataSource");

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway", "liquibase", "dataSource");
    }

    @Test
    void backgroundsSharedInfraConsumersWhenEnabled() {
        registerSingleton("dataSource");
        registerWithDependsOn("entityManagerFactory", "dataSource");
        registerWithConstructorRef("flyway", "dataSource");
        registerWithConstructorRef("liquibase", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundSharedInfraConsumers(true)
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // The two independent consumers may now run concurrently, while the shared
        // DataSource barrier itself stays on the main thread.
        assertThat(candidates).contains("flyway", "liquibase").doesNotContain("dataSource");
    }

    @Test
    void stillForcesConsumerMainlineWhenAMainThreadBeanDependsOnIt() {
        // Reverse direction: a main-thread bean (puller) depends on the consumer, so the
        // consumer is pulled by type during the puller's main-thread creation and must
        // stay mainline even with the relaxation enabled.
        registerSingleton("dataSource");
        registerWithDependsOn("entityManagerFactory", "dataSource");
        registerWithConstructorRef("flyway", "dataSource");
        registerWithConstructorRef("puller", "flyway");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundSharedInfraConsumers(true)
                .candidateFilter(name -> !name.equals("puller"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway");
    }

    @Test
    void doesNotTreatNonLeafAsBarrier() {
        // dataSource itself depends on a backgroundable bean, so it is not a completed
        // leaf and cannot be a barrier; its consumer stays mainline.
        registerSingleton("infra");
        registerWithConstructorRef("dataSource", "infra");
        registerWithDependsOn("entityManagerFactory", "dataSource");
        registerWithConstructorRef("flyway", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundSharedInfraConsumers(true)
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway");
    }

    @Test
    void doesNotTreatCyclicBeanAsBarrier() {
        // dataSource participates in a dependency cycle, so it is not a barrier; its
        // consumer stays mainline.
        registerWithConstructorRef("dataSource", "other");
        registerWithConstructorRef("other", "dataSource");
        registerWithDependsOn("entityManagerFactory", "dataSource");
        registerWithConstructorRef("flyway", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundSharedInfraConsumers(true)
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway");
    }

    @Test
    void colocatesFactoryMethodBeanWithDynamicConfigurationByDefault() {
        registerDynamicConfigWithProduct();

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        // A dynamic configuration is present, so its @Bean product is co-located on the
        // main thread by default.
        assertThat(candidates).doesNotContain("product");
    }

    @Test
    void backgroundsFactoryMethodBeanInAllowlist() {
        registerDynamicConfigWithProduct();
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundBeanNames("product")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // Naming the product drops only its co-location edge, so it may now background even
        // though the dynamic configuration stays on the main thread.
        assertThat(candidates).contains("product").doesNotContain("config");
    }

    @Test
    void backgroundsFactoryMethodBeanMarkedWithForceBackgroundAttribute() {
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition();
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        product.setAttribute(ParallelBootstrapSettings.FORCE_BACKGROUND_ATTRIBUTE, Boolean.TRUE);
        this.beanFactory.registerBeanDefinition("product", product);

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        assertThat(candidates).contains("product").doesNotContain("config");
    }

    @Test
    void allowlistDoesNotBackgroundForcedMainlineDependsOnTarget() {
        // flyway is a depends-on target of entityManagerFactory, so it is forced onto the
        // main thread; allow-listing it must not override that structural safety rule.
        registerSingleton("dataSource");
        registerWithDependsOn("entityManagerFactory", "flyway");
        registerWithConstructorRef("flyway", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundBeanNames("flyway")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway");
    }

    @Test
    void allowlistStillRespectsConnectivitySafety() {
        // The allow-listed product is pulled by type by a main-thread consumer, so it must
        // still be kept on the main thread despite losing its co-location edge.
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition(Leaf.class);
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("product", product);
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ConstructorConsumer.class));
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundBeanNames("product")
                .candidateFilter(name -> !name.equals("consumer"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("product");
    }

    // --- Solution 2: named completed-leaf barriers (barrierBeanNames) ---

    @Test
    void namedBarrierLetsCoLocatedConsumersOverlapWithoutFlag() {
        // A shared DataSource exposed only as a co-located @Bean of a dynamic configuration is
        // not in the forced-mainline set, so the structural barrier predicate rejects it and
        // its independent consumers stay serialized. Naming it as a barrier lets those
        // consumers overlap -- without enabling backgroundSharedInfraConsumers.
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("dataSource", "config");
        registerFactoryBean("flyway", "config", "dataSource");
        registerFactoryBean("liquibase", "config", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .barrierBeanNames("dataSource")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).contains("flyway", "liquibase").doesNotContain("dataSource", "config");
    }

    @Test
    void namedBarrierIsIgnoredWhenNotALeaf() {
        // dataSource itself pulls a backgroundable bean during its own construction, so it is
        // not a completed leaf; naming it a barrier must not free its consumers.
        register("config", DynamicFactoryConfig.class);
        registerSingleton("backgroundable");
        registerFactoryBean("dataSource", "config", "backgroundable");
        registerFactoryBean("flyway", "config", "dataSource");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .barrierBeanNames("dataSource")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("flyway");
    }

    @Test
    void namedBarrierStillForcesConsumerMainlineWhenAMainThreadBeanDependsOnIt() {
        // Reverse direction: a main-thread bean depends on the consumer, so the consumer is
        // pulled by type during the puller's main-thread creation and must stay mainline even
        // though its only dependency is a named barrier.
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("dataSource", "config");
        registerFactoryBean("flyway", "config", "dataSource");
        registerWithConstructorRef("puller", "flyway");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .barrierBeanNames("dataSource")
                .candidateFilter(name -> !name.equals("puller"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // puller depends on flyway, so flyway is pulled onto the main thread during the
        // puller's main-thread creation and must stay there.
        assertThat(candidates).doesNotContain("flyway");
    }

    // --- Solution 3: co-background groups (coBackgroundGroups) ---

    @Test
    void coBackgroundGroupBackgroundsMembersDespiteDynamicConfiguration() {
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("alpha", "config");
        registerFactoryBean("beta", "config");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .coBackgroundGroup("alpha", "beta")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // Both members lose their co-location edge, so they may background while the dynamic
        // configuration stays on the main thread.
        assertThat(candidates).contains("alpha", "beta").doesNotContain("config");
    }

    @Test
    void coBackgroundGroupDropsInterMemberEdgeSoMemberOverlapsCompletedDependency() {
        // memberB is a depends-on target (forced mainline, completed before fan-out). memberA
        // reads it. Without the group, memberA is pulled onto the main thread by the memberA ->
        // memberB sync edge; declaring them a co-background group drops that inter-member edge,
        // so memberA may overlap the already-completed memberB.
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("memberB", "config");
        registerFactoryBean("memberA", "config", "memberB");
        registerWithDependsOn("external", "memberB");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .coBackgroundGroup("memberA", "memberB")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).contains("memberA").doesNotContain("memberB");
    }

    @Test
    void coBackgroundGroupWithoutInterMemberEdgeKeepsMemberMainline() {
        // Control for the previous test: with the same topology but no group declared, the
        // memberA -> memberB sync edge keeps memberA on the main thread.
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("memberB", "config");
        registerFactoryBean("memberA", "config", "memberB");
        registerWithDependsOn("external", "memberB");
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundBeanNames("memberA", "memberB")
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // Allow-listing alone drops co-location but keeps the inter-member edge, so memberA is
        // still pulled mainline by its dependency on the forced-mainline memberB.
        assertThat(candidates).doesNotContain("memberA", "memberB");
    }

    // --- Spring Boot Web profile (springBootWebProfile) ---

    @Test
    void springBootWebProfileBackgroundsCuratedWebBeansOnWebContext() {
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("jacksonObjectMapper", "config");
        registerFactoryBean("springSecurityFilterChain", "config");
        registerFactoryBean("cacheManager", "config");
        registerWebServerFactory();
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().springBootWebProfile(true).build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // The curated web/security/cache beans lose their co-location edge and may background even
        // though the dynamic configuration stays on the main thread.
        assertThat(candidates)
                .contains("jacksonObjectMapper", "springSecurityFilterChain", "cacheManager")
                .doesNotContain("config");
    }

    @Test
    void springBootWebProfileColocatesCuratedBeansWithoutTheFlag() {
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("jacksonObjectMapper", "config");
        registerFactoryBean("cacheManager", "config");
        registerWebServerFactory();

        List<String> candidates = new ParallelBootstrapBeanFactoryPostProcessor().planCandidates(this.beanFactory);

        // Without springBootWebProfile the curated beans are co-located like any other @Bean of a
        // dynamic configuration.
        assertThat(candidates).doesNotContain("jacksonObjectMapper", "cacheManager");
    }

    @Test
    void springBootWebProfileIsInertOnNonWebContext() {
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("jacksonObjectMapper", "config");
        registerFactoryBean("cacheManager", "config");
        // No web-server-factory bean: the context is not a web application.
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().springBootWebProfile(true).build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        // The profile detects a non-web context and leaves the generic (co-located) plan untouched.
        assertThat(candidates).doesNotContain("jacksonObjectMapper", "cacheManager");
    }

    @Test
    void springBootWebProfileStillRespectsForcedMainline() {
        // springSecurityFilterChain is a depends-on target of entityManagerFactory, so it is forced
        // onto the main thread; the profile must not override that structural safety rule.
        register("config", DynamicFactoryConfig.class);
        registerWithDependsOn("entityManagerFactory", "springSecurityFilterChain");
        registerFactoryBean("springSecurityFilterChain", "config");
        registerWebServerFactory();
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().springBootWebProfile(true).build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("springSecurityFilterChain");
    }

    @Test
    void springBootWebProfileStillRespectsConnectivitySafety() {
        // The curated cacheManager is pulled by type by a main-thread consumer, so it must stay on
        // the main thread despite losing its co-location edge.
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition cacheManager = new RootBeanDefinition(Leaf.class);
        cacheManager.setFactoryBeanName("config");
        cacheManager.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("cacheManager", cacheManager);
        this.beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(ConstructorConsumer.class));
        registerWebServerFactory();
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .springBootWebProfile(true)
                .candidateFilter(name -> !name.equals("consumer"))
                .build();

        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).doesNotContain("cacheManager");
    }

    @Test
    void springBootWebProfileDetectsWebContextByTypeSimpleName() {
        register("config", DynamicFactoryConfig.class);
        registerFactoryBean("cacheManager", "config");
        registerWebServerFactory();
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().springBootWebProfile(true).build();

        // Detection is by the web-server-factory type, not by the cacheManager bean name, so the
        // profile activates and frees the curated bean.
        List<String> candidates =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).planCandidates(this.beanFactory);

        assertThat(candidates).contains("cacheManager");
    }

    private void registerWebServerFactory() {
        this.beanFactory.registerBeanDefinition(
                "webServerFactory", new RootBeanDefinition(ServletWebServerFactory.class));
    }

    // --- JPA EntityManagerFactory background bootstrap (backgroundEntityManagerFactory) ---

    @Test
    void backgroundEntityManagerFactoryWiresDeferredBootstrapWhenEnabled() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundEntityManagerFactory(true)
                .build();

        new ParallelBootstrapBeanFactoryPostProcessor(settings).postProcessBeanFactory(this.beanFactory);

        // The EntityManagerFactory FactoryBean stays on the main thread (it is never a background
        // candidate), but its native build is offloaded by wiring its bootstrapExecutor property.
        Object value = this.beanFactory
                .getBeanDefinition("entityManagerFactory")
                .getPropertyValues()
                .get(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY);
        assertThat(value).isInstanceOf(AsyncTaskExecutor.class);
        // The bootstrap executor is installed even though no bean is backgrounded, so the deferred
        // build has somewhere to run.
        assertThat(this.beanFactory.getBootstrapExecutor()).isNotNull();
    }

    @Test
    void backgroundEntityManagerFactoryIsInertWhenDisabled() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));

        new ParallelBootstrapBeanFactoryPostProcessor().postProcessBeanFactory(this.beanFactory);

        Object value = this.beanFactory
                .getBeanDefinition("entityManagerFactory")
                .getPropertyValues()
                .get(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY);
        assertThat(value).isNull();
    }

    private void registerFactoryBean(String beanName, String factoryBeanName, String... constructorRefs) {
        RootBeanDefinition bd = new RootBeanDefinition(Leaf.class);
        bd.setFactoryBeanName(factoryBeanName);
        bd.setFactoryMethodName("product");
        if (constructorRefs.length > 0) {
            ConstructorArgumentValues cav = new ConstructorArgumentValues();
            for (String ref : constructorRefs) {
                cav.addGenericArgumentValue(new RuntimeBeanReference(ref));
            }
            bd.setConstructorArgumentValues(cav);
        }
        this.beanFactory.registerBeanDefinition(beanName, bd);
    }

    private void registerDynamicConfigWithProduct() {
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition(Leaf.class);
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("product", product);
    }

    private void register(String beanName, Class<?> type) {
        this.beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(type));
    }

    @Test
    void virtualThreadsInstallVirtualThreadExecutor() throws Exception {
        registerSingleton("a");
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().useVirtualThreads(true).build();

        new ParallelBootstrapBeanFactoryPostProcessor(settings).postProcessBeanFactory(this.beanFactory);

        Executor executor = this.beanFactory.getBootstrapExecutor();
        assertThat(executor).isNotNull();
        CompletableFuture<Boolean> wasVirtual =
                CompletableFuture.supplyAsync(() -> Thread.currentThread().isVirtual(), executor);
        assertThat(wasVirtual.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void virtualThreadsAreTheDefaultExecutor() throws Exception {
        registerSingleton("a");
        // No explicit useVirtualThreads(...) call: the default must install a
        // virtual-thread-per-task executor.
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder().build();
        assertThat(settings.isUseVirtualThreads()).isTrue();

        new ParallelBootstrapBeanFactoryPostProcessor(settings).postProcessBeanFactory(this.beanFactory);

        Executor executor = this.beanFactory.getBootstrapExecutor();
        assertThat(executor).isNotNull();
        CompletableFuture<Boolean> wasVirtual =
                CompletableFuture.supplyAsync(() -> Thread.currentThread().isVirtual(), executor);
        assertThat(wasVirtual.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void useVirtualThreadsFalseInstallsPlatformThreadExecutor() throws Exception {
        registerSingleton("a");
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().useVirtualThreads(false).build();

        new ParallelBootstrapBeanFactoryPostProcessor(settings).postProcessBeanFactory(this.beanFactory);

        Executor executor = this.beanFactory.getBootstrapExecutor();
        assertThat(executor).isNotNull();
        CompletableFuture<Boolean> wasVirtual =
                CompletableFuture.supplyAsync(() -> Thread.currentThread().isVirtual(), executor);
        assertThat(wasVirtual.get(5, TimeUnit.SECONDS)).isFalse();
    }

    private void registerSingleton(String beanName) {
        this.beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(Object.class));
    }

    private void registerWithDependsOn(String beanName, String... dependsOn) {
        RootBeanDefinition bd = new RootBeanDefinition(Object.class);
        bd.setDependsOn(dependsOn);
        this.beanFactory.registerBeanDefinition(beanName, bd);
    }

    private void registerWithConstructorRef(String beanName, String ref) {
        RootBeanDefinition bd = new RootBeanDefinition(Object.class);
        ConstructorArgumentValues cav = new ConstructorArgumentValues();
        cav.addGenericArgumentValue(new RuntimeBeanReference(ref));
        bd.setConstructorArgumentValues(cav);
        this.beanFactory.registerBeanDefinition(beanName, bd);
    }

    private boolean isBackgroundInit(String beanName) {
        return ((AbstractBeanDefinition) this.beanFactory.getBeanDefinition(beanName)).isBackgroundInit();
    }

    static class SampleBeanPostProcessor implements BeanPostProcessor {}

    // A stand-in whose simple name matches a Spring Boot Web marker type, so the web profile's
    // architecture detection recognises this bare bean factory as a web application context.
    static class ServletWebServerFactory {}

    // A concrete AbstractEntityManagerFactoryBean used to exercise the JPA background bootstrap
    // wiring. It is never instantiated by the planner (the native factory is never built), so the
    // abstract hook can simply fail fast if ever called.
    static class StubEntityManagerFactoryBean extends AbstractEntityManagerFactoryBean {

        @Override
        protected jakarta.persistence.EntityManagerFactory createNativeEntityManagerFactory() {
            throw new UnsupportedOperationException("not instantiated in tests");
        }
    }

    static class Leaf {}

    static class DynamicFactoryConfig implements org.springframework.context.ApplicationContextAware {

        @Override
        public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {}

        Leaf product() {
            return new Leaf();
        }
    }

    static class ConstructorConsumer {
        ConstructorConsumer(Leaf leaf) {}
    }

    static class ProviderConsumer {

        @org.springframework.beans.factory.annotation.Autowired
        org.springframework.beans.factory.ObjectProvider<Leaf> leaf;
    }
}
