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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Enables opt-in parallel instantiation of non-lazy singleton beans during
 * application context bootstrap.
 *
 * <p>To be used together with
 * {@link org.springframework.context.annotation.Configuration @Configuration}
 * classes as follows:
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableParallelBootstrap
 * public class AppConfig {
 * }</pre>
 *
 * <p>This registers a {@link ParallelBootstrapBeanFactoryPostProcessor} that plans
 * which beans can be created concurrently and installs a bounded bootstrap thread
 * pool sized, by default, at twice the number of available processors. Nothing is
 * parallelized unless this annotation (or an
 * equivalent programmatic registration) is present, keeping the feature strictly
 * opt-in.
 *
 * <p>The pool size and other behavior can be tuned through the annotation
 * attributes, or for full control by registering a
 * {@link ParallelBootstrapBeanFactoryPostProcessor} bean directly with a custom
 * {@link ParallelBootstrapSettings} instance instead of using this annotation.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor
 * @see ParallelBootstrapSettings
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ParallelBootstrapRegistrar.class)
public @interface EnableParallelBootstrap {

    /**
     * Global kill-switch. When {@code false}, the post-processor is registered but
     * performs no work, so the context bootstraps sequentially.
     * @return whether parallel bootstrapping is enabled
     */
    boolean enabled() default true;

    /**
     * The number of threads in the bounded bootstrap pool. A value of {@code -1}
     * (the default) derives the size as twice the number of available processors
     * (with a floor of {@code 2}), since bootstrap work is often I/O- or
     * blocking-bound. Set an explicit positive value to override this.
     * @return the bootstrap pool size, or {@code -1} to derive it automatically
     */
    int poolSize() default -1;

    /**
     * The thread name prefix used for bootstrap threads.
     * @return the thread name prefix for bootstrap threads
     */
    String threadNamePrefix() default "parallel-bootstrap-";

    /**
     * An explicit allowlist of {@code @Bean} factory-method bean names that may be
     * backgrounded even when a dynamic configuration is present (which normally co-locates
     * every {@code @Bean} bean with its configuration class). Defaults to an empty array.
     * <p>This is the targeted, per-bean alternative to {@link #backgroundFactoryMethodBeans()}:
     * only the named beans lose their co-location edge, while every other {@code @Bean} bean
     * stays on the main thread. Each named bean must still clear the remaining safety checks
     * and the connectivity-safe propagation, so a genuinely entangled bean is kept on the main
     * thread and the {@code BeanCurrentlyInCreationException} fast-fail remains the backstop.
     * Useful for backgrounding a known-independent heavyweight bean such as
     * {@code springSecurityFilterChain}.
     * @return the explicit background allowlist of {@code @Bean} bean names
     * @see ParallelBootstrapSettings#getBackgroundBeanNames()
     */
    String[] backgroundBeanNames() default {};

    /**
     * Whether beans produced by {@code @Bean} factory methods are eligible for
     * background initialization. Defaults to {@code false}, which co-locates every
     * factory-method bean with its (always main-thread) configuration class so that
     * accept-all bootstrapping stays safe on fully auto-configured applications. Set to
     * {@code true} to also background factory-method beans for maximum parallelism, at
     * the cost of reintroducing the invisible by-type pull risk for {@code @Bean} beans.
     * @return whether factory-method beans may be backgrounded
     * @see ParallelBootstrapSettings#isBackgroundFactoryMethodBeans()
     */
    boolean backgroundFactoryMethodBeans() default false;

    /**
     * Whether by-type dependency edges reached only through an {@code ObjectProvider},
     * {@code ObjectFactory} or {@code Provider} wrapper, or through a {@code @Lazy}
     * injection point, are treated as <em>deferred</em> and excluded from the
     * connectivity-safe boundary. Defaults to {@code false}. Enabling this lets a
     * main-thread bean depend on a background subtree purely through a provider /
     * {@code @Lazy} without dragging that subtree onto the main thread, at the cost of
     * assuming such handles are dereferenced lazily (after construction).
     * @return whether provider / {@code @Lazy} edges are deferred
     * @see ParallelBootstrapSettings#isDeferProviderEdges()
     */
    boolean deferProviderEdges() default false;

    /**
     * Whether beans that depend only on completed-leaf main-thread infrastructure
     * singletons may still be backgrounded. Defaults to {@code false}. Set to
     * {@code true} to let mutually independent consumers of a shared, terminal
     * infrastructure bean (such as a {@code DataSource} feeding both Flyway and
     * Liquibase) run concurrently even though that infrastructure bean stays on the
     * main thread. Only the barrier&rarr;dependent direction is relaxed, and a violated
     * assumption fails fast and falls back to sequential bootstrap.
     * @return whether shared-infrastructure consumers may be backgrounded
     * @see ParallelBootstrapSettings#isBackgroundSharedInfraConsumers()
     */
    boolean backgroundSharedInfraConsumers() default false;

    /**
     * An explicit set of bean names to treat as <em>completed-leaf barriers</em> &mdash;
     * terminal main-thread infrastructure singletons across which mainline-ness is not
     * propagated to consumers, so independent consumers of one such bean may overlap on
     * background threads. Defaults to an empty array.
     * <p>This is the user-named override of the structural barrier predicate behind
     * {@link #backgroundSharedInfraConsumers()}: it lets a shared singleton exposed only as a
     * co-located {@code @Bean} (such as a {@code DataSource}) act as a barrier so that, for
     * example, Flyway and Liquibase overlap. A named bean is honored only when it is acyclic
     * and a genuine leaf; supplying names here activates the relaxation for them even when
     * {@link #backgroundSharedInfraConsumers()} is {@code false}.
     * @return the explicit set of completed-leaf barrier bean names
     * @see ParallelBootstrapSettings#getBarrierBeanNames()
     */
    String[] barrierBeanNames() default {};

    /**
     * Groups of {@code @Bean} bean names asserted to be <em>mutually independent</em>
     * heavyweight beans that may be constructed concurrently. Defaults to an empty array.
     * <p>For every member of a group the planner drops the configuration&rarr;{@code @Bean}
     * co-location edge (like {@link #backgroundBeanNames()}), and additionally drops any sync
     * co-location edge between two members of the same group, so an asserted-independent pair
     * (such as {@code {entityManagerFactory, springSecurityFilterChain}} or
     * {@code {flyway, liquibase}}) overlaps rather than serializes. Forced
     * {@code depends-on}/factory edges and every other safety gate still apply, so a member
     * pinned to the main thread stays there and an invisible eager by-type pull fails fast.
     * Each {@link CoBackgroundGroup} holds one group's member names.
     * @return the declared co-background groups
     * @see ParallelBootstrapSettings#getCoBackgroundGroups()
     */
    CoBackgroundGroup[] coBackgroundGroups() default {};

    /**
     * A single <em>co-background group</em>: a set of {@code @Bean} bean names the user
     * asserts are mutually independent heavyweight beans that may be constructed concurrently.
     * Used as the element type of {@link EnableParallelBootstrap#coBackgroundGroups()}.
     *
     * @see EnableParallelBootstrap#coBackgroundGroups()
     * @see ParallelBootstrapSettings#getCoBackgroundGroups()
     */
    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface CoBackgroundGroup {

        /**
         * The mutually-independent {@code @Bean} bean names forming this group.
         * @return the group's bean names
         */
        String[] value();
    }
}
