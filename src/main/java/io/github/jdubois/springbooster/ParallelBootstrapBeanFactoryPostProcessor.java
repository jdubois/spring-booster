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
import org.springframework.aot.AotDetector;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryInitializer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
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
 * <p><strong>Ahead-of-time (AOT) / native image.</strong> This post-processor is also a
 * {@link BeanFactoryInitializationAotProcessor}: during Spring AOT processing it computes
 * the background-init plan once at build time and emits it as generated code (see
 * {@link ParallelBootstrapAotContribution}). When the application later runs with the
 * generated artifacts ({@link AotDetector#useGeneratedArtifacts()}), the runtime planning
 * is skipped entirely &mdash; the generated initializer marks the precomputed candidates and
 * installs the bootstrap executor through {@link #applyAotPlan} &mdash; so the reflective
 * graph analysis never runs in a native image and runtime planning cost approaches zero.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapSettings
 * @see EnableParallelBootstrap
 * @see ParallelBootstrapApplicationContextInitializer
 */
public class ParallelBootstrapBeanFactoryPostProcessor
        implements BeanFactoryPostProcessor,
                BeanFactoryInitializer<ConfigurableListableBeanFactory>,
                BeanFactoryInitializationAotProcessor,
                PriorityOrdered {

    private static final Log logger = LogFactory.getLog(ParallelBootstrapBeanFactoryPostProcessor.class);

    private final ParallelBootstrapSettings settings;

    /**
     * The bootstrap executor this post-processor installed, if any. Tracked so that AOT
     * processing (which runs the post-processor's {@code apply} during a build-time refresh
     * before the AOT contribution is collected) can tell its own executor apart from one a
     * user supplied, and not mistake it for a reason to skip emitting the plan.
     */
    private @Nullable Executor installedExecutor;

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
        // Startup profiling is independent of the parallel-bootstrap kill-switch so that a
        // sequential baseline can be measured for comparison.
        if (this.settings.isProfileStartup()) {
            installProfiler(beanFactory);
        }
        if (!this.settings.isEnabled()) {
            logger.debug("Parallel bootstrap disabled; using sequential singleton instantiation");
            return;
        }
        if (AotDetector.useGeneratedArtifacts()) {
            // Running with AOT-generated artifacts: the precomputed plan is applied by the
            // generated context initializer (see ParallelBootstrapAotContribution), so the
            // reflective runtime planning is skipped entirely.
            logger.debug("AOT-generated artifacts in use; parallel bootstrap plan applied ahead of time");
            return;
        }
        if (beanFactory.getBootstrapExecutor() != null) {
            logger.info("A bootstrap executor is already configured; skipping parallel bootstrap planning");
            return;
        }
        try {
            BeanDependencyGraph graph = buildGraph(beanFactory);
            List<String> candidates = planCandidates(beanFactory, graph);
            if (candidates.size() < this.settings.getMinimumBackgroundCandidates()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Only " + candidates.size() + " eligible bean(s) for parallel bootstrap (minimum "
                            + this.settings.getMinimumBackgroundCandidates()
                            + "); using sequential instantiation");
                }
                return;
            }
            for (String beanName : candidates) {
                markForBackgroundInit(beanFactory, beanName);
            }
            int poolSize = effectivePoolSize(graph, candidates);
            ThreadPoolExecutor executor = createBoundedExecutor(poolSize, this.settings.getThreadNamePrefix());
            beanFactory.setBootstrapExecutor(executor);
            this.installedExecutor = executor;
            registerShutdownHook(beanFactory, executor);
            if (logger.isInfoEnabled()) {
                logger.info("Parallel bootstrap enabled for " + candidates.size() + " bean(s) using a pool of "
                        + poolSize + " thread(s)");
            }
        } catch (RuntimeException ex) {
            // Kill-switch / graceful fallback: never let planning break the context.
            logger.warn("Parallel bootstrap planning failed; falling back to sequential instantiation", ex);
        }
    }

    /**
     * Spring AOT entry point. Computes the background-init plan once at build time and, if
     * any candidates qualify, returns a {@link ParallelBootstrapAotContribution} that emits
     * the plan as generated code applied at runtime through {@link #applyAotPlan}. Returns
     * {@code null} (no contribution) when the feature is disabled, a foreign bootstrap
     * executor is configured, or fewer candidates than
     * {@link ParallelBootstrapSettings#getMinimumBackgroundCandidates()} qualify &mdash; in
     * which case the AOT-optimized application bootstraps sequentially, exactly as the
     * dynamic path would.
     */
    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        if (!this.settings.isEnabled()) {
            return null;
        }
        Executor existing = beanFactory.getBootstrapExecutor();
        if (existing != null && existing != this.installedExecutor) {
            // A user-supplied bootstrap executor takes precedence; do not emit a plan.
            return null;
        }
        try {
            BeanDependencyGraph graph = buildGraph(beanFactory);
            List<String> candidates = planCandidates(beanFactory, graph);
            if (candidates.size() < this.settings.getMinimumBackgroundCandidates()) {
                return null;
            }
            int poolSize = effectivePoolSize(graph, candidates);
            return new ParallelBootstrapAotContribution(candidates, poolSize, this.settings.getThreadNamePrefix());
        } catch (RuntimeException ex) {
            logger.warn("Parallel bootstrap AOT planning failed; the application will bootstrap sequentially", ex);
            return null;
        }
    }

    /**
     * Apply an ahead-of-time computed parallel bootstrap plan: mark each named bean for
     * background initialization and install a bounded bootstrap executor of the given size.
     * Invoked by the code generated during AOT processing (see
     * {@link ParallelBootstrapAotContribution}); it is a no-op if a bootstrap executor is
     * already configured, so a user-supplied executor is never overridden.
     * @param beanFactory the bean factory to configure (must not be {@code null})
     * @param backgroundBeanNames the names of the beans to initialize in the background
     * @param poolSize the size of the bounded bootstrap pool to install
     * @param threadNamePrefix the thread name prefix for bootstrap threads
     */
    public static void applyAotPlan(
            ConfigurableListableBeanFactory beanFactory,
            List<String> backgroundBeanNames,
            int poolSize,
            String threadNamePrefix) {
        Assert.notNull(beanFactory, "'beanFactory' must not be null");
        if (beanFactory.getBootstrapExecutor() != null) {
            logger.info("A bootstrap executor is already configured; skipping precomputed parallel bootstrap plan");
            return;
        }
        for (String beanName : backgroundBeanNames) {
            markForBackgroundInit(beanFactory, beanName);
        }
        ThreadPoolExecutor executor = createBoundedExecutor(poolSize, threadNamePrefix);
        beanFactory.setBootstrapExecutor(executor);
        registerShutdownHook(beanFactory, executor);
        if (logger.isInfoEnabled()) {
            logger.info("Parallel bootstrap (ahead-of-time) enabled for " + backgroundBeanNames.size()
                    + " bean(s) using a pool of " + poolSize + " thread(s)");
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
        return planCandidates(beanFactory, buildGraph(beanFactory));
    }

    /**
     * Build the dependency graph over every registered bean definition so that all
     * dependency relationships (including by-type autowiring) are visible. Unless the
     * caller has opted in to backgrounding factory-method beans, co-locate every
     * {@code @Bean} bean with its configuration class so the bootstrap stays safe against
     * the dynamic, by-type lookups that configuration classes perform on the main thread.
     */
    private BeanDependencyGraph buildGraph(ConfigurableListableBeanFactory beanFactory) {
        List<String> allNames = List.of(beanFactory.getBeanDefinitionNames());
        return BeanDependencyGraph.build(beanFactory, allNames, shouldColocateFactoryMethodBeans(beanFactory));
    }

    /**
     * Decide whether {@code @Bean} factory-method beans must be co-located with their
     * configuration class. They are, by default, unless the user has opted into
     * backgrounding them unconditionally
     * ({@link ParallelBootstrapSettings#isBackgroundFactoryMethodBeans()}), or opted into
     * {@link ParallelBootstrapSettings#isEvidenceBasedColocation() evidence-based
     * co-location} and static analysis proves every configuration class free of invisible
     * by-type lookup channels.
     */
    private boolean shouldColocateFactoryMethodBeans(ConfigurableListableBeanFactory beanFactory) {
        if (this.settings.isBackgroundFactoryMethodBeans()) {
            return false;
        }
        if (this.settings.isEvidenceBasedColocation()
                && ConfigurationClassColocationAnalyzer.canSkipColocation(beanFactory)) {
            logger.debug("Evidence-based analysis proved all configuration classes safe; "
                    + "releasing @Bean beans from co-location");
            return false;
        }
        return true;
    }

    /**
     * Determine the ordered list of background candidates against an already-built
     * {@link BeanDependencyGraph}, so the caller can reuse the same graph (for example to
     * size the pool from its concurrency width) without rebuilding it.
     */
    List<String> planCandidates(ConfigurableListableBeanFactory beanFactory, BeanDependencyGraph graph) {
        List<String> singletons = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = safeGetBeanDefinition(beanFactory, beanName);
            if (bd != null && !bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
                singletons.add(beanName);
            }
        }

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
        // constraint across sync edges so no sync edge crosses the boundary.
        Set<String> mainline = new LinkedHashSet<>(graph.getNodes());
        mainline.removeAll(eligible);
        propagateMainline(graph, eligible, mainline);

        List<String> candidates = new ArrayList<>();
        for (String beanName : singletons) {
            if (eligible.contains(beanName)) {
                candidates.add(beanName);
            }
        }
        return candidates;
    }

    /**
     * Resolve the pool size to install for the given candidate set. With adaptive sizing
     * enabled, this is the candidates' achievable concurrency width (the widest topological
     * layer of their induced subgraph), floored at {@code 2} and capped at the configured
     * {@link ParallelBootstrapSettings#getPoolSize() pool size}; otherwise it is the
     * configured pool size unchanged.
     */
    int effectivePoolSize(BeanDependencyGraph graph, List<String> candidates) {
        int configured = this.settings.getPoolSize();
        if (!this.settings.isAdaptivePoolSize()) {
            return configured;
        }
        int width = graph.maxConcurrentWidth(new LinkedHashSet<>(candidates));
        if (width <= 0) {
            return configured;
        }
        return Math.min(configured, Math.max(2, width));
    }

    /**
     * Iteratively reclassify as main-thread any eligible bean that is joined by a sync
     * edge (in either direction) to a bean already known to run on the main thread,
     * until a fixpoint is reached.
     */
    private static void propagateMainline(BeanDependencyGraph graph, Set<String> eligible, Set<String> mainline) {
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
            Set<String> neighbors = new LinkedHashSet<>(graph.getSyncDependencies(current));
            neighbors.addAll(dependents.getOrDefault(current, Collections.emptySet()));
            for (String neighbor : neighbors) {
                if (eligible.remove(neighbor)) {
                    mainline.add(neighbor);
                    worklist.add(neighbor);
                }
            }
        }
    }

    /**
     * Collect the names of beans that the framework force-instantiates on the main
     * thread before backgrounding a dependent: the factory bean of any bean (for
     * example a {@code @Configuration} class hosting {@code @Bean} methods) and the
     * target of any {@code depends-on} declaration. Such beans cannot themselves be
     * background candidates.
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
        }
        return forced;
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

    private static void markForBackgroundInit(ConfigurableListableBeanFactory beanFactory, String beanName) {
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

    private static ThreadPoolExecutor createBoundedExecutor(int poolSize, String threadNamePrefix) {
        ThreadFactory threadFactory = new BootstrapThreadFactory(threadNamePrefix);
        return new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
    }

    /**
     * Install the opt-in {@link BeanStartupProfiler} as a bean post-processor and arrange
     * for a summary report to be logged once the context has refreshed. Installation is
     * idempotent and degrades gracefully: any failure here is logged and ignored so that
     * profiling can never break the context.
     */
    private void installProfiler(ConfigurableListableBeanFactory beanFactory) {
        String profilerName = getClass().getName() + ".profiler";
        if (beanFactory.containsSingleton(profilerName)) {
            // Already installed (apply may run via both the BeanFactoryPostProcessor and
            // BeanFactoryInitializer entry points).
            return;
        }
        try {
            BeanStartupProfiler profiler = new BeanStartupProfiler(this.settings.getThreadNamePrefix());
            beanFactory.addBeanPostProcessor(profiler);
            beanFactory.registerSingleton(profilerName, profiler);
            String reportListenerName = getClass().getName() + ".profilerReportListener";
            ApplicationListener<ContextRefreshedEvent> reportListener = event -> {
                if (logger.isInfoEnabled()) {
                    logger.info(profiler.report());
                }
            };
            beanFactory.registerSingleton(reportListenerName, reportListener);
            logger.debug("Startup profiling enabled");
        } catch (RuntimeException ex) {
            logger.warn("Startup profiler installation failed; continuing without profiling", ex);
        }
    }

    private static void registerShutdownHook(ConfigurableListableBeanFactory beanFactory, ThreadPoolExecutor executor) {
        // Shut the pool down right after the context has refreshed so it does not
        // linger for the lifetime of the context. The listener is registered as a
        // manual singleton so that it is detected by the context's event multicaster.
        String listenerName = ParallelBootstrapBeanFactoryPostProcessor.class.getName() + ".shutdownListener";
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
