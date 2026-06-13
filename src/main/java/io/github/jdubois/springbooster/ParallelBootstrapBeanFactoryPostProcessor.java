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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryInitializer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.Assert;

/**
 * A {@link BeanFactoryPostProcessor} that enables parallel instantiation of
 * non-lazy singleton beans during application context bootstrap.
 *
 * <p>This post-processor does not parallelize the core pre-instantiation loop
 * directly. Instead it acts as a <em>planner and orchestrator</em> on top of the
 * existing background-initialization machinery of
 * {@link org.springframework.beans.factory.support.DefaultListableBeanFactory}:
 * <ol>
 * <li>it builds an approximate {@link BeanDependencyGraph} of the registered
 * singletons;</li>
 * <li>it selects a conservative set of safe candidates &mdash; excluding
 * infrastructure beans, {@link SmartInitializingSingleton} beans, beans in
 * dependency cycles, and beans that have opted out;</li>
 * <li>it marks each candidate for background initialization via
 * {@link AbstractBeanDefinition#setBackgroundInit(boolean)};</li>
 * <li>it installs a bounded {@link Executor} (sized by default at twice the
 * available processor count) as the factory's bootstrap
 * executor.</li>
 * </ol>
 *
 * <p>The actual concurrent creation, dependency ordering, and thread-safe singleton
 * exposure are delegated to the hardened bean factory primitives (the single
 * creation lock plus the lenient-creation fallback). The bounded executor is shut
 * down automatically once the context has refreshed, so it does not linger for the
 * lifetime of the context.
 *
 * <p>If the bean factory is not a recognized {@code DefaultListableBeanFactory}
 * subtype, or if planning fails for any reason, the post-processor logs the
 * condition and silently falls back to the standard sequential bootstrap.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapSettings
 * @see EnableParallelBootstrap
 * @see ParallelBootstrapApplicationContextInitializer
 */
