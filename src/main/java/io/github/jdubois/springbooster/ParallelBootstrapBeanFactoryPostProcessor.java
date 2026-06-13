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
import java.util.List;
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
            List<String> candidates = findPreMarkedCandidates(beanFactory);
            if (candidates.isEmpty()) {
                candidates = resolveCandidatePlan(beanFactory);
            } else {
                logger.debug("Using pre-marked Spring Booster background-init beans");
            }
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

    List<String> planCandidates(ConfigurableListableBeanFactory beanFactory) {
        return new ParallelBootstrapPlanner(this.settings)
                .createPlan(beanFactory)
                .getCandidateBeanNames();
    }

    private List<String> resolveCandidatePlan(ConfigurableListableBeanFactory beanFactory) {
        ParallelBootstrapPlanner planner = new ParallelBootstrapPlanner(this.settings);
        if (this.settings.isBuildTimePlanningEnabled()) {
            ParallelBootstrapPlan generatedPlan = loadGeneratedPlan(beanFactory);
            if (generatedPlan != null) {
                if (planner.isPlanCompatible(beanFactory, generatedPlan)) {
                    logger.debug("Using generated Spring Booster bootstrap plan");
                    return generatedPlan.getCandidateBeanNames();
                }
                logger.info(
                        "Generated Spring Booster bootstrap plan fingerprint does not match current settings or bean definitions; ignoring it");
            }
            if (this.settings.isGeneratedPlanRequired()) {
                logger.warn(
                        "Generated Spring Booster bootstrap plan required but not found or incompatible; using sequential bootstrap."
                                + " Ensure AOT processing completed successfully or set generatedPlanRequired to false");
                return List.of();
            }
        }
        if (!this.settings.isRuntimePlanningEnabled()) {
            logger.debug("Runtime bootstrap planning disabled; using sequential bootstrap");
            return List.of();
        }
        return planner.createPlan(beanFactory).getCandidateBeanNames();
    }

    private @Nullable ParallelBootstrapPlan loadGeneratedPlan(ConfigurableListableBeanFactory beanFactory) {
        ParallelBootstrapPlan plan = loadGeneratedPlan(beanFactory.getBeanClassLoader());
        if (plan != null) {
            return plan;
        }
        plan = loadGeneratedPlan(Thread.currentThread().getContextClassLoader());
        if (plan != null) {
            return plan;
        }
        return loadGeneratedPlan(getClass().getClassLoader());
    }

    private @Nullable ParallelBootstrapPlan loadGeneratedPlan(@Nullable ClassLoader classLoader) {
        if (classLoader == null) {
            return null;
        }
        try (java.io.InputStream inputStream =
                classLoader.getResourceAsStream(ParallelBootstrapPlan.RESOURCE_LOCATION)) {
            if (inputStream == null) {
                return null;
            }
            byte[] content = inputStream.readAllBytes();
            return ParallelBootstrapPlan.fromResourceContent(
                    new String(content, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            logger.info("Failed to read generated Spring Booster bootstrap plan; ignoring it", ex);
            return null;
        }
    }

    private List<String> findPreMarkedCandidates(ConfigurableListableBeanFactory beanFactory) {
        List<String> candidates = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition beanDefinition = safeGetBeanDefinition(beanFactory, beanName);
            if (beanDefinition instanceof AbstractBeanDefinition abstractBeanDefinition
                    && abstractBeanDefinition.isBackgroundInit()
                    && !beanDefinition.isAbstract()
                    && beanDefinition.isSingleton()
                    && !beanDefinition.isLazyInit()) {
                candidates.add(beanName);
            }
        }
        return candidates;
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
