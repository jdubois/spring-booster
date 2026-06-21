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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

/**
 * Opt-in JPA integration (enabled by
 * {@link ParallelBootstrapSettings#isBackgroundEntityManagerFactory() backgroundEntityManagerFactory})
 * that offloads the expensive native {@code EntityManagerFactory} build to a background thread by
 * wiring the JPA provider's own <em>deferred bootstrap</em> mechanism to the library's bootstrap
 * executor.
 *
 * <p>On a persistence application the dominant cost of context refresh is the
 * {@code EntityManagerFactory}: Hibernate's metamodel construction, entity scanning and schema
 * validation. That bean is a {@code FactoryBean} which the generic planner force-instantiates on
 * the main thread (it can never be a background candidate without breaking the connectivity-safe
 * contract). Spring already solves this without backgrounding the bean: when an
 * {@code AbstractEntityManagerFactoryBean} is given an {@link AsyncTaskExecutor} through
 * {@code setBootstrapExecutor(...)}, it builds the native {@code EntityManagerFactory} on that
 * executor and returns a proxy immediately, so the metamodel build overlaps the rest of refresh.
 * The first real use of the {@code EntityManagerFactory} blocks until the build completes.
 *
 * <p>This helper detects every {@code AbstractEntityManagerFactoryBean} bean definition (matched by
 * type, resolved reflectively so the library keeps depending only on {@code spring-context} &mdash;
 * an absent {@code spring-orm} simply means no match) and adds a {@code bootstrapExecutor} property
 * value referencing the library's executor (wrapped as an {@link AsyncTaskExecutor}). It only ever
 * <em>adds</em> the property when the user has not already configured one, and it never marks the
 * {@code EntityManagerFactory} bean itself for background initialization &mdash; the bean stays on
 * the main thread, so the connectivity-safe guarantees of the planner are completely untouched
 * (design goal #1).
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapSettings#isBackgroundEntityManagerFactory()
 */
final class JpaBackgroundBootstrap {

    private static final Log logger = LogFactory.getLog(JpaBackgroundBootstrap.class);

    /**
     * Fully-qualified name of the JPA {@code FactoryBean} base type whose deferred bootstrap is
     * wired here. Referenced by name and resolved reflectively, so {@code spring-orm} need not be
     * on the classpath of the library itself.
     */
    static final String ENTITY_MANAGER_FACTORY_BEAN_TYPE = "org.springframework.orm.jpa.AbstractEntityManagerFactoryBean";

    /**
     * The {@code AbstractEntityManagerFactoryBean} JavaBean property that receives the deferred
     * bootstrap executor.
     */
    static final String BOOTSTRAP_EXECUTOR_PROPERTY = "bootstrapExecutor";

    private JpaBackgroundBootstrap() {}

    /**
     * Wire the deferred bootstrap of every detected {@code EntityManagerFactory} bean to the given
     * executor, using the production target type ({@link #ENTITY_MANAGER_FACTORY_BEAN_TYPE}).
     * @param beanFactory the bean factory whose JPA bean definitions are augmented
     * @param executor the library's bootstrap executor to reuse for the deferred build
     * @return the number of {@code EntityManagerFactory} beans wired
     */
    static int apply(ConfigurableListableBeanFactory beanFactory, Executor executor) {
        return apply(beanFactory, executor, List.of(ENTITY_MANAGER_FACTORY_BEAN_TYPE));
    }

