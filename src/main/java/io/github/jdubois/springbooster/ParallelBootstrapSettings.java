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

import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.util.Assert;

/**
 * Configuration settings for {@link ParallelBootstrapBeanFactoryPostProcessor}.
 *
 * <p>Controls the size of the bounded bootstrap thread pool, the thread naming
 * prefix, an additional candidate predicate, and a global kill-switch that allows
 * disabling parallel bootstrapping without removing the post-processor.
 *
 * <p>Instances are created through {@link #builder()} and are immutable.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor
 */
public final class ParallelBootstrapSettings {

    /**
     * Bean definition attribute that, when set to {@code Boolean.TRUE}, explicitly
     * opts a bean out of parallel background initialization.
     */
    public static final String OPT_OUT_ATTRIBUTE = ParallelBootstrapSettings.class.getName() + ".optOut";

    private final boolean enabled;

    private final int poolSize;

    private final String threadNamePrefix;

    private final Predicate<String> candidateFilter;

    private final boolean backgroundFactoryMethodBeans;

    private final boolean profileStartup;

    private final int minimumBackgroundCandidates;

    private final boolean adaptivePoolSize;

    private ParallelBootstrapSettings(
            boolean enabled,
            int poolSize,
            String threadNamePrefix,
            Predicate<String> candidateFilter,
            boolean backgroundFactoryMethodBeans,
            boolean profileStartup,
            int minimumBackgroundCandidates,
            boolean adaptivePoolSize) {

        this.enabled = enabled;
        this.poolSize = poolSize;
        this.threadNamePrefix = threadNamePrefix;
        this.candidateFilter = candidateFilter;
        this.backgroundFactoryMethodBeans = backgroundFactoryMethodBeans;
        this.profileStartup = profileStartup;
        this.minimumBackgroundCandidates = minimumBackgroundCandidates;
        this.adaptivePoolSize = adaptivePoolSize;
    }

    /**
     * Whether parallel bootstrapping is enabled (global kill-switch).
     * @return whether parallel bootstrapping is enabled
     */
    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * The number of threads in the bounded bootstrap pool.
     * @return the bootstrap pool size
     */
    public int getPoolSize() {
        return this.poolSize;
    }

    /**
     * The thread name prefix used for bootstrap threads.
     * @return the thread name prefix
     */
    public String getThreadNamePrefix() {
        return this.threadNamePrefix;
    }

    /**
     * An additional user-supplied filter applied to candidate bean names; a bean is
     * only eligible for background initialization if this predicate returns
     * {@code true}. Defaults to accepting every bean.
     * @return the candidate filter predicate
     */
    public Predicate<String> getCandidateFilter() {
        return this.candidateFilter;
    }

    /**
     * Whether beans produced by {@code @Bean} factory methods are eligible for
     * background initialization.
     * <p>Defaults to {@code false}. Configuration classes are always created on the
     * main thread and are the primary site of dynamic, by-type bean access during
     * context refresh (captured {@code ApplicationContext}/{@code BeanFactory} lookups,
     * {@code ObjectProvider}/{@code Lazy} resolution from framework callbacks, CGLIB
     * {@code @Bean} self-invocation). Such access is invisible to static injection-point
     * analysis, so by default every factory-method bean is co-located with its
     * configuration class on the main thread, which keeps accept-all bootstrapping safe
     * on fully auto-configured Spring Boot applications. Only component-scanned beans and
     * beans registered as plain definitions are backgrounded.
     * <p>Set to {@code true} to also background factory-method beans (subject to the
     * remaining structural and connectivity safety checks). This maximizes parallelism
     * but reintroduces the risk of {@code BeanCurrentlyInCreationException} when a
     * main-thread bean pulls a backgrounded {@code @Bean} bean by type through a call the
     * analysis cannot see; pair it with a {@link #getCandidateFilter() candidate filter}
     * scoped to beans known to be safe.
     * @return whether factory-method beans may be backgrounded
     */
    public boolean isBackgroundFactoryMethodBeans() {
        return this.backgroundFactoryMethodBeans;
    }

    /**
     * Whether opt-in startup profiling is enabled.
     * <p>Defaults to {@code false}. When {@code true}, a {@link BeanStartupProfiler} is
     * installed that records, for every singleton created during context refresh, the
     * thread it was created on and the inclusive wall-clock time its creation took, and a
     * summary of the slowest beans is logged once the context has refreshed. Profiling
     * never changes application semantics; it is intended to identify which heavyweight
     * beans are worth backgrounding. It is independent of the {@link #isEnabled() global
     * kill-switch}, so a sequential baseline can be profiled by combining
     * {@code profileStartup(true)} with {@code enabled(false)}.
     * @return whether startup profiling is enabled
     */
    public boolean isProfileStartup() {
        return this.profileStartup;
    }

