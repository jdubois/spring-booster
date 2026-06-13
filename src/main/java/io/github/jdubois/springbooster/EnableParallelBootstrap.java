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
}
