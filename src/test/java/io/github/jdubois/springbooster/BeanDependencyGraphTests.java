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
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.assertj.core.api.Assertions.assertThat;

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

}