    /**
     * The minimum number of eligible background candidates required before parallel
     * bootstrapping is actually engaged.
     * <p>Defaults to {@code 1} (any single candidate triggers parallelism). Installing
     * and tearing down a bounded thread pool has a fixed cost, and on applications whose
     * startup is dominated by a few main-thread {@code @Bean} beans the handful of
     * lightweight background candidates can save less time than the pool costs to run —
     * occasionally making a "boosted" start marginally <em>slower</em> than a sequential
     * one. Setting a higher threshold acts as a "don't bother" guard: when fewer than this
     * many beans would be backgrounded, the post-processor skips planning entirely and the
     * context bootstraps sequentially. This extends the graceful-degradation design goal to
     * "never make startup worse".
     * @return the minimum number of background candidates required to engage parallelism
     */
    public int getMinimumBackgroundCandidates() {
        return this.minimumBackgroundCandidates;
    }

    /**
     * Whether the bootstrap pool is sized to the dependency graph's achievable
     * concurrency width rather than to the fixed {@link #getPoolSize() pool size}.
     * <p>Defaults to {@code false}, preserving the fixed pool size. When {@code true}, the
     * post-processor measures how many of the selected background candidates can actually
     * run at the same time — the widest topological layer of the candidate subgraph — and
     * caps the pool at that width (with a floor of {@code 2} and never exceeding
     * {@link #getPoolSize()}). A dependency-constrained candidate set that can only ever run
     * two beans concurrently gains nothing from an eight-thread pool; adaptive sizing avoids
     * the idle threads and the associated scheduling overhead.
     * @return whether the pool is sized to the measured concurrency width
     */
    public boolean isAdaptivePoolSize() {
        return this.adaptivePoolSize;
    }

    /**
     * Create settings with sensible defaults: enabled, a pool size of twice the
     * number of available processors, the {@code parallel-bootstrap-} thread prefix,
     * and a candidate filter that accepts every bean.
     * @return a new settings instance populated with default values
     */
    public static ParallelBootstrapSettings withDefaults() {
        return builder().build();
    }