public class ParallelBootstrapBeanFactoryPostProcessor
        implements BeanFactoryPostProcessor, BeanFactoryInitializer<ConfigurableListableBeanFactory>, PriorityOrdered {

    private static final Log logger = LogFactory.getLog(ParallelBootstrapBeanFactoryPostProcessor.class);

    /**
     * Bean definition attribute set by Spring's {@code ConfigurationClassPostProcessor}
     * to mark a configuration class. The value is {@link #CONFIGURATION_CLASS_FULL} or
     * {@link #CONFIGURATION_CLASS_LITE}. Referenced by its stable string value because
     * the declaring {@code ConfigurationClassUtils} type is not public API.
     */
    private static final String CONFIGURATION_CLASS_ATTRIBUTE =
            "org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass";

    private static final String CONFIGURATION_CLASS_FULL = "full";

    private static final String CONFIGURATION_CLASS_LITE = "lite";

    private final ParallelBootstrapSettings settings;

    /**
     * Create a post-processor with default settings.
     */
    public ParallelBootstrapBeanFactoryPostProcessor() {
        this(ParallelBootstrapSettings.withDefaults());
    }

    /**
     * Create a post-processor with the given settings.
     * @param settings the parallel bootstrap settings (must not be {@code null})
     */
    public ParallelBootstrapBeanFactoryPostProcessor(ParallelBootstrapSettings settings) {
        Assert.notNull(settings, "'settings' must not be null");
        this.settings = settings;
    }

    @Override
    public int getOrder() {
        // Run last among BeanFactoryPostProcessors so that all bean definitions
        // (including those contributed by other post-processors) are visible.
        return PriorityOrdered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        apply(beanFactory);
    }

    @Override
    public void initialize(ConfigurableListableBeanFactory beanFactory) {
        apply(beanFactory);
    }

    private void apply(ConfigurableListableBeanFactory beanFactory) {
        if (!this.settings.isEnabled()) {
            logger.debug("Parallel bootstrap disabled; using sequential singleton instantiation");
            return;
        }
        if (beanFactory.getBootstrapExecutor() != null) {
            logger.info("A bootstrap executor is already configured; skipping parallel bootstrap planning");
            return;
        }
        try {
            List<String> candidates = planCandidates(beanFactory);
            if (candidates.isEmpty()) {
                logger.debug("No eligible beans for parallel bootstrap; using sequential instantiation");
                return;
            }
            for (String beanName : candidates) {
                markForBackgroundInit(beanFactory, beanName);
            }
            ThreadPoolExecutor executor = createBoundedExecutor();
            beanFactory.setBootstrapExecutor(executor);
            registerShutdownHook(beanFactory, executor);
            if (logger.isInfoEnabled()) {
                logger.info("Parallel bootstrap enabled for " + candidates.size() + " bean(s) using a pool of "
                        + this.settings.getPoolSize() + " thread(s)");
            }
        } catch (RuntimeException ex) {
            // Kill-switch / graceful fallback: never let planning break the context.
            logger.warn("Parallel bootstrap planning failed; falling back to sequential instantiation", ex);
        }
    }

    /**
     * Determine the ordered list of bean names that are safe to instantiate in the
     * background. Exposed with package visibility for testing.
     *
     * <p>Selection is <em>connectivity-safe</em>: a bean is only retained if no
     * <em>sync</em> dependency edge (constructor/property reference or by-type /
     * {@code @Autowired} / {@code ObjectProvider} autowiring) connects it &mdash; in
     * either direction &mdash; to a bean that is instantiated on the main thread.
     * This prevents the {@code BeanCurrentlyInCreationException} that occurs when a
     * main-thread bean pulls a background bean by type (and the symmetric case of a
     * background bean pulling a main-thread bean).
     */
    List<String> planCandidates(ConfigurableListableBeanFactory beanFactory) {
        List<String> allNames = List.of(beanFactory.getBeanDefinitionNames());
        List<String> singletons = new ArrayList<>();
        for (String beanName : allNames) {
            BeanDefinition bd = safeGetBeanDefinition(beanFactory, beanName);
            if (bd != null && !bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
                singletons.add(beanName);
            }
        }

        // Build the graph over every registered bean definition so that all
        // dependency relationships (including by-type autowiring) are visible. Unless the
        // caller has opted in to backgrounding factory-method beans, co-locate every
        // @Bean bean with its configuration class so the bootstrap stays safe against the
        // dynamic, by-type lookups that configuration classes perform on the main thread.
        //
        // The shared-infrastructure relaxation always keeps co-location active: it closes
        // the invisible by-type lookup channel for every @Bean bean, and then selectively
        // frees only the genuine completed-leaf-barrier consumers (see below). Disabling
        // co-location globally (backgroundFactoryMethodBeans) would background every @Bean
        // bean, reintroducing that channel for unrelated infrastructure beans such as
        // Spring Security's authenticationEventPublisher (resolved by type on the main
        // thread from AuthenticationManagerBuilder), which fails with a
        // BeanCurrentlyInCreationException. Naming completed-leaf barriers
        // (barrierBeanNames) activates the same relaxation for the named beans, so it too
        // requires co-location to stay active.
        boolean relaxSharedInfra =
                this.settings.isBackgroundSharedInfraConsumers() || !this.settings.getBarrierBeanNames().isEmpty();
        boolean colocateFactoryMethodBeans =
                !this.settings.isBackgroundFactoryMethodBeans() || relaxSharedInfra;
        BeanDependencyGraph graph = BeanDependencyGraph.build(
                beanFactory, allNames, colocateFactoryMethodBeans, this.settings.isDeferProviderEdges());

        // Drop the configuration -> @Bean co-location edge for any bean the user has
        // explicitly allow-listed for background initialization (by name via
        // backgroundBeanNames, or per-definition via FORCE_BACKGROUND_ATTRIBUTE). This is the
        // targeted, per-bean counterpart of backgroundFactoryMethodBeans: only the named beans
        // escape co-location, while every other @Bean bean stays on the main thread so the
        // invisible by-type lookup channel stays closed for the rest of the context. The bean
        // still has to clear every other safety gate below (cycle, forced-mainline, opt-out,
        // infrastructure type, candidate filter) and the connectivity-safe propagation, so a
        // genuinely entangled bean is pulled back to the main thread rather than misbehaving.
        applyBackgroundAllowlist(beanFactory, graph);

        // Apply the user-declared co-background groups: drop each member's co-location edge
        // (like the allowlist) and additionally drop any sync co-location edge between two
        // members of the same group, so asserted-independent heavyweights overlap rather than
        // serialize. Forced depends-on/factory edges and the safety gates below still apply.
        applyCoBackgroundGroups(beanFactory, graph);

        Set<String> cyclic = graph.beansInCycles();
        Set<String> forcedMainline = collectForcedMainlineBeans(beanFactory);

        // Beans that are structurally eligible for background initialization.
        Set<String> eligible = new LinkedHashSet<>();
        for (String beanName : singletons) {
            if (isSafeCandidate(beanFactory, beanName, cyclic, forcedMainline)) {
                eligible.add(beanName);
            }
        }

        // Every node that is not eligible runs on the main thread; propagate that
        // constraint across sync edges so no sync edge crosses the boundary. When the
        // shared-infrastructure relaxation is enabled, completed-leaf barriers are exempted
        // from propagating mainline-ness to their pure consumers, and those consumers are
        // freed from co-location so independent consumers of one completed leaf can overlap.
        Set<String> barriers = Collections.emptySet();
        if (relaxSharedInfra) {
            barriers = applySharedInfraRelaxation(
                    beanFactory,
                    graph,
                    eligible,
                    cyclic,
                    forcedMainline,
                    this.settings.isBackgroundSharedInfraConsumers(),
                    this.settings.getBarrierBeanNames());
        }

        Set<String> mainline = new LinkedHashSet<>(graph.getNodes());
        mainline.removeAll(eligible);
        propagateMainline(graph, eligible, mainline, barriers);

        List<String> candidates = new ArrayList<>();
        for (String beanName : singletons) {
            if (eligible.contains(beanName)) {
                candidates.add(beanName);
            }
        }
        return candidates;
    }

    /**
     * Iteratively reclassify as main-thread any eligible bean that is joined by a sync
     * edge (in either direction) to a bean already known to run on the main thread,
     * until a fixpoint is reached.
     *
     * <p>The {@code barriers} set carves out the single safe exception: a
     * <em>completed-leaf barrier</em> does not propagate its mainline-ness to the beans
     * that <em>depend on it</em> (the barrier&rarr;dependent direction), because the
     * barrier is a terminal infrastructure singleton the framework finishes on the main
     * thread before those dependents are constructed, and they only read it. Because a
     * dependent {@code D} keeps the barrier {@code B} as a construction dependency
     * ({@code B} is an outgoing edge of {@code D} in the full dependency graph), {@code B}
     * is ordered strictly before {@code D} in the topological layering, so "completed
     * before fan-out" holds structurally. The opposite direction &mdash; a main-thread
     * bean that <em>depends on</em> a candidate &mdash; is never exempted, as that is the
     * genuine in-flight by-type pull.
     */
    private static void propagateMainline(
            BeanDependencyGraph graph, Set<String> eligible, Set<String> mainline, Set<String> barriers) {
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String node : graph.getNodes()) {
            for (String dependency : graph.getSyncDependencies(node)) {
                dependents
                        .computeIfAbsent(dependency, key -> new LinkedHashSet<>())
                        .add(node);
            }
        }
        Deque<String> worklist = new ArrayDeque<>(mainline);
        while (!worklist.isEmpty()) {
            String current = worklist.poll();
            // Direction 1: current depends on neighbor. A main-thread bean pulls its
            // dependency on its own thread, so the dependency must also run on the main
            // thread. This direction is never exempted.
            for (String neighbor : graph.getSyncDependencies(current)) {
                if (eligible.remove(neighbor)) {
                    mainline.add(neighbor);
                    worklist.add(neighbor);
                }
            }
            // Direction 2: neighbor depends on current. Normally the neighbor is pulled
            // onto the main thread too, but if current is a completed-leaf barrier the
            // neighbor merely reads an already-finished singleton and may stay backgrounded.
            boolean currentIsBarrier = barriers.contains(current);
            for (String neighbor : dependents.getOrDefault(current, Collections.emptySet())) {
                if (currentIsBarrier) {
                    continue;
                }
                if (eligible.remove(neighbor)) {
                    mainline.add(neighbor);
                    worklist.add(neighbor);
                }
            }
        }
    }

    /**
     * Drop the configuration&rarr;{@code @Bean} <em>co-location</em> edge for every bean the
     * user has explicitly allow-listed for background initialization &mdash; either by name
     * through {@link ParallelBootstrapSettings#getBackgroundBeanNames()} or per-definition
     * through {@link ParallelBootstrapSettings#FORCE_BACKGROUND_ATTRIBUTE}.
     *
     * <p>This is the targeted, per-bean counterpart of the context-wide
     * {@code backgroundFactoryMethodBeans} flag: it removes <em>only</em> the co-location edge
     * for the named beans, leaving every other {@code @Bean} bean co-located on the main
     * thread so the invisible by-type lookup channel stays closed for the rest of the context.
     * The relaxation only ever <em>removes</em> a sync edge; the bean must still clear every
     * structural safety gate ({@link #isSafeCandidate}) and the connectivity-safe
     * {@link #propagateMainline} pass, so a bean that is a forced-mainline
     * {@code depends-on}/factory target, in a cycle, opted out, or joined by a genuine visible
     * sync edge to a main-thread bean is kept on the main thread. An invisible eager by-type
     * pull still fails fast with {@code BeanCurrentlyInCreationException} and falls back to
     * sequential bootstrap (design goal #1).
     */
    private void applyBackgroundAllowlist(ConfigurableListableBeanFactory beanFactory, BeanDependencyGraph graph) {
        Set<String> allowlist = this.settings.getBackgroundBeanNames();
        for (String beanName : graph.getNodes()) {
            boolean allowlisted = allowlist.contains(beanName)
                    || ParallelBootstrapSettings.isForcedBackground(safeGetBeanDefinition(beanFactory, beanName));
            if (allowlisted) {
                dropColocationEdge(beanFactory, graph, beanName);
            }
        }
    }

    /**
     * Apply the user-declared co-background groups (Solution 3): for every member of every
     * group drop its configuration&rarr;{@code @Bean} co-location edge (exactly like the
     * {@link #applyBackgroundAllowlist allowlist}), and additionally drop any sync co-location
     * edge <em>between two members of the same group</em>.
     *
     * <p>A co-background group is a user assertion that its members are mutually independent
     * heavyweight {@code @Bean} beans that may be constructed concurrently (the canonical
     * cases are {@code {entityManagerFactory, springSecurityFilterChain}} and
     * {@code {flyway, liquibase}}). Dropping the inter-member sync edges stops the
     * connectivity-safe {@link #propagateMainline} pass from forcing an asserted-independent
     * pair onto a single thread through a by-type edge the user has declared safe to ignore.
     *
     * <p>The relaxation only ever <em>removes</em> edges from the sync-connectivity view; the
     * genuine construction dependency (if any) remains in the full dependency graph, so
     * topological ordering and cycle detection are unchanged, and a forced
     * {@code depends-on}/factory edge is never overridden (it is not a co-location edge). Every
     * member must still clear {@link #isSafeCandidate} and survive {@link #propagateMainline},
     * so a member pinned by a genuine visible sync edge to the main thread stays there, and an
     * invisible eager by-type pull still fails fast with {@code BeanCurrentlyInCreationException}
     * (design goal #1).
     */
    private void applyCoBackgroundGroups(ConfigurableListableBeanFactory beanFactory, BeanDependencyGraph graph) {
        for (Set<String> group : this.settings.getCoBackgroundGroups()) {
            for (String member : group) {
                if (graph.getNodes().contains(member)) {
                    dropColocationEdge(beanFactory, graph, member);
                }
            }
            // Drop co-location sync edges between mutually-independent members (both
            // directions), so the group does not collapse onto a single thread.
            for (String from : group) {
                for (String to : group) {
                    if (!from.equals(to)) {
                        graph.removeSyncEdge(from, to);
                    }
                }
            }
        }
    }

    /**
     * Drop the configuration&rarr;{@code @Bean} <em>co-location</em> sync edge for the given
     * factory-method bean, if it has one whose factory is part of the graph. Shared by the
     * per-bean allowlist ({@link #applyBackgroundAllowlist}) and the co-background groups
     * ({@link #applyCoBackgroundGroups}). A non-factory-method bean (no factory bean) is left
     * untouched.
     */
    private static void dropColocationEdge(
            ConfigurableListableBeanFactory beanFactory, BeanDependencyGraph graph, String beanName) {
        BeanDefinition mbd = safeGetMergedBeanDefinition(beanFactory, beanName);
        String factoryBeanName = (mbd != null) ? mbd.getFactoryBeanName() : null;
        if (factoryBeanName != null && graph.getNodes().contains(factoryBeanName)) {
            graph.removeSyncEdge(factoryBeanName, beanName);
        }
    }

    /**
     * Apply the completed-leaf-barrier relaxation (opt-in
     * {@code backgroundSharedInfraConsumers}) on a <em>co-located</em> graph, and return
     * the set of completed-leaf barriers whose mainline-ness must not propagate to their
     * pure consumers (used by {@link #propagateMainline}).
     *
     * <p>This runs while every {@code @Bean} factory-method bean is still co-located with
     * its configuration class, so the invisible by-type lookup channel stays closed for
     * the whole context (in particular for unrelated infrastructure beans such as Spring
     * Security's {@code authenticationEventPublisher}, which is resolved by type on the
     * main thread from {@code AuthenticationManagerBuilder} and must therefore stay on the
     * main thread). The method then carves out exactly one safe widening:
     * <ol>
     * <li><b>Identify completed-leaf barriers.</b> A bean that is force-instantiated on the
     * main thread (a factory bean, {@code depends-on} target, or configuration class),
     * acyclic, and whose sync dependencies are all non-eligible (it pulls no backgroundable
     * bean during its own construction) is a barrier: the framework finishes it before
     * fan-out and consumers only read it. The leaf check uses the <em>initial</em> eligible
     * set, the conservative choice. Such structural barriers are auto-detected only when
     * {@code autoDetect} ({@code backgroundSharedInfraConsumers}) is set.</li>
     * <li><b>Honor user-named barriers (Solution 2).</b> A bean named in
     * {@code barrierNames} is treated as a barrier even when it is not in the forced-mainline
     * set &mdash; the canonical case is a shared {@code DataSource} exposed only as a
     * co-located {@code @Bean}, which the structural predicate above rejects. A named bean is
     * honored only when it is acyclic and a genuine leaf (no sync dependency on a
     * still-eligible bean, treating the whole named-barrier cohort as mainline); honored names
     * are pinned to the main thread (removed from {@code eligible}) so "completed before
     * fan-out" holds. Named barriers are honored regardless of {@code autoDetect}.</li>
     * <li><b>Free the pure barrier consumers from co-location.</b> A co-located
     * factory-method {@code @Bean} bean whose <em>every</em> sync dependency is a barrier
     * (and which depends on at least one) reads only already-completed leaves, so its
     * co-location edge is dropped. Independent such consumers (the classic Flyway +
     * Liquibase over one shared {@code DataSource} barrier) then land in the same
     * construction layer and overlap on background threads. A bean with any non-barrier
     * dependency &mdash; or no barrier dependency at all &mdash; keeps its co-location edge
     * and stays on the main thread.</li>
     * </ol>
     *
     * <p>The relaxation only ever <em>removes</em> co-location edges for verified pure
     * consumers and exempts the barrier&rarr;consumer propagation direction; the final
     * {@link #propagateMainline} still pulls back any freed bean that is connected to the
     * main thread by a visible edge, and the {@code BeanCurrentlyInCreationException}
     * fast-fail remains the backstop for an invisible eager lookup (design goal #1).
     */
    private static Set<String> applySharedInfraRelaxation(
            ConfigurableListableBeanFactory beanFactory,
            BeanDependencyGraph graph,
            Set<String> eligible,
            Set<String> cyclic,
            Set<String> forcedMainline,
            boolean autoDetect,
            Set<String> barrierNames) {
        // 1. Honor user-named completed-leaf barriers (Solution 2): pin them to the main
        // thread so consumers may overlap. Each named bean must be acyclic and a genuine leaf
        // (no sync dependency on a still-eligible bean); a dependency that is itself a named
        // barrier does not disqualify it, since the whole named cohort moves to the main
        // thread together.
        Set<String> namedBarriers = new LinkedHashSet<>();
        if (!barrierNames.isEmpty()) {
            Set<String> candidateNamed = new LinkedHashSet<>();
            for (String name : barrierNames) {
                if (graph.getNodes().contains(name) && !cyclic.contains(name)) {
                    candidateNamed.add(name);
                }
            }
            for (String name : candidateNamed) {
                boolean leaf = true;
                for (String dependency : graph.getSyncDependencies(name)) {
                    if (eligible.contains(dependency) && !candidateNamed.contains(dependency)) {
                        leaf = false;
                        break;
                    }
                }
                if (leaf) {
                    namedBarriers.add(name);
                }
            }
            eligible.removeAll(namedBarriers);
        }

        // 2. Auto-detected structural barriers: force-instantiated, acyclic, main-thread beans
        // that pull no still-eligible bean during their own construction (computed against the
        // eligible set, which is the conservative choice). Only when backgroundSharedInfraConsumers
        // is enabled; user-named barriers are always included.
        Set<String> barriers = new LinkedHashSet<>(namedBarriers);
        if (autoDetect) {
            for (String node : graph.getNodes()) {
                if (!forcedMainline.contains(node) || cyclic.contains(node)) {
                    continue;
                }
                boolean leaf = true;
                for (String dependency : graph.getSyncDependencies(node)) {
                    if (eligible.contains(dependency)) {
                        leaf = false;
                        break;
                    }
                }
                if (leaf) {
                    barriers.add(node);
                }
            }
        }

        // 3. Free the pure barrier consumers from co-location so independent consumers of a
        // completed leaf can overlap. A consumer qualifies only when every one of its sync
        // dependencies is a barrier (it reads only already-completed leaves) and it is not a
        // barrier itself (freeing a barrier would break the "completed before fan-out"
        // guarantee its own consumers rely on).
        for (String node : new LinkedHashSet<>(graph.getNodes())) {
            if (barriers.contains(node) || !eligible.contains(node)) {
                continue;
            }
            BeanDefinition mbd = safeGetMergedBeanDefinition(beanFactory, node);
            String factoryBeanName = (mbd != null) ? mbd.getFactoryBeanName() : null;
            if (factoryBeanName == null || !graph.getNodes().contains(factoryBeanName)) {
                continue;
            }
            Set<String> syncDeps = graph.getSyncDependencies(node);
            if (syncDeps.isEmpty()) {
                continue;
            }
            boolean pureBarrierConsumer = true;
            for (String dependency : syncDeps) {
                if (!barriers.contains(dependency)) {
                    pureBarrierConsumer = false;
                    break;
                }
            }
            if (pureBarrierConsumer) {
                // Drop the configuration -> @Bean co-location edge so this consumer can
                // background; its genuine dependency on the (main-thread) barrier remains in
                // the full graph, keeping the barrier ordered strictly earlier.
                graph.removeSyncEdge(factoryBeanName, node);
            }
        }
        return barriers;
    }

    /**
     * Collect the names of beans that must always be instantiated on the main thread,
     * never in the background:
     * <ul>
     * <li>the factory bean of any bean (for example a {@code @Configuration} class
     * hosting {@code @Bean} methods) and the target of any {@code depends-on}
     * declaration, both of which the framework force-instantiates on the main thread
     * before backgrounding a dependent; and</li>
     * <li>every {@code @Configuration} / configuration-class bean itself (full or
     * lite), because configuration classes are routinely retrieved dynamically by type
     * or by annotation on the main thread &mdash; for example through
     * {@code getBeansWithAnnotation(...)} &mdash; in ways no static analysis can see. A
     * configuration class that registers no eligible {@code @Bean} singleton would
     * otherwise not be reached by the factory&rarr;bean co-location rule and could be
     * backgrounded, triggering a {@code BeanCurrentlyInCreationException} when it is
     * pulled on the main thread (observed with Spring Security's
     * {@code EnableWebSecurityConfiguration}, which is fetched via
     * {@code getBeansWithAnnotation(EnableWebSecurity.class)}).</li>
     * </ul>
     */
    private static Set<String> collectForcedMainlineBeans(ConfigurableListableBeanFactory beanFactory) {
        Set<String> forced = new HashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = safeGetMergedBeanDefinition(beanFactory, beanName);
            if (bd == null) {
                continue;
            }
            if (bd.getFactoryBeanName() != null) {
                forced.add(bd.getFactoryBeanName());
            }
            String[] dependsOn = bd.getDependsOn();
            if (dependsOn != null) {
                Collections.addAll(forced, dependsOn);
            }
            // The configuration-class marker is set by ConfigurationClassPostProcessor on
            // the originally registered definition, which getMergedBeanDefinition does not
            // necessarily carry over, so consult the raw definition for it.
            if (isConfigurationClassBean(safeGetBeanDefinition(beanFactory, beanName))) {
                forced.add(beanName);
            }
        }
        return forced;
    }

    /**
     * Whether the given bean definition denotes a configuration class (a
     * {@code @Configuration} class, full or lite) as marked by Spring's
     * {@code ConfigurationClassPostProcessor}. Such beans are kept on the main thread
     * because they are frequently resolved dynamically &mdash; by type or by annotation
     * &mdash; during context refresh.
     */
    private static boolean isConfigurationClassBean(@Nullable BeanDefinition bd) {
        if (bd == null) {
            return false;
        }
        Object attribute = bd.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE);
        return CONFIGURATION_CLASS_FULL.equals(attribute) || CONFIGURATION_CLASS_LITE.equals(attribute);
    }

    private boolean isSafeCandidate(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            Set<String> cyclic,
            Set<String> forcedMainline) {

        if (cyclic.contains(beanName)) {
            // Beans in a cycle must be created on a single thread to preserve the
            // early-singleton-reference handshake.
            return false;
        }
        if (forcedMainline.contains(beanName)) {
            // A shared factory bean or depends-on target must be created synchronously
            // on the main thread.
            return false;
        }
        BeanDefinition bd = safeGetBeanDefinition(beanFactory, beanName);
        if (bd == null || ParallelBootstrapSettings.isOptedOut(bd)) {
            return false;
        }
        if (!(bd instanceof AbstractBeanDefinition)) {
            // Cannot mark a non-AbstractBeanDefinition for background init.
            return false;
        }
        Class<?> type = safeGetType(beanFactory, beanName);
        if (type != null && isInfrastructureType(type)) {
            return false;
        }
        return this.settings.getCandidateFilter().test(beanName);
    }

    private static boolean isInfrastructureType(Class<?> type) {
        return (BeanPostProcessor.class.isAssignableFrom(type)
                || BeanFactoryPostProcessor.class.isAssignableFrom(type)
                || BeanFactoryInitializer.class.isAssignableFrom(type)
                || SmartInitializingSingleton.class.isAssignableFrom(type));
    }

    private void markForBackgroundInit(ConfigurableListableBeanFactory beanFactory, String beanName) {
        BeanDefinition original = safeGetBeanDefinition(beanFactory, beanName);
        if (original instanceof AbstractBeanDefinition abd) {
            abd.setBackgroundInit(true);
        }
        // Also update the cached merged definition, which is what the pre-instantiation
        // phase actually reads, in case it has already been merged and cached.
        BeanDefinition merged = safeGetMergedBeanDefinition(beanFactory, beanName);
        if (merged instanceof AbstractBeanDefinition mergedAbd) {
            mergedAbd.setBackgroundInit(true);
        }
    }

    private ThreadPoolExecutor createBoundedExecutor() {
        int poolSize = this.settings.getPoolSize();
        ThreadFactory threadFactory = new BootstrapThreadFactory(this.settings.getThreadNamePrefix());
        return new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
    }

    private void registerShutdownHook(ConfigurableListableBeanFactory beanFactory, ThreadPoolExecutor executor) {
        // Shut the pool down right after the context has refreshed so it does not
        // linger for the lifetime of the context. The listener is registered as a
        // manual singleton so that it is detected by the context's event multicaster.
        String listenerName = getClass().getName() + ".shutdownListener";
        ApplicationListener<ContextRefreshedEvent> listener = event -> {
            beanFactory.setBootstrapExecutor(null);
            executor.shutdown();
        };
        beanFactory.registerSingleton(listenerName, listener);
    }

    private static @Nullable BeanDefinition safeGetBeanDefinition(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
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

    private static @Nullable Class<?> safeGetType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Thread factory producing named, daemon bootstrap threads.
     */
    private static final class BootstrapThreadFactory implements ThreadFactory {

        private final String namePrefix;

        private final AtomicInteger threadNumber = new AtomicInteger(1);

        BootstrapThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, this.namePrefix + this.threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