    /**
     * Wire the deferred bootstrap of every bean whose resolved type is assignable to one of
     * {@code targetTypeNames}. The {@code targetTypeNames} seam keeps the helper unit-testable
     * without a {@code spring-orm} dependency.
     * @param beanFactory the bean factory whose JPA bean definitions are augmented
     * @param executor the library's bootstrap executor to reuse for the deferred build
     * @param targetTypeNames the fully-qualified {@code EntityManagerFactory} {@code FactoryBean}
     * type names to match by assignability
     * @return the number of beans wired
     */
    static int apply(ConfigurableListableBeanFactory beanFactory, Executor executor, List<String> targetTypeNames) {
        ClassLoader classLoader = resolveClassLoader(beanFactory);
        List<Class<?>> targets = loadTargets(targetTypeNames, classLoader);
        if (targets.isEmpty()) {
            // spring-orm (or the configured target type) is not on the classpath: nothing to do.
            return 0;
        }
        AsyncTaskExecutor asyncExecutor = asAsyncTaskExecutor(executor);
        int wired = 0;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> factoryBeanType = resolveFactoryBeanType(beanFactory, beanName);
            if (factoryBeanType == null || !isAssignableToAny(factoryBeanType, targets)) {
                continue;
            }
            if (setBootstrapExecutorProperty(beanFactory, beanName, asyncExecutor)) {
                wired++;
                if (logger.isDebugEnabled()) {
                    logger.debug("Wired EntityManagerFactory bean '" + beanName
                            + "' to build its native EntityManagerFactory on the background bootstrap executor");
                }
            }
        }
        return wired;
    }

    /**
     * Resolve the concrete type of the (potential) {@code FactoryBean} backing the given bean,
     * without instantiating anything: for a {@code @Bean} factory-method bean this is the factory
     * method's declared return type (for example {@code LocalContainerEntityManagerFactoryBean}),
     * and for a plain definition it is the bean class.
     */
    private static @Nullable Class<?> resolveFactoryBeanType(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        BeanDefinition merged = safeGetMergedBeanDefinition(beanFactory, beanName);
        if (merged == null) {
            return null;
        }
        ResolvableType resolvableType = merged.getResolvableType();
        return (resolvableType != ResolvableType.NONE) ? resolvableType.resolve() : null;
    }

    /**
     * Add the {@code bootstrapExecutor} property to the bean definition (raw and merged) so the JPA
     * provider builds the native {@code EntityManagerFactory} on the background executor. Returns
     * {@code false} (and leaves the definition untouched) when a {@code bootstrapExecutor} is
     * already configured, honoring an explicit user choice.
     */
    private static boolean setBootstrapExecutorProperty(
            ConfigurableListableBeanFactory beanFactory, String beanName, AsyncTaskExecutor asyncExecutor) {
        BeanDefinition raw = safeGetBeanDefinition(beanFactory, beanName);
        if (raw == null) {
            return false;
        }
        if (raw.getPropertyValues().contains(BOOTSTRAP_EXECUTOR_PROPERTY)) {
            return false;
        }
        raw.getPropertyValues().add(BOOTSTRAP_EXECUTOR_PROPERTY, asyncExecutor);
        // Also update the cached merged definition, which is what the bean-creation phase reads,
        // in case it has already been merged and cached (mirrors markForBackgroundInit).
        BeanDefinition merged = safeGetMergedBeanDefinition(beanFactory, beanName);
        if (merged != null && merged != raw && !merged.getPropertyValues().contains(BOOTSTRAP_EXECUTOR_PROPERTY)) {
            merged.getPropertyValues().add(BOOTSTRAP_EXECUTOR_PROPERTY, asyncExecutor);
        }
        return true;
    }

    /**
     * Adapt the plain {@link Executor} to the {@link AsyncTaskExecutor} the JPA
     * {@code setBootstrapExecutor} setter expects, passing it through unchanged when it already is
     * one.
     */
    private static AsyncTaskExecutor asAsyncTaskExecutor(Executor executor) {
        return (executor instanceof AsyncTaskExecutor asyncTaskExecutor)
                ? asyncTaskExecutor
                : new TaskExecutorAdapter(executor);
    }

    private static List<Class<?>> loadTargets(List<String> targetTypeNames, ClassLoader classLoader) {
        List<Class<?>> targets = new ArrayList<>();
        for (String targetTypeName : targetTypeNames) {
            Class<?> target = loadClass(targetTypeName, classLoader);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    private static boolean isAssignableToAny(Class<?> type, List<Class<?>> targets) {
        for (Class<?> target : targets) {
            if (target.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static ClassLoader resolveClassLoader(ConfigurableListableBeanFactory beanFactory) {
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return (contextClassLoader != null) ? contextClassLoader : JpaBackgroundBootstrap.class.getClassLoader();
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
}
