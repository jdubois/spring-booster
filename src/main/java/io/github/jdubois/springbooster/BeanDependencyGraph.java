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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;

/**
 * An approximate, conservative dependency graph of the singleton bean definitions
 * registered in a bean factory.
 *
 * <p>Edges are extracted statically from each merged {@link BeanDefinition} using
 * the introspectable references only: {@code depends-on} declarations, the factory
 * bean reference, constructor-argument {@link BeanReference bean references}, and
 * property {@link BeanReference bean references} (including those nested inside
 * managed collections and maps). By-type autowiring edges that cannot be resolved
 * statically are intentionally not represented here; callers must treat such beans
 * conservatively.
 *
 * <p>The graph exposes two derived views used by the parallel bootstrap planner:
 * <ul>
 * <li>{@link #computeLayers()} &mdash; a topological layering (Kahn's algorithm)
 * where every bean in a layer depends only on beans in earlier layers, so a single
 * layer may be instantiated concurrently;</li>
 * <li>{@link #beansInCycles()} &mdash; the set of beans that participate in a
 * dependency cycle (a strongly connected component of size greater than one, or a
 * self-reference), computed with Tarjan's algorithm. These must be created on a
 * single thread to preserve the early-singleton-reference handshake.</li>
 * </ul>
 *
 * <p>This class performs pure in-memory analysis and never triggers bean creation.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor
 */
final class BeanDependencyGraph {

	private final Set<String> nodes;

	/** Adjacency: bean name -> names of the beans it directly depends on. */
	private final Map<String, Set<String>> dependencies;


	private BeanDependencyGraph(Set<String> nodes, Map<String, Set<String>> dependencies) {
		this.nodes = nodes;
		this.dependencies = dependencies;
	}


	/**
	 * Build a dependency graph from the given bean factory, restricted to the
	 * supplied set of bean names (typically the non-abstract singletons). Edges that
	 * point to beans outside the supplied set are ignored.
	 * @param beanFactory the bean factory to introspect
	 * @param beanNames the bean names to include as graph nodes
	 * @return the resulting dependency graph
	 */
	static BeanDependencyGraph build(ConfigurableListableBeanFactory beanFactory, Collection<String> beanNames) {
		Set<String> nodes = new LinkedHashSet<>(beanNames);
		Map<String, Set<String>> dependencies = new HashMap<>(nodes.size());
		for (String beanName : nodes) {
			Set<String> edges = new LinkedHashSet<>();
			BeanDefinition mbd = safeGetMergedBeanDefinition(beanFactory, beanName);
			if (mbd != null) {
				collectEdges(mbd, edges);
			}
			// Keep only edges that point to known nodes; self-references are retained
			// so that cycle detection can flag them.
			edges.retainAll(nodes);
			dependencies.put(beanName, edges);
		}
		return new BeanDependencyGraph(nodes, dependencies);
	}