    /**
     * Create a new {@link Builder} pre-populated with default values.
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Compute the default bootstrap pool size as twice the number of available
     * processors, with a floor of {@code 2} so that at least some parallelism is
     * available on single-core environments.
     * <p>Bean bootstrap is frequently I/O- or blocking-bound (opening connection
     * pools, warming caches, establishing remote clients), so the pool is
     * deliberately oversized relative to the CPU count to keep cores busy while
     * other threads wait. Workloads that are purely CPU-bound, or that block for
     * unusually long, may benefit from tuning {@code poolSize} explicitly.
     * @return the default bootstrap pool size
     */
    public static int defaultPoolSize() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    }

    /**
     * Determine whether the given bean definition has explicitly opted out of
     * parallel background initialization via {@link #OPT_OUT_ATTRIBUTE}.
     */
    static boolean isOptedOut(@Nullable BeanDefinition beanDefinition) {
        return (beanDefinition != null && Boolean.TRUE.equals(beanDefinition.getAttribute(OPT_OUT_ATTRIBUTE)));
    }

    /**
     * Builder for {@link ParallelBootstrapSettings}.
     */
    public static final class Builder {

        private boolean enabled = true;

        private int poolSize = defaultPoolSize();

        private String threadNamePrefix = "parallel-bootstrap-";

        private Predicate<String> candidateFilter = beanName -> true;

        private boolean backgroundFactoryMethodBeans = false;

        private boolean profileStartup = false;

        private int minimumBackgroundCandidates = 1;

        private boolean adaptivePoolSize = false;

        private Builder() {}

        /**
         * Set the global kill-switch. When {@code false}, the post-processor performs
         * no work and the context bootstraps sequentially.
         * @param enabled whether parallel bootstrapping is enabled
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Set the bounded bootstrap pool size. Must be a positive number.
         * @param poolSize the number of threads in the bootstrap pool (must be positive)
         * @return this builder
         */
        public Builder poolSize(int poolSize) {
            Assert.isTrue(poolSize > 0, "'poolSize' must be positive");
            this.poolSize = poolSize;
            return this;
        }

        /**
         * Set the thread name prefix for bootstrap threads.
         * @param threadNamePrefix the thread name prefix (must not be empty)
         * @return this builder
         */
        public Builder threadNamePrefix(String threadNamePrefix) {
            Assert.hasText(threadNamePrefix, "'threadNamePrefix' must not be empty");
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        /**
         * Set an additional candidate filter by bean name. Beans for which the
         * predicate returns {@code false} are never marked for background
         * initialization, regardless of the built-in safety checks.
         * @param candidateFilter the candidate filter predicate (must not be null)
         * @return this builder
         */
        public Builder candidateFilter(Predicate<String> candidateFilter) {
            Assert.notNull(candidateFilter, "'candidateFilter' must not be null");
            this.candidateFilter = candidateFilter;
            return this;
        }

        /**
         * Set whether beans produced by {@code @Bean} factory methods may be
         * backgrounded. Defaults to {@code false}, which co-locates every factory-method
         * bean with its (always main-thread) configuration class so that accept-all
         * bootstrapping stays safe. Set to {@code true} to maximize parallelism at the
         * cost of reintroducing the invisible by-type pull risk for {@code @Bean} beans.
         * @param backgroundFactoryMethodBeans whether factory-method beans may be backgrounded
         * @return this builder
         * @see ParallelBootstrapSettings#isBackgroundFactoryMethodBeans()
         */
        public Builder backgroundFactoryMethodBeans(boolean backgroundFactoryMethodBeans) {
            this.backgroundFactoryMethodBeans = backgroundFactoryMethodBeans;
            return this;
        }

        /**
         * Set whether opt-in startup profiling is enabled. Defaults to {@code false}.
         * When {@code true}, a {@link BeanStartupProfiler} records per-bean creation
         * thread and inclusive wall-clock duration and logs a summary of the slowest
         * beans after refresh. Profiling never changes application semantics and is
         * independent of the {@link #enabled(boolean) kill-switch}.
         * @param profileStartup whether startup profiling is enabled
         * @return this builder
         * @see ParallelBootstrapSettings#isProfileStartup()
         */
        public Builder profileStartup(boolean profileStartup) {
            this.profileStartup = profileStartup;
            return this;
        }

        /**
         * Set the minimum number of eligible background candidates required before
         * parallel bootstrapping is engaged. Must be at least {@code 1}. Defaults to
         * {@code 1} (any candidate triggers parallelism). A higher value acts as a
         * "don't bother" guard: when fewer beans than this would be backgrounded, the
         * context bootstraps sequentially instead, so the executor overhead is never paid
         * for a negligible win.
         * @param minimumBackgroundCandidates the minimum candidate count (must be at least {@code 1})
         * @return this builder
         * @see ParallelBootstrapSettings#getMinimumBackgroundCandidates()
         */
        public Builder minimumBackgroundCandidates(int minimumBackgroundCandidates) {
            Assert.isTrue(minimumBackgroundCandidates >= 1, "'minimumBackgroundCandidates' must be at least 1");
            this.minimumBackgroundCandidates = minimumBackgroundCandidates;
            return this;
        }

        /**
         * Set whether the bootstrap pool is sized to the measured concurrency width of
         * the selected candidate set rather than to the fixed {@link #poolSize(int) pool
         * size}. Defaults to {@code false}. When {@code true}, the pool is capped at the
         * widest topological layer of the candidate subgraph (floor of {@code 2}, never
         * exceeding the configured pool size), avoiding idle threads when the candidates
         * cannot all run concurrently.
         * @param adaptivePoolSize whether to size the pool to the measured concurrency width
         * @return this builder
         * @see ParallelBootstrapSettings#isAdaptivePoolSize()
         */
        public Builder adaptivePoolSize(boolean adaptivePoolSize) {
            this.adaptivePoolSize = adaptivePoolSize;
            return this;
        }

        /**
         * Build the immutable {@link ParallelBootstrapSettings} instance.
         * @return the immutable settings instance
         */
        public ParallelBootstrapSettings build() {
            return new ParallelBootstrapSettings(
                    this.enabled,
                    this.poolSize,
                    this.threadNamePrefix,
                    this.candidateFilter,
                    this.backgroundFactoryMethodBeans,
                    this.profileStartup,
                    this.minimumBackgroundCandidates,
                    this.adaptivePoolSize);
        }
    }
}
