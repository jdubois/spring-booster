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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Tests for {@link BeanDependencyGraph}.
 *
 * @author Spring Framework Team
 */
class BeanDependencyGraphTests {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void constructorReferenceProducesEdge() {
        registerWithConstructorRefs("a");
        registerWithConstructorRefs("b", "a");

        BeanDependencyGraph graph = build("a", "b");

        assertThat(graph.getDependencies("b")).containsExactly("a");
        assertThat(graph.getDependencies("a")).isEmpty();
    }

    @Test
    void propertyReferenceProducesEdge() {
        registerWithConstructorRefs("a");
        RootBeanDefinition b = new RootBeanDefinition(Object.class);
        b.setPropertyValues(new MutablePropertyValues().add("dep", new RuntimeBeanReference("a")));
        this.beanFactory.registerBeanDefinition("b", b);

        BeanDependencyGraph graph = build("a", "b");

        assertThat(graph.getDependencies("b")).containsExactly("a");
    }

    @Test
    void dependsOnProducesEdge() {
        registerWithConstructorRefs("a");
        RootBeanDefinition b = new RootBeanDefinition(Object.class);
        b.setDependsOn("a");
        this.beanFactory.registerBeanDefinition("b", b);

        BeanDependencyGraph graph = build("a", "b");

        assertThat(graph.getDependencies("b")).containsExactly("a");
    }

    @Test
    void edgesToUnknownNodesAreIgnored() {
        registerWithConstructorRefs("b", "missing");

        BeanDependencyGraph graph = build("b");

        assertThat(graph.getDependencies("b")).isEmpty();
    }

    @Test
    void layersOrderDependenciesBeforeDependents() {
        registerWithConstructorRefs("a");
        registerWithConstructorRefs("b", "a");
        registerWithConstructorRefs("c", "b");

        List<Set<String>> layers = build("a", "b", "c").computeLayers();

        assertThat(layers).containsExactly(Set.of("a"), Set.of("b"), Set.of("c"));
    }

    @Test
    void independentBeansShareALayer() {
        registerWithConstructorRefs("a");
        registerWithConstructorRefs("b");
        registerWithConstructorRefs("root", "a", "b");

        List<Set<String>> layers = build("a", "b", "root").computeLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactlyInAnyOrder("a", "b");
        assertThat(layers.get(1)).containsExactly("root");
    }

    @Test
    void diamondDependenciesAreLayeredCorrectly() {
        registerWithConstructorRefs("top");
        registerWithConstructorRefs("left", "top");
        registerWithConstructorRefs("right", "top");
        registerWithConstructorRefs("bottom", "left", "right");

        List<Set<String>> layers = build("top", "left", "right", "bottom").computeLayers();

        assertThat(layers.get(0)).containsExactly("top");
        assertThat(layers.get(1)).containsExactlyInAnyOrder("left", "right");
        assertThat(layers.get(2)).containsExactly("bottom");
    }

