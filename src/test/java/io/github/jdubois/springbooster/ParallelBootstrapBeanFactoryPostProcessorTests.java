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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

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

    static class Leaf {}

    static class ConstructorConsumer {
        ConstructorConsumer(Leaf leaf) {}
    }

    static class ProviderConsumer {

        @org.springframework.beans.factory.annotation.Autowired
        org.springframework.beans.factory.ObjectProvider<Leaf> leaf;
    }
}