	private static @Nullable BeanDefinition safeGetMergedBeanDefinition(
			ConfigurableListableBeanFactory beanFactory, String beanName) {
		try {
			return beanFactory.getMergedBeanDefinition(beanName);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static void collectEdges(BeanDefinition mbd, Set<String> edges) {
		String[] dependsOn = mbd.getDependsOn();
		if (dependsOn != null) {
			Collections.addAll(edges, dependsOn);
		}
		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			edges.add(factoryBeanName);
		}
		for (ValueHolder holder : mbd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
			extractReferences(holder.getValue(), edges);
		}
		for (ValueHolder holder : mbd.getConstructorArgumentValues().getGenericArgumentValues()) {
			extractReferences(holder.getValue(), edges);
		}
		for (PropertyValue pv : mbd.getPropertyValues().getPropertyValueList()) {
			extractReferences(pv.getValue(), edges);
		}
	}

	private static void extractReferences(@Nullable Object value, Set<String> edges) {
		if (value instanceof BeanReference beanReference) {
			edges.add(beanReference.getBeanName());
		}
		else if (value instanceof Iterable<?> iterable) {
			for (Object element : iterable) {
				extractReferences(element, edges);
			}
		}
		else if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				extractReferences(entry.getKey(), edges);
				extractReferences(entry.getValue(), edges);
			}
		}
		else if (value instanceof Object[] array) {
			for (Object element : array) {
				extractReferences(element, edges);
			}
		}
	}


	/**
	 * The set of bean names represented as nodes in this graph.
	 */
	Set<String> getNodes() {
		return Collections.unmodifiableSet(this.nodes);
	}

	/**
	 * The direct dependencies (outgoing edges) of the given bean.
	 */
	Set<String> getDependencies(String beanName) {
		return Collections.unmodifiableSet(this.dependencies.getOrDefault(beanName, Collections.emptySet()));
	}

	/**
	 * Compute a topological layering of the graph using Kahn's algorithm.
	 * <p>Each returned set contains beans whose dependencies are all satisfied by
	 * earlier layers, and which can therefore be instantiated concurrently. Beans
	 * participating in cycles cannot be ordered topologically and are excluded from
	 * the layering; use {@link #beansInCycles()} to obtain them.
	 * @return the ordered list of layers (each a set of bean names)
	 */
	List<Set<String>> computeLayers() {
		Map<String, Integer> remaining = new HashMap<>(this.nodes.size());
		Map<String, Set<String>> dependents = new HashMap<>(this.nodes.size());
		for (String node : this.nodes) {
			dependents.computeIfAbsent(node, k -> new LinkedHashSet<>());
		}
		for (String node : this.nodes) {
			Set<String> deps = new LinkedHashSet<>(this.dependencies.getOrDefault(node, Collections.emptySet()));
			deps.remove(node);
			remaining.put(node, deps.size());
			for (String dep : deps) {
				dependents.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(node);
			}
		}

		List<Set<String>> layers = new ArrayList<>();
		Set<String> ready = new LinkedHashSet<>();
		for (String node : this.nodes) {
			if (remaining.getOrDefault(node, 0) == 0) {
				ready.add(node);
			}
		}
		while (!ready.isEmpty()) {
			layers.add(new LinkedHashSet<>(ready));
			Set<String> next = new LinkedHashSet<>();
			for (String node : ready) {
				for (String dependent : dependents.getOrDefault(node, Collections.emptySet())) {
					int count = remaining.getOrDefault(dependent, 0) - 1;
					remaining.put(dependent, count);
					if (count == 0) {
						next.add(dependent);
					}
				}
			}
			ready = next;
		}
		return layers;
	}

	/**
	 * Compute the set of beans that participate in a dependency cycle, using
	 * Tarjan's strongly-connected-components algorithm. A bean is included if it
	 * belongs to a strongly connected component containing more than one node, or if
	 * it directly references itself.
	 * @return the set of beans involved in cycles (never {@code null})
	 */
	Set<String> beansInCycles() {
		Tarjan tarjan = new Tarjan();
		return tarjan.run();
	}


	/**
	 * Iterative implementation of Tarjan's strongly connected components algorithm.
	 * Implemented without recursion to avoid stack overflow on large graphs.
	 */
	private final class Tarjan {

		private int index = 0;

		private final Map<String, Integer> indices = new HashMap<>();

		private final Map<String, Integer> lowLink = new HashMap<>();

		private final Deque<String> stack = new ArrayDeque<>();

		private final Set<String> onStack = new HashSet<>();

		private final Set<String> result = new HashSet<>();

		Set<String> run() {
			for (String node : nodes) {
				if (!this.indices.containsKey(node)) {
					strongConnect(node);
				}
			}
			return this.result;
		}

		private void strongConnect(String start) {
			Deque<Frame> frames = new ArrayDeque<>();
			frames.push(new Frame(start, iteratorOf(start)));
			this.indices.put(start, this.index);
			this.lowLink.put(start, this.index);
			this.index++;
			this.stack.push(start);
			this.onStack.add(start);

			while (!frames.isEmpty()) {
				Frame frame = Objects.requireNonNull(frames.peek());
				boolean descended = false;
				while (frame.neighbors.hasNext()) {
					String next = frame.neighbors.next();
					if (!nodes.contains(next)) {
						continue;
					}
					if (next.equals(frame.node)) {
						// Self-reference is a cycle of one.
						this.result.add(frame.node);
						continue;
					}
					if (!this.indices.containsKey(next)) {
						this.indices.put(next, this.index);
						this.lowLink.put(next, this.index);
						this.index++;
						this.stack.push(next);
						this.onStack.add(next);
						frames.push(new Frame(next, iteratorOf(next)));
						descended = true;
						break;
					}
					else if (this.onStack.contains(next)) {
						this.lowLink.put(frame.node,
								Math.min(low(frame.node), idx(next)));
					}
				}
				if (descended) {
					continue;
				}
				if (low(frame.node) == idx(frame.node)) {
					List<String> component = new ArrayList<>();
					String w;
					do {
						w = this.stack.pop();
						this.onStack.remove(w);
						component.add(w);
					}
					while (!w.equals(frame.node));
					if (component.size() > 1) {
						this.result.addAll(component);
					}
				}
				frames.pop();
				if (!frames.isEmpty()) {
					String parent = Objects.requireNonNull(frames.peek()).node;
					this.lowLink.put(parent, Math.min(low(parent), low(frame.node)));
				}
			}
		}

		private int idx(String node) {
			Integer value = this.indices.get(node);
			return (value != null ? value : 0);
		}

		private int low(String node) {
			Integer value = this.lowLink.get(node);
			return (value != null ? value : 0);
		}

		private java.util.Iterator<String> iteratorOf(String node) {
			return new ArrayList<>(dependencies.getOrDefault(node, Collections.emptySet())).iterator();
		}
	}


	private record Frame(String node, java.util.Iterator<String> neighbors) {
	}

}
