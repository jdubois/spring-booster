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
import java.util.LinkedHashMap;
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
 * <p>Edges are extracted from each merged {@link BeanDefinition} using both the
 * declared references &mdash; {@code depends-on} declarations, the factory bean
 * reference, constructor-argument {@link BeanReference bean references}, and property
 * {@link BeanReference bean references} (including those nested inside managed
 * collections and maps) &mdash; and the <em>by-type</em> autowiring edges discovered
 * by {@link AutowiredEdgeResolver} ({@code @Bean} factory-method parameters, autowired
 * constructor parameters, and {@code @Autowired} / {@code ObjectProvider} injection
 * points). Together these make every dependency relationship between the analysed
 * beans visible to the planner.
 *
 * <p>Edges are additionally classified as <em>forced</em> or <em>sync</em>. A forced
 * edge ({@code depends-on} or the factory-bean reference) is one whose target the
 * framework eagerly instantiates on the main thread before backgrounding the source,
 * so it never crosses the background/mainline boundary unsafely. A sync edge (a
 * constructor/property reference or a by-type autowiring edge) is resolved on the
 * source bean's own thread, so it constrains which beans may share the background
 * set. {@link #getSyncDependencies(String)} exposes the latter for the planner.
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

    /**
     * Adjacency restricted to <em>sync</em> / co-location edges: the dependencies
     * resolved on the source bean's own thread (constructor/property references and
     * by-type autowiring) plus the factory&rarr;bean co-location edges added by
     * {@link #addFactoryColocationEdges}. These are the edges that must not cross the
     * background/mainline boundary, as opposed to the {@code depends-on} / factory-bean
     * edges that the framework force-instantiates on the main thread.
     */
    private final Map<String, Set<String>> syncDependencies;

    private BeanDependencyGraph(
            Set<String> nodes, Map<String, Set<String>> dependencies, Map<String, Set<String>> syncDependencies) {
        this.nodes = nodes;
        this.dependencies = dependencies;
        this.syncDependencies = syncDependencies;
    }

    /**
     * Build a dependency graph from the given bean factory, restricted to the
     * supplied set of bean names (typically all registered bean definitions). Edges
     * that point to beans outside the supplied set are ignored. Factory-method beans
     * are co-located with their configuration class (see
     * {@link #addFactoryColocationEdges}).
     * @param beanFactory the bean factory to introspect
     * @param beanNames the bean names to include as graph nodes
     * @return the resulting dependency graph
     */
    static BeanDependencyGraph build(ConfigurableListableBeanFactory beanFactory, Collection<String> beanNames) {
        return build(beanFactory, beanNames, true);
    }

    /**
     * Build a dependency graph from the given bean factory, restricted to the
     * supplied set of bean names (typically all registered bean definitions). Edges
     * that point to beans outside the supplied set are ignored.
     * @param beanFactory the bean factory to introspect
     * @param beanNames the bean names to include as graph nodes
     * @param colocateFactoryMethodBeans whether to add factory&rarr;bean co-location
     * edges that keep every {@code @Bean} bean on its configuration's thread (see
     * {@link #addFactoryColocationEdges})
     * @return the resulting dependency graph
     */
    static BeanDependencyGraph build(
            ConfigurableListableBeanFactory beanFactory,
            Collection<String> beanNames,
            boolean colocateFactoryMethodBeans) {
        return build(beanFactory, beanNames, colocateFactoryMethodBeans, false);
    }

    /**
     * Build a dependency graph from the given bean factory, restricted to the
     * supplied set of bean names (typically all registered bean definitions). Edges
     * that point to beans outside the supplied set are ignored.
     * @param beanFactory the bean factory to introspect
     * @param beanNames the bean names to include as graph nodes
     * @param colocateFactoryMethodBeans whether to add factory&rarr;bean co-location
     * edges that keep every {@code @Bean} bean on its configuration's thread (see
     * {@link #addFactoryColocationEdges})
     * @param deferProviderEdges whether by-type edges reached only through an
     * {@code ObjectProvider}/{@code ObjectFactory}/{@code Provider} wrapper or a
     * {@code @Lazy} injection point are treated as <em>deferred</em> &mdash; kept in the
     * full dependency set (for cycle detection and layering) but excluded from the
     * <em>sync</em> connectivity view, so they no longer constrain the
     * background/mainline boundary
     * @return the resulting dependency graph
     */
    static BeanDependencyGraph build(
            ConfigurableListableBeanFactory beanFactory,
            Collection<String> beanNames,
            boolean colocateFactoryMethodBeans,
            boolean deferProviderEdges) {
        Set<String> nodes = new LinkedHashSet<>(beanNames);
        Map<String, Set<String>> dependencies = new HashMap<>(nodes.size());
        Map<String, Set<String>> syncDependencies = new HashMap<>(nodes.size());
        for (String beanName : nodes) {
            Set<String> edges = new LinkedHashSet<>();
            Set<String> syncEdges = new LinkedHashSet<>();
            Set<String> deferredEdges = new LinkedHashSet<>();
            BeanDefinition mbd = safeGetMergedBeanDefinition(beanFactory, beanName);
            if (mbd != null) {
                collectEdges(mbd, edges, syncEdges);
                // By-type / @Autowired / ObjectProvider edges the declarations do not reveal.
                AutowiredEdgeResolver.collect(beanFactory, beanName, mbd, syncEdges, deferredEdges, deferProviderEdges);
            }
            edges.addAll(syncEdges);
            // Deferred edges remain genuine construction dependencies for cycle detection
            // and layering, but are excluded from the sync connectivity boundary below.
            edges.addAll(deferredEdges);
            // Keep only edges that point to known nodes; self-references are retained
            // so that cycle detection can flag them.
            edges.retainAll(nodes);
            syncEdges.retainAll(nodes);
            dependencies.put(beanName, edges);
            syncDependencies.put(beanName, syncEdges);
        }
        if (colocateFactoryMethodBeans) {
            addFactoryColocationEdges(beanFactory, nodes, syncDependencies);
        }
        return new BeanDependencyGraph(nodes, dependencies, syncDependencies);
    }

    /**
     * Add factory&rarr;bean <em>co-location</em> edges that bind every bean produced by a
     * {@code @Bean} factory method to its configuration class.
     *
     * <p>A configuration class is always created on the main thread &mdash; it is the
     * factory of its {@code @Bean} beans and is therefore force-instantiated there before
     * any of them is produced. Configuration classes are also the primary site of
     * <em>dynamic, by-type</em> bean access during context refresh: a CGLIB
     * self-invocation of another {@code @Bean} method, a captured
     * {@code ApplicationContext}/{@code BeanFactory} used to look a collaborator up by
     * type, or an {@code ObjectProvider}/{@code Lazy} resolved from a framework callback
     * the class implements. Spring Data's {@code SpringDataWebConfiguration} is a
     * canonical example: from {@code WebMvcConfigurer.addArgumentResolvers} (run on the
     * main thread) it resolves {@code sortResolver} (its own {@code @Bean}) and
     * {@code sortCustomizer} (a {@code @Bean} of <em>another</em> configuration) by type
     * through a captured context. None of these accesses appears at any injection point,
     * so no static analysis can see them.
     *
     * <p>Because such pulls happen on the main thread and target {@code @Bean} beans by
     * type, the only robust boundary is to keep every {@code @Bean} bean on its
     * configuration's thread. We therefore record a sync edge from each configuration to
     * each of its {@code @Bean} beans; the planner then never backgrounds a
     * factory-method bean while its configuration runs on the main thread. The remaining
     * background candidates &mdash; component-scanned beans and beans registered as plain
     * definitions &mdash; are reached only through the framework's ordinary singleton path,
     * which honours background initialization.
     *
     * <p>The co-location edges are added only when at least one configuration in the
     * context {@link DynamicConfigurationDetector#isDynamicConfiguration classifies as
     * <em>dynamic</em>} &mdash; one that actually performs (or could perform) invisible
     * by-type lookups. Such a lookup targets a {@code @Bean} bean <em>by type</em> and is
     * not restricted to the dynamic configuration's own beans: it may pull a {@code @Bean}
     * declared by a different, even <em>pure</em>, configuration (Spring Data's dynamic
     * {@code SpringDataWebConfiguration}, for example, pulls the {@code sortCustomizer}
     * {@code @Bean} of the pure {@code DataWebAutoConfiguration} from its
     * {@code WebMvcConfigurer.addArgumentResolvers} callback). Co-locating only the dynamic
     * configuration's own beans is therefore not safe; when any dynamic configuration is
     * present, <em>every</em> {@code @Bean} factory-method bean is co-located. The detector
     * errs towards {@code dynamic}, so an unrecognised configuration shape keeps the whole
     * context safely co-located.
     *
     * <p>When <em>no</em> configuration is dynamic, no such invisible lookup can occur, so
     * every {@code @Bean} bean (and the subtrees rooted at it) is left free to be
     * backgrounded and no co-location edge is added.
     *
     * <p>The edge is recorded only in the sync-connectivity view used by the planner, not
     * as a construction dependency, so it never introduces a spurious cycle or perturbs
     * the topological layering (the genuine dependency runs the opposite direction: the
     * bean depends on its factory).
     */
    private static void addFactoryColocationEdges(
            ConfigurableListableBeanFactory beanFactory, Set<String> nodes, Map<String, Set<String>> syncDependencies) {
        // Group every factory-method bean under its configuration (factory) bean, and
        // record whether any configuration in the context is dynamic.
        Map<String, Set<String>> beansByFactory = new LinkedHashMap<>();
        for (String beanName : nodes) {
            BeanDefinition mbd = safeGetMergedBeanDefinition(beanFactory, beanName);
            if (mbd == null) {
                continue;
            }
            String factoryBeanName = mbd.getFactoryBeanName();
            // Skip beans that are not factory-method beans (no factory bean), the factory
            // bean itself (self-reference), or beans whose factory is outside the graph.
            if (factoryBeanName == null || factoryBeanName.equals(beanName) || !nodes.contains(factoryBeanName)) {
                continue;
            }
            beansByFactory
                    .computeIfAbsent(factoryBeanName, key -> new LinkedHashSet<>())
                    .add(beanName);
        }

        boolean anyDynamicConfiguration = beansByFactory.keySet().stream()
                .anyMatch(factoryBeanName ->
                        DynamicConfigurationDetector.isDynamicConfiguration(beanFactory, factoryBeanName));
        if (!anyDynamicConfiguration) {
            // No configuration performs invisible by-type lookups, so no @Bean bean can be
            // pulled by type on the main thread by a configuration that static analysis
            // cannot see through. Every @Bean bean (and the subtree rooted at it) is then
            // free to be backgrounded: add no co-location edge.
            return;
        }

        // At least one dynamic configuration is present. A dynamic configuration can resolve
        // @Bean beans of OTHER configurations by type, on the main thread, through lookups no
        // static analysis can see (Spring Data's SpringDataWebConfiguration, for example,
        // dereferences an ObjectProvider/Lazy of SortHandlerMethodArgumentResolverCustomizer
        // from its WebMvcConfigurer.addArgumentResolvers callback, pulling the sortCustomizer
        // @Bean declared by the pure DataWebAutoConfiguration). Because such a lookup targets a
        // bean by type and is not restricted to the dynamic configuration's own @Bean beans,
        // co-locating only the dynamic configuration's beans is not enough: every @Bean
        // factory-method bean must stay on its configuration's (main) thread.
        for (Map.Entry<String, Set<String>> entry : beansByFactory.entrySet()) {
            syncDependencies
                    .computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>())
                    .addAll(entry.getValue());
        }
    }

    private static @Nullable BeanDefinition safeGetMergedBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getMergedBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void collectEdges(BeanDefinition mbd, Set<String> edges, Set<String> syncEdges) {
        String[] dependsOn = mbd.getDependsOn();
        if (dependsOn != null) {
            // depends-on targets are force-instantiated on the main thread: forced edges.
            Collections.addAll(edges, dependsOn);
        }
        String factoryBeanName = mbd.getFactoryBeanName();
        if (factoryBeanName != null) {
            // The factory bean is force-instantiated on the main thread: forced edge.
            edges.add(factoryBeanName);
        }
        for (ValueHolder holder :
                mbd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
            extractReferences(holder.getValue(), syncEdges);
        }
        for (ValueHolder holder : mbd.getConstructorArgumentValues().getGenericArgumentValues()) {
            extractReferences(holder.getValue(), syncEdges);
        }
        for (PropertyValue pv : mbd.getPropertyValues().getPropertyValueList()) {
            extractReferences(pv.getValue(), syncEdges);
        }
    }

    private static void extractReferences(@Nullable Object value, Set<String> edges) {
        if (value instanceof BeanReference beanReference) {
            edges.add(beanReference.getBeanName());
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                extractReferences(element, edges);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                extractReferences(entry.getKey(), edges);
                extractReferences(entry.getValue(), edges);
            }
        } else if (value instanceof Object[] array) {
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
     * The direct <em>sync</em> dependencies (outgoing edges resolved on the bean's own
     * thread, i.e. constructor/property references and by-type autowiring) of the
     * given bean. These are the edges that must not cross the background/mainline
     * boundary, as opposed to the {@code depends-on} / factory-bean edges that the
     * framework force-instantiates on the main thread.
     */
    Set<String> getSyncDependencies(String beanName) {
        return Collections.unmodifiableSet(this.syncDependencies.getOrDefault(beanName, Collections.emptySet()));
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
                    } else if (this.onStack.contains(next)) {
                        this.lowLink.put(frame.node, Math.min(low(frame.node), idx(next)));
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
                    } while (!w.equals(frame.node));
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

    private record Frame(String node, java.util.Iterator<String> neighbors) {}
}
