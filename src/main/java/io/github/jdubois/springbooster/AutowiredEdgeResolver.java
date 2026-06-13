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

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * Resolves the <em>by-type</em> dependency edges of a single bean definition that
 * the purely declaration-based analysis of {@link BeanDependencyGraph} cannot see.
 *
 * <p>It introspects, reflectively and without ever instantiating a bean, the
 * injection points through which the framework would wire the bean by type:
 * <ul>
 * <li>{@code @Bean} factory-method parameters,</li>
 * <li>the autowired constructor parameters of a component bean,</li>
 * <li>fields and methods annotated with {@link Autowired @Autowired} (and, when
 * present on the classpath, {@code jakarta.inject.Inject} / {@code @Resource}).</li>
 * </ul>
 *
 * <p>Wrapper types are unwrapped to their target element type so that lazy and
 * multi-valued lookups are modelled too: {@link ObjectProvider}, {@link ObjectFactory},
 * {@code jakarta/javax.inject.Provider}, {@link Optional}, arrays, {@link Collection}s
 * and {@link Map} values. Candidate bean names are then resolved through
 * {@link ConfigurableListableBeanFactory#getBeanNamesForType(ResolvableType, boolean, boolean)}
 * with eager initialization disabled, so no {@code FactoryBean} or bean instance is
 * ever created during analysis.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see BeanDependencyGraph
 */
final class AutowiredEdgeResolver {

    /** Annotations that mark an injection point we should resolve by type. */
    private static final List<Class<? extends Annotation>> INJECT_ANNOTATIONS = injectAnnotations();

    /** Wrapper types whose single generic argument is the real injection target. */
    private static final Set<Class<?>> WRAPPER_TYPES = wrapperTypes();

    /**
     * Wrapper types that defer resolution of their target to <em>after</em> the
     * dependent bean is constructed ({@link ObjectProvider}, {@link ObjectFactory},
     * {@code jakarta/javax.inject.Provider}). Unlike {@link Optional}, these never force
     * the target to be created while the dependent is being wired, so the edges they
     * produce can be classified as <em>deferred</em>.
     */
    private static final Set<Class<?>> DEFERRED_WRAPPER_TYPES = deferredWrapperTypes();

    /** The {@code @Lazy} annotation, which likewise defers resolution to a proxy. */
    private static final @Nullable Class<? extends Annotation> LAZY_ANNOTATION = lazyAnnotation();

    private AutowiredEdgeResolver() {}

    /**
     * Collect the by-type dependency edges of the given bean, partitioning them into
     * <em>sync</em> and <em>deferred</em> edges.
     *
     * <p>A <em>deferred</em> edge is one whose target is reached only through an
     * {@link ObjectProvider}/{@link ObjectFactory}/{@code Provider} wrapper or a
     * {@code @Lazy} injection point, i.e. it is not resolved while the dependent bean is
     * being constructed. When {@code separateDeferred} is {@code false} every edge is
     * added to {@code syncEdges} (preserving the original, fully-conservative behaviour);
     * when {@code true}, deferred edges are routed to {@code deferredEdges} instead so the
     * planner can let them cross the background/mainline boundary.
     *
     * <p>Self-references are never added.
     * @param beanFactory the bean factory to introspect
     * @param beanName the bean whose injection points are analysed
     * @param mbd the merged bean definition of {@code beanName}
     * @param syncEdges the set to populate with resolved sync dependency bean names
     * @param deferredEdges the set to populate with resolved deferred dependency bean names
     * @param separateDeferred whether to route deferred edges to {@code deferredEdges}
     */
    static void collect(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            BeanDefinition mbd,
            Set<String> syncEdges,
            Set<String> deferredEdges,
            boolean separateDeferred) {
        try {
            Class<?> beanType = safeGetType(beanFactory, beanName);
            if (isFactoryMethodBean(mbd)) {
                for (Method factoryMethod : findFactoryMethods(beanFactory, mbd)) {
                    addExecutableEdges(
                            beanFactory, factoryMethod, beanName, syncEdges, deferredEdges, separateDeferred);
                }
            } else if (beanType != null) {
                Constructor<?> constructor = chooseAutowireConstructor(beanType);
                if (constructor != null) {
                    addExecutableEdges(beanFactory, constructor, beanName, syncEdges, deferredEdges, separateDeferred);
                }
            }
            if (beanType != null) {
                addMemberInjectionEdges(beanFactory, beanType, beanName, syncEdges, deferredEdges, separateDeferred);
            }
        } catch (Throwable ex) {
            // Best-effort analysis: never let introspection break graph construction.
        }
    }

    private static boolean isFactoryMethodBean(BeanDefinition mbd) {
        return mbd.getFactoryBeanName() != null || mbd.getFactoryMethodName() != null;
    }

    private static List<Method> findFactoryMethods(ConfigurableListableBeanFactory beanFactory, BeanDefinition mbd) {
        if (mbd instanceof RootBeanDefinition rbd) {
            Method resolved = rbd.getResolvedFactoryMethod();
            if (resolved != null) {
                return List.of(resolved);
            }
        }
        String factoryMethodName = mbd.getFactoryMethodName();
        Class<?> factoryClass = resolveFactoryClass(beanFactory, mbd);
        if (factoryMethodName == null || factoryClass == null) {
            return List.of();
        }
        List<Method> methods = new ArrayList<>();
        Class<?> current = factoryClass;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName) && method.getParameterCount() > 0) {
                    methods.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static @Nullable Class<?> resolveFactoryClass(
            ConfigurableListableBeanFactory beanFactory, BeanDefinition mbd) {
        String factoryBeanName = mbd.getFactoryBeanName();
        if (factoryBeanName != null) {
            return safeGetType(beanFactory, factoryBeanName);
        }
        if (mbd instanceof AbstractBeanDefinition abd && abd.hasBeanClass()) {
            return abd.getBeanClass();
        }
        return null;
    }

    private static @Nullable Constructor<?> chooseAutowireConstructor(Class<?> beanType) {
        if (beanType.isInterface() || beanType.isPrimitive() || beanType.isArray()) {
            return null;
        }
        Constructor<?>[] constructors;
        try {
            constructors = beanType.getDeclaredConstructors();
        } catch (Throwable ex) {
            return null;
        }
        for (Constructor<?> constructor : constructors) {
            if (hasInjectAnnotation(constructor)) {
                return constructor;
            }
        }
        // With no annotated constructor, Spring autowires the single declared
        // constructor (if any); ambiguous multi-constructor classes fall back to the
        // default constructor, so we model no constructor edges for them.
        if (constructors.length == 1 && constructors[0].getParameterCount() > 0) {
            return constructors[0];
        }
        return null;
    }

    private static void addMemberInjectionEdges(
            ConfigurableListableBeanFactory beanFactory,
            Class<?> beanType,
            String beanName,
            Set<String> syncEdges,
            Set<String> deferredEdges,
            boolean separateDeferred) {
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (hasInjectAnnotation(field) && !hasValueAnnotation(field)) {
                        addCandidatesForType(
                                beanFactory,
                                ResolvableType.forField(field),
                                beanName,
                                syncEdges,
                                deferredEdges,
                                separateDeferred,
                                isLazy(field));
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    if (hasInjectAnnotation(method)) {
                        addExecutableEdges(beanFactory, method, beanName, syncEdges, deferredEdges, separateDeferred);
                    }
                }
            } catch (Throwable ex) {
                // Skip members we cannot introspect on this class.
            }
            current = current.getSuperclass();
        }
    }

    private static void addExecutableEdges(
            ConfigurableListableBeanFactory beanFactory,
            Executable executable,
            String beanName,
            Set<String> syncEdges,
            Set<String> deferredEdges,
            boolean separateDeferred) {
        for (int i = 0; i < executable.getParameterCount(); i++) {
            if (hasValueAnnotation(executable.getParameters()[i])) {
                continue;
            }
            MethodParameter parameter = forExecutable(executable, i);
            if (parameter != null) {
                addCandidatesForType(
                        beanFactory,
                        ResolvableType.forMethodParameter(parameter),
                        beanName,
                        syncEdges,
                        deferredEdges,
                        separateDeferred,
                        isLazy(executable.getParameters()[i]));
            }
        }
    }

    private static @Nullable MethodParameter forExecutable(Executable executable, int index) {
        if (executable instanceof Method method) {
            return new MethodParameter(method, index);
        }
        if (executable instanceof Constructor<?> constructor) {
            return new MethodParameter(constructor, index);
        }
        return null;
    }

    private static void addCandidatesForType(
            ConfigurableListableBeanFactory beanFactory,
            ResolvableType type,
            String beanName,
            Set<String> syncEdges,
            Set<String> deferredEdges,
            boolean separateDeferred,
            boolean deferred) {
        if (type == ResolvableType.NONE) {
            return;
        }
        Class<?> resolved = type.resolve();
        if (resolved == null) {
            return;
        }
        if (WRAPPER_TYPES.contains(resolved)) {
            boolean nextDeferred = deferred || DEFERRED_WRAPPER_TYPES.contains(resolved);
            addCandidatesForType(
                    beanFactory,
                    type.getGeneric(0),
                    beanName,
                    syncEdges,
                    deferredEdges,
                    separateDeferred,
                    nextDeferred);
            return;
        }
        if (resolved.isArray()) {
            addCandidatesForType(
                    beanFactory,
                    type.getComponentType(),
                    beanName,
                    syncEdges,
                    deferredEdges,
                    separateDeferred,
                    deferred);
            return;
        }
        if (Collection.class.isAssignableFrom(resolved)) {
            addCandidatesForType(
                    beanFactory,
                    type.asCollection().getGeneric(0),
                    beanName,
                    syncEdges,
                    deferredEdges,
                    separateDeferred,
                    deferred);
            return;
        }
        if (Map.class.isAssignableFrom(resolved)) {
            addCandidatesForType(
                    beanFactory,
                    type.asMap().getGeneric(1),
                    beanName,
                    syncEdges,
                    deferredEdges,
                    separateDeferred,
                    deferred);
            return;
        }
        if (isUnresolvableTargetType(resolved)) {
            return;
        }
        Set<String> sink = (separateDeferred && deferred) ? deferredEdges : syncEdges;
        try {
            for (String candidate : beanFactory.getBeanNamesForType(type, true, false)) {
                if (!candidate.equals(beanName)) {
                    sink.add(candidate);
                }
            }
        } catch (Throwable ex) {
            // Type matching failed for this injection point; skip it.
        }
    }

    private static boolean isUnresolvableTargetType(Class<?> type) {
        return type == Object.class || BeanUtils.isSimpleValueType(type);
    }

    private static boolean hasInjectAnnotation(java.lang.reflect.AnnotatedElement element) {
        for (Class<? extends Annotation> annotation : INJECT_ANNOTATIONS) {
            if (AnnotatedElementUtils.hasAnnotation(element, annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValueAnnotation(java.lang.reflect.AnnotatedElement element) {
        return AnnotatedElementUtils.hasAnnotation(element, Value.class);
    }

    private static boolean isLazy(java.lang.reflect.AnnotatedElement element) {
        return LAZY_ANNOTATION != null && AnnotatedElementUtils.hasAnnotation(element, LAZY_ANNOTATION);
    }

    private static @Nullable Class<?> safeGetType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<Class<? extends Annotation>> injectAnnotations() {
        List<Class<? extends Annotation>> annotations = new ArrayList<>();
        annotations.add(Autowired.class);
        addOptionalAnnotation(annotations, "jakarta.inject.Inject");
        addOptionalAnnotation(annotations, "javax.inject.Inject");
        addOptionalAnnotation(annotations, "jakarta.annotation.Resource");
        addOptionalAnnotation(annotations, "javax.annotation.Resource");
        return List.copyOf(annotations);
    }

    @SuppressWarnings("unchecked")
    private static void addOptionalAnnotation(List<Class<? extends Annotation>> target, String className) {
        try {
            Class<?> type = ClassUtils.forName(className, AutowiredEdgeResolver.class.getClassLoader());
            if (type.isAnnotation()) {
                target.add((Class<? extends Annotation>) type);
            }
        } catch (Throwable ex) {
            // Annotation not on the classpath; ignore.
        }
    }

    private static Set<Class<?>> wrapperTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        types.add(ObjectProvider.class);
        types.add(ObjectFactory.class);
        types.add(Optional.class);
        addOptionalClass(types, "jakarta.inject.Provider");
        addOptionalClass(types, "javax.inject.Provider");
        return Set.copyOf(types);
    }

    private static Set<Class<?>> deferredWrapperTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        types.add(ObjectProvider.class);
        types.add(ObjectFactory.class);
        addOptionalClass(types, "jakarta.inject.Provider");
        addOptionalClass(types, "javax.inject.Provider");
        return Set.copyOf(types);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Annotation> lazyAnnotation() {
        try {
            Class<?> type = ClassUtils.forName(
                    "org.springframework.context.annotation.Lazy", AutowiredEdgeResolver.class.getClassLoader());
            if (type.isAnnotation()) {
                return (Class<? extends Annotation>) type;
            }
        } catch (Throwable ex) {
            // Lazy not on the classpath; ignore.
        }
        return null;
    }

    private static void addOptionalClass(Set<Class<?>> target, String className) {
        try {
            target.add(ClassUtils.forName(className, AutowiredEdgeResolver.class.getClassLoader()));
        } catch (Throwable ex) {
            // Type not on the classpath; ignore.
        }
    }
}
