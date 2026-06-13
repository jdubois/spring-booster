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
        BeanDependencyGraph graph = BeanDependencyGraph.build(
                beanFactory,
                allNames,
                !this.settings.isBackgroundFactoryMethodBeans(),
                this.settings.isDeferProviderEdges());
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
        // from propagating mainline-ness to their pure consumers (see
        // collectCompletedLeafBarriers).
        Set<String> mainline = new LinkedHashSet<>(graph.getNodes());
        mainline.removeAll(eligible);
        Set<String> barriers = this.settings.isBackgroundSharedInfraConsumers()
                ? collectCompletedLeafBarriers(graph, eligible, cyclic, forcedMainline)
                : Collections.emptySet();
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
     * Identify the <em>completed-leaf barriers</em>: main-thread beans across which
     * mainline-ness is not propagated to their dependents (see {@link #propagateMainline}).
     *
     * <p>A bean {@code B} qualifies only when every condition holds:
     * <ol>
     * <li><b>Force-instantiated on the main thread &amp; acyclic.</b> {@code B} is in the
     * forced-mainline set (a factory bean, a {@code depends-on} target, or a configuration
     * class) and is not part of a dependency cycle, so the framework is guaranteed to
     * create it on the main thread.</li>
     * <li><b>Leaf with respect to background candidates.</b> {@code B} has no sync
     * dependency on any currently-eligible (backgroundable) bean, so its own construction
     * is self-contained and cannot pull a background bean mid-creation.</li>
     * </ol>
     *
     * <p>The set is computed from the <em>initial</em> eligible set (before propagation),
     * which is the conservative choice: an eligible sync dependency only ever disqualifies
     * a would-be barrier, never the reverse. Dependents reach the barrier through the
     * framework's ordinary, background-aware singleton path, and the barrier&rarr;dependent
     * edge keeps the barrier ordered strictly earlier in the layering, which together
     * establish that the dependent only reads an already-completed singleton.
     */
    private static Set<String> collectCompletedLeafBarriers(
            BeanDependencyGraph graph, Set<String> eligible, Set<String> cyclic, Set<String> forcedMainline) {
        Set<String> barriers = new LinkedHashSet<>();
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
