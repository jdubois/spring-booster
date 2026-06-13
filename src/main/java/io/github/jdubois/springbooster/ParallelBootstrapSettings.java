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

    private final boolean backgroundSharedInfraConsumers;

    private ParallelBootstrapSettings(
            boolean enabled,
            int poolSize,
            String threadNamePrefix,
            Predicate<String> candidateFilter,
            boolean backgroundFactoryMethodBeans,
            boolean backgroundSharedInfraConsumers) {

        this.enabled = enabled;
        this.poolSize = poolSize;
        this.threadNamePrefix = threadNamePrefix;
        this.candidateFilter = candidateFilter;
        this.backgroundFactoryMethodBeans = backgroundFactoryMethodBeans;
        this.backgroundSharedInfraConsumers = backgroundSharedInfraConsumers;
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
     * Whether beans that depend only on <em>completed-leaf</em> main-thread
     * infrastructure singletons may still be backgrounded.
     * <p>Defaults to {@code false}. By default the planner is
     * <em>connectivity-safe</em>: any bean joined by a sync dependency edge &mdash; in
     * either direction &mdash; to a bean that runs on the main thread is itself kept on
     * the main thread. That conservative closure also pulls back beans whose <em>only</em>
     * link to the main thread is a read of a shared, terminal infrastructure singleton
     * (a {@code DataSource}/connection pool, for example) that the framework fully
     * constructs on the main thread before those beans are touched. The canonical case is
     * two mutually independent, heavyweight consumers of one shared {@code DataSource}
     * (such as Flyway and Liquibase): they could run concurrently, but the default rule
     * serializes them because both read the main-thread {@code DataSource}.
     * <p>Set to {@code true} to relax that one case: a main-thread bean is treated as a
     * <em>completed-leaf barrier</em> &mdash; across which mainline-ness is not propagated
     * to its dependents &mdash; when it is force-instantiated on the main thread (a factory
     * bean, a {@code depends-on} target, or a configuration class), is not part of a
     * dependency cycle, and itself has no sync dependency on any backgroundable bean (so it
     * is a genuine leaf that completes before its dependents fan out). Only the
     * barrier&rarr;dependent direction is exempted; a main-thread bean that <em>depends on</em>
     * a candidate still forces that candidate onto the main thread, because that is the
     * genuine in-flight pull. This relaxation only ever <em>removes</em> propagation, never
     * adds dependency edges, so it cannot introduce cycles or perturb the topological
     * layering. As with {@link #isBackgroundFactoryMethodBeans()}, a violated assumption
     * fails fast with {@code BeanCurrentlyInCreationException} and the bootstrap falls back
     * to sequential instantiation.
     * @return whether shared-infrastructure consumers may be backgrounded
     */
    public boolean isBackgroundSharedInfraConsumers() {
        return this.backgroundSharedInfraConsumers;
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

        private boolean backgroundSharedInfraConsumers = false;

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
         * Set whether beans that depend only on completed-leaf main-thread
         * infrastructure singletons may still be backgrounded. Defaults to {@code false}.
         * Set to {@code true} to allow mutually independent consumers of a shared,
         * terminal infrastructure bean (such as a {@code DataSource}) to run concurrently
         * even though that infrastructure bean stays on the main thread.
         * @param backgroundSharedInfraConsumers whether shared-infrastructure consumers may
         * be backgrounded
         * @return this builder
         * @see ParallelBootstrapSettings#isBackgroundSharedInfraConsumers()
         */
        public Builder backgroundSharedInfraConsumers(boolean backgroundSharedInfraConsumers) {
            this.backgroundSharedInfraConsumers = backgroundSharedInfraConsumers;
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
                    this.backgroundSharedInfraConsumers);
        }
    }
}