    @Test
    void directCycleIsDetected() {
        registerWithConstructorRefs("a", "b");
        registerWithConstructorRefs("b", "a");

        Set<String> cyclic = build("a", "b").beansInCycles();

        assertThat(cyclic).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void selfReferenceIsDetectedAsCycle() {
        registerWithConstructorRefs("a", "a");

        Set<String> cyclic = build("a").beansInCycles();

        assertThat(cyclic).containsExactly("a");
    }

    @Test
    void largerCycleIsDetectedAndAcyclicBeansAreNot() {
        registerWithConstructorRefs("a", "b");
        registerWithConstructorRefs("b", "c");
        registerWithConstructorRefs("c", "a");
        registerWithConstructorRefs("standalone");

        Set<String> cyclic = build("a", "b", "c", "standalone").beansInCycles();

        assertThat(cyclic).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void acyclicGraphHasNoCycles() {
        registerWithConstructorRefs("a");
        registerWithConstructorRefs("b", "a");

        assertThat(build("a", "b").beansInCycles()).isEmpty();
    }

    @Test
    void byTypeConstructorAutowiringProducesEdge() {
        register("leaf", Leaf.class);
        register("consumer", ConstructorConsumer.class);

        BeanDependencyGraph graph = build("leaf", "consumer");

        assertThat(graph.getDependencies("consumer")).contains("leaf");
        assertThat(graph.getSyncDependencies("consumer")).contains("leaf");
    }

    @Test
    void autowiredFieldProducesEdge() {
        register("leaf", Leaf.class);
        register("consumer", FieldConsumer.class);

        assertThat(build("leaf", "consumer").getSyncDependencies("consumer")).contains("leaf");
    }

    @Test
    void objectProviderInjectionProducesEdge() {
        register("leaf", Leaf.class);
        register("consumer", ProviderConsumer.class);

        assertThat(build("leaf", "consumer").getSyncDependencies("consumer")).contains("leaf");
    }

    @Test
    void objectProviderEdgeIsExcludedFromSyncWhenDeferred() {
        register("leaf", Leaf.class);
        register("consumer", ProviderConsumer.class);

        BeanDependencyGraph graph =
                BeanDependencyGraph.build(this.beanFactory, List.of("leaf", "consumer"), true, true);

        // With deferral enabled the provider edge is no longer a sync (boundary) edge,
        // but it is still a genuine construction dependency for cycle/layering.
        assertThat(graph.getSyncDependencies("consumer")).doesNotContain("leaf");
        assertThat(graph.getDependencies("consumer")).contains("leaf");
    }

    @Test
    void directByTypeEdgeRemainsSyncWhenDeferralEnabled() {
        register("leaf", Leaf.class);
        register("consumer", ConstructorConsumer.class);

        BeanDependencyGraph graph =
                BeanDependencyGraph.build(this.beanFactory, List.of("leaf", "consumer"), true, true);

        // A direct (non-provider, non-lazy) by-type dependency is still resolved during
        // construction, so it stays a sync edge even when deferral is enabled.
        assertThat(graph.getSyncDependencies("consumer")).contains("leaf");
    }

    @Test
    void lazyFieldEdgeIsExcludedFromSyncWhenDeferred() {
        register("leaf", Leaf.class);
        register("consumer", LazyFieldConsumer.class);

        BeanDependencyGraph graph =
                BeanDependencyGraph.build(this.beanFactory, List.of("leaf", "consumer"), true, true);

        assertThat(graph.getSyncDependencies("consumer")).doesNotContain("leaf");
        assertThat(graph.getDependencies("consumer")).contains("leaf");
    }

    @Test
    void collectionInjectionProducesEdge() {
        register("leaf", Leaf.class);
        register("consumer", CollectionConsumer.class);

        assertThat(build("leaf", "consumer").getSyncDependencies("consumer")).contains("leaf");
    }

    @Test
    void dependsOnIsNotASyncEdge() {
        register("a", Leaf.class);
        RootBeanDefinition b = new RootBeanDefinition(Leaf.class);
        b.setDependsOn("a");
        this.beanFactory.registerBeanDefinition("b", b);

        BeanDependencyGraph graph = build("a", "b");

        assertThat(graph.getDependencies("b")).contains("a");
        assertThat(graph.getSyncDependencies("b")).doesNotContain("a");
    }

    @Test
    void dynamicFactoryMethodBeanIsColocatedWithConfigurationByDefault() {
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition();
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("product", product);

        BeanDependencyGraph graph = BeanDependencyGraph.build(this.beanFactory, List.of("config", "product"), true);

        // The dynamic configuration gains a co-location sync edge to its @Bean product, so
        // the planner keeps the product on the configuration's (main) thread.
        assertThat(graph.getSyncDependencies("config")).contains("product");
        // The genuine construction dependency still runs in the opposite direction.
        assertThat(graph.getDependencies("product")).contains("config");
    }

    @Test
    void pureFactoryMethodBeanIsNotColocatedWithConfiguration() {
        register("config", FactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition();
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("product", product);

        BeanDependencyGraph graph = BeanDependencyGraph.build(this.beanFactory, List.of("config", "product"), true);

        // A pure configuration performs no invisible by-type lookup, so its @Bean product
        // is left free to be backgrounded: no co-location edge is added.
        assertThat(graph.getSyncDependencies("config")).doesNotContain("product");
        // The genuine construction dependency still runs in the opposite direction.
        assertThat(graph.getDependencies("product")).contains("config");
    }

    @Test
    void factoryMethodColocationCanBeDisabled() {
        register("config", DynamicFactoryConfig.class);
        RootBeanDefinition product = new RootBeanDefinition();
        product.setFactoryBeanName("config");
        product.setFactoryMethodName("product");
        this.beanFactory.registerBeanDefinition("product", product);

        BeanDependencyGraph graph = BeanDependencyGraph.build(this.beanFactory, List.of("config", "product"), false);

        assertThat(graph.getSyncDependencies("config")).doesNotContain("product");
        assertThat(graph.getDependencies("product")).contains("config");
    }

    private void register(String beanName, Class<?> type) {
        this.beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(type));
    }

    private void registerWithConstructorRefs(String beanName, String... refs) {
        RootBeanDefinition bd = new RootBeanDefinition(Object.class);
        ConstructorArgumentValues cav = new ConstructorArgumentValues();
        for (String ref : refs) {
            cav.addGenericArgumentValue(new RuntimeBeanReference(ref));
        }
        bd.setConstructorArgumentValues(cav);
        this.beanFactory.registerBeanDefinition(beanName, bd);
    }

    private BeanDependencyGraph build(String... beanNames) {
        return BeanDependencyGraph.build(this.beanFactory, List.of(beanNames));
    }

    static class Leaf {}

    static class FactoryConfig {

        Leaf product() {
            return new Leaf();
        }
    }

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

    static class FieldConsumer {

        @Autowired
        Leaf leaf;
    }

    static class ProviderConsumer {

        @Autowired
        ObjectProvider<Leaf> leaf;
    }

    static class LazyFieldConsumer {

        @Autowired
        @org.springframework.context.annotation.Lazy
        Leaf leaf;
    }

    static class CollectionConsumer {

        @Autowired
        List<Leaf> leaves;
    }
}
