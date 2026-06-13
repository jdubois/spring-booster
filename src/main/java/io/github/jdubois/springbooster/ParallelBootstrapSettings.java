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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Bean definition attribute that, when set to {@code Boolean.TRUE}, explicitly
     * opts a bean <em>into</em> background initialization even when it is a
     * {@code @Bean} factory-method bean that the safe default would otherwise co-locate
     * with its configuration class (see {@link #getBackgroundBeanNames()}). This is the
     * per-definition counterpart of {@link #getBackgroundBeanNames()}.
     */
    public static final String FORCE_BACKGROUND_ATTRIBUTE =
            ParallelBootstrapSettings.class.getName() + ".forceBackground";

    private final boolean enabled;

    private final int poolSize;

    private final String threadNamePrefix;

    private final Predicate<String> candidateFilter;

    private final Set<String> backgroundBeanNames;

    private final boolean backgroundFactoryMethodBeans;

    private final boolean deferProviderEdges;

    private final boolean backgroundSharedInfraConsumers;

    private final Set<String> barrierBeanNames;

    private final List<Set<String>> coBackgroundGroups;

    private ParallelBootstrapSettings(
            boolean enabled,
            int poolSize,
            String threadNamePrefix,
            Predicate<String> candidateFilter,
            Set<String> backgroundBeanNames,
            boolean backgroundFactoryMethodBeans,
            boolean deferProviderEdges,
            boolean backgroundSharedInfraConsumers,
            Set<String> barrierBeanNames,
            List<Set<String>> coBackgroundGroups) {

        this.enabled = enabled;
        this.poolSize = poolSize;
        this.threadNamePrefix = threadNamePrefix;
        this.candidateFilter = candidateFilter;
        this.backgroundBeanNames = backgroundBeanNames;
        this.backgroundFactoryMethodBeans = backgroundFactoryMethodBeans;
        this.deferProviderEdges = deferProviderEdges;
        this.backgroundSharedInfraConsumers = backgroundSharedInfraConsumers;
        this.barrierBeanNames = barrierBeanNames;
        this.coBackgroundGroups = coBackgroundGroups;
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
     * The set of {@code @Bean} factory-method bean names the user has explicitly asserted
     * are safe to background, even when a <em>dynamic</em> configuration is present and the
     * safe default would otherwise co-locate every {@code @Bean} bean with its
     * configuration class (see {@link #isBackgroundFactoryMethodBeans()}).
     * <p>Defaults to the empty set. Naming a bean here drops only its
     * configuration&rarr;{@code @Bean} <em>co-location</em> edge; the bean still has to pass
     * every other safety check &mdash; it must not be in a cycle, must not be a
     * forced-mainline {@code depends-on}/factory target, must not be opted out, and is still
     * subject to the connectivity-safe sync-edge propagation. A bean named here that is
     * pulled mainline by a genuine (visible) sync edge therefore stays on the main thread, and
     * an invisible eager by-type pull still fails fast with
     * {@code BeanCurrentlyInCreationException} and falls back to sequential bootstrap. This is
     * the targeted, per-bean alternative to the context-wide
     * {@link #isBackgroundFactoryMethodBeans()} flag.
     * @return the explicit background allowlist of {@code @Bean} bean names
     * @see #FORCE_BACKGROUND_ATTRIBUTE
     */
    public Set<String> getBackgroundBeanNames() {
        return this.backgroundBeanNames;
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
     * Whether by-type dependency edges reached only through an {@code ObjectProvider},
     * {@code ObjectFactory} or {@code Provider} wrapper, or through a {@code @Lazy}
     * injection point, are treated as <em>deferred</em>.
     * <p>Defaults to {@code false}. A deferred dependency is not resolved while the
     * dependent bean is being constructed, so &mdash; unlike an ordinary <em>sync</em>
     * dependency &mdash; it does not force the dependent and the dependency onto the same
     * thread. When this is enabled, such edges are excluded from the connectivity-safe
     * boundary, so a main-thread bean that depends on a background subtree <em>only</em>
     * through a provider or {@code @Lazy} no longer drags that subtree onto the main
     * thread, letting larger chunks be backgrounded.
     * <p>This is opt-in because the relaxation is sound only if the provider/{@code @Lazy}
     * handle is dereferenced <em>after</em> the dependent bean has been constructed. A
     * bean that eagerly dereferences a provider inside its constructor or an
     * initialization callback (for example a framework callback such as
     * {@code WebMvcConfigurer.addArgumentResolvers}) would request a background bean from
     * the main thread and fail with {@code BeanCurrentlyInCreationException}. Enable it
     * only when you know your provider/{@code @Lazy} dependencies are resolved lazily, and
     * pair it with a {@link #getCandidateFilter() candidate filter} or the
     * {@link #OPT_OUT_ATTRIBUTE opt-out attribute} for any bean that does not fit that
     * pattern.
     * @return whether provider / {@code @Lazy} edges are treated as deferred
     */
    public boolean isDeferProviderEdges() {
        return this.deferProviderEdges;
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
     * The set of bean names the user has explicitly asserted are <em>completed-leaf
     * barriers</em> &mdash; terminal infrastructure singletons that the framework finishes
     * on the main thread before their consumers fan out, and which those consumers only
     * <em>read</em>.
     * <p>Defaults to the empty set. This is the user-named counterpart of the structural
     * barrier predicate behind {@link #isBackgroundSharedInfraConsumers()}: that predicate
     * only ever treats a <em>forced-mainline</em> bean (a factory bean, {@code depends-on}
     * target, or configuration class) as a barrier, so a shared infrastructure singleton
     * exposed purely as a co-located {@code @Bean} (a {@code DataSource} declared by a
     * {@code @Configuration} class, for example) is not recognised and its independent
     * consumers (Flyway and Liquibase) stay serialized on the main thread. Naming such a
     * bean here makes the planner treat it as a barrier: it is pinned to the main thread and
     * its mainline-ness is not propagated to its consumers, so a factory-method {@code @Bean}
     * whose every sync dependency is a barrier is freed from co-location and may overlap its
     * siblings on background threads.
     * <p>A named bean is honored as a barrier only when it is structurally safe &mdash; it
     * must be acyclic and a genuine leaf (it must not have a sync dependency on any
     * still-backgroundable bean), so its own construction cannot pull a background bean
     * mid-creation. A name that fails these checks is ignored. As with the other relaxations,
     * a violated assumption fails fast with {@code BeanCurrentlyInCreationException} and the
     * bootstrap falls back to sequential instantiation. Supplying a non-empty set activates
     * the shared-infrastructure relaxation for the named barriers even when
     * {@link #isBackgroundSharedInfraConsumers()} is {@code false}; in that case only the
     * named barriers (not the auto-detected structural ones) are used.
     * @return the explicit set of completed-leaf barrier bean names
     * @see #isBackgroundSharedInfraConsumers()
     */
    public Set<String> getBarrierBeanNames() {
        return this.barrierBeanNames;
    }

    /**
     * Groups of {@code @Bean} factory-method bean names the user has asserted are
     * <em>mutually independent</em> heavyweight beans that may be constructed concurrently.
     * <p>Defaults to the empty list. This is the ergonomic, group-oriented wrapper over the
     * per-bean {@link #getBackgroundBeanNames() allowlist}: for every member of a group the
     * planner drops the configuration&rarr;{@code @Bean} co-location edge (so the bean may
     * background even when a dynamic configuration is present), and it additionally drops any
     * sync co-location edge <em>between two members of the same group</em>, so an
     * asserted-independent pair (such as {@code entityManagerFactory} and
     * {@code springSecurityFilterChain}, or {@code flyway} and {@code liquibase}) is not
     * forced onto a single thread. Every {@code @Bean} bean outside the declared groups stays
     * co-located on the main thread.
     * <p>The relaxation only ever <em>removes</em> co-location edges; it never overrides a
     * forced {@code depends-on} or factory-bean edge (those remain in the full dependency
     * graph, so ordering, cycle detection, and the forced-mainline rule are unaffected) and
     * it never bypasses {@link #getCandidateFilter()}, the opt-out attribute, or the
     * connectivity-safe propagation. A group member that is a forced-mainline
     * {@code depends-on}/factory target, in a cycle, or joined to the main thread by a genuine
     * visible sync edge therefore stays on the main thread, and an invisible eager by-type
     * pull still fails fast with {@code BeanCurrentlyInCreationException}.
     * @return the declared co-background groups of mutually-independent bean names
     * @see #getBackgroundBeanNames()
     */
    public List<Set<String>> getCoBackgroundGroups() {
        return this.coBackgroundGroups;
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
     * Determine whether the given bean definition has explicitly opted <em>into</em>
     * background initialization via {@link #FORCE_BACKGROUND_ATTRIBUTE}.
     */
    static boolean isForcedBackground(@Nullable BeanDefinition beanDefinition) {
        return (beanDefinition != null && Boolean.TRUE.equals(beanDefinition.getAttribute(FORCE_BACKGROUND_ATTRIBUTE)));
    }

    /**
     * Builder for {@link ParallelBootstrapSettings}.
     */
    public static final class Builder {

        private boolean enabled = true;

        private int poolSize = defaultPoolSize();

        private String threadNamePrefix = "parallel-bootstrap-";

        private Predicate<String> candidateFilter = beanName -> true;

        private Set<String> backgroundBeanNames = Collections.emptySet();

        private boolean backgroundFactoryMethodBeans = false;

        private boolean deferProviderEdges = false;

        private boolean backgroundSharedInfraConsumers = false;

        private Set<String> barrierBeanNames = Collections.emptySet();

        private List<Set<String>> coBackgroundGroups = Collections.emptyList();

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
         * Set the explicit allowlist of {@code @Bean} factory-method bean names that may be
         * backgrounded even when a dynamic configuration is present (which normally co-locates
         * every {@code @Bean} bean with its configuration class). The names are copied
         * defensively; passing {@code null} or an empty collection clears the allowlist. This
         * is the targeted, per-bean alternative to the context-wide
         * {@link #backgroundFactoryMethodBeans(boolean)} flag: only the named beans lose their
         * co-location edge, while every other {@code @Bean} bean stays on the main thread.
         * @param backgroundBeanNames the {@code @Bean} bean names to allow into the background
         * @return this builder
         * @see ParallelBootstrapSettings#getBackgroundBeanNames()
         */
        public Builder backgroundBeanNames(@Nullable Collection<String> backgroundBeanNames) {
            // Treat null and empty identically: both mean "no allowlist", i.e. the empty set.
            this.backgroundBeanNames = (backgroundBeanNames == null || backgroundBeanNames.isEmpty())
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(backgroundBeanNames));
            return this;
        }

        /**
         * Set the explicit allowlist of {@code @Bean} factory-method bean names that may be
         * backgrounded, as a varargs convenience over
         * {@link #backgroundBeanNames(Collection)}.
         * @param backgroundBeanNames the {@code @Bean} bean names to allow into the background
         * @return this builder
         * @see ParallelBootstrapSettings#getBackgroundBeanNames()
         */
        public Builder backgroundBeanNames(String... backgroundBeanNames) {
            return backgroundBeanNames(List.of(backgroundBeanNames));
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
         * Set whether by-type edges reached only through an {@code ObjectProvider},
         * {@code ObjectFactory} or {@code Provider} wrapper, or through a {@code @Lazy}
         * injection point, are treated as <em>deferred</em> and therefore excluded from
         * the connectivity-safe boundary. Defaults to {@code false}. Enabling this lets a
         * main-thread bean depend on a background subtree purely through a provider /
         * {@code @Lazy} without dragging that subtree onto the main thread, at the cost of
         * assuming such handles are dereferenced lazily (after construction).
         * @param deferProviderEdges whether provider / {@code @Lazy} edges are deferred
         * @return this builder
         * @see ParallelBootstrapSettings#isDeferProviderEdges()
         */
        public Builder deferProviderEdges(boolean deferProviderEdges) {
            this.deferProviderEdges = deferProviderEdges;
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
         * Set the explicit set of bean names to treat as <em>completed-leaf barriers</em>
         * &mdash; terminal main-thread infrastructure singletons across which mainline-ness is
         * not propagated to consumers, so independent consumers of one such bean may overlap
         * on background threads. The names are copied defensively; passing {@code null} or an
         * empty collection clears the set. This is the user-named override of the structural
         * barrier predicate behind {@link #backgroundSharedInfraConsumers(boolean)}: it lets a
         * shared singleton exposed only as a co-located {@code @Bean} (such as a
         * {@code DataSource}) act as a barrier so that, for example, Flyway and Liquibase
         * overlap. A named bean is honored only when it is acyclic and a genuine leaf;
         * supplying a non-empty set activates the relaxation for the named barriers even when
         * {@link #backgroundSharedInfraConsumers(boolean)} is {@code false}.
         * @param barrierBeanNames the bean names to treat as completed-leaf barriers
         * @return this builder
         * @see ParallelBootstrapSettings#getBarrierBeanNames()
         */
        public Builder barrierBeanNames(@Nullable Collection<String> barrierBeanNames) {
            // Treat null and empty identically: both mean "no named barriers".
            this.barrierBeanNames = (barrierBeanNames == null || barrierBeanNames.isEmpty())
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(barrierBeanNames));
            return this;
        }

        /**
         * Set the bean names to treat as completed-leaf barriers, as a varargs convenience
         * over {@link #barrierBeanNames(Collection)}.
         * @param barrierBeanNames the bean names to treat as completed-leaf barriers
         * @return this builder
         * @see ParallelBootstrapSettings#getBarrierBeanNames()
         */
        public Builder barrierBeanNames(String... barrierBeanNames) {
            return barrierBeanNames(List.of(barrierBeanNames));
        }

        /**
         * Replace the declared <em>co-background groups</em> &mdash; groups of {@code @Bean}
         * bean names the user asserts are mutually independent and may be constructed
         * concurrently. For every group member the planner drops the configuration&rarr;
         * {@code @Bean} co-location edge, and it additionally drops any sync co-location edge
         * between two members of the same group, so asserted-independent heavyweights overlap
         * rather than serialize. The groups are copied defensively; passing {@code null} or an
         * empty collection clears them, and empty or singleton groups are ignored (they impose
         * no inter-member constraint). This is the ergonomic, group-oriented wrapper over
         * {@link #backgroundBeanNames(Collection)}; forced {@code depends-on}/factory edges and
         * every other safety gate still apply.
         * @param coBackgroundGroups the groups of mutually-independent bean names
         * @return this builder
         * @see ParallelBootstrapSettings#getCoBackgroundGroups()
         */
        public Builder coBackgroundGroups(@Nullable Collection<? extends Collection<String>> coBackgroundGroups) {
            if (coBackgroundGroups == null || coBackgroundGroups.isEmpty()) {
                this.coBackgroundGroups = Collections.emptyList();
                return this;
            }
            List<Set<String>> groups = new ArrayList<>();
            for (Collection<String> group : coBackgroundGroups) {
                if (group != null && !group.isEmpty()) {
                    groups.add(Collections.unmodifiableSet(new LinkedHashSet<>(group)));
                }
            }
            this.coBackgroundGroups = Collections.unmodifiableList(groups);
            return this;
        }

        /**
         * Add a single <em>co-background group</em> of mutually-independent {@code @Bean} bean
         * names to the declared groups, as a varargs convenience over
         * {@link #coBackgroundGroups(Collection)}. A {@code null}, empty, or singleton group is
         * ignored.
         * @param members the mutually-independent bean names forming one group
         * @return this builder
         * @see ParallelBootstrapSettings#getCoBackgroundGroups()
         */
        public Builder coBackgroundGroup(String... members) {
            if (members == null || members.length == 0) {
                return this;
            }
            List<Set<String>> groups = new ArrayList<>(this.coBackgroundGroups);
            groups.add(Collections.unmodifiableSet(new LinkedHashSet<>(List.of(members))));
            this.coBackgroundGroups = Collections.unmodifiableList(groups);
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
                    this.backgroundBeanNames,
                    this.backgroundFactoryMethodBeans,
                    this.deferProviderEdges,
                    this.backgroundSharedInfraConsumers,
                    this.barrierBeanNames,
                    this.coBackgroundGroups);
        }
    }
}
