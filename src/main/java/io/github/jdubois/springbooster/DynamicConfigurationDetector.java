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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * Classifies a configuration / factory bean as <em>dynamic</em> or <em>pure</em> for
 * the purpose of factory-method co-location (see
 * {@link BeanDependencyGraph#addFactoryColocationEdges}).
 *
 * <p>A {@code @Configuration} class can reach its own &mdash; and other configurations'
 * &mdash; {@code @Bean} beans <em>by type, on the main thread, through calls that no
 * static injection-point analysis can see</em>: a CGLIB self-invocation of another
 * {@code @Bean} method, a captured {@code ApplicationContext}/{@code BeanFactory} used
 * for a by-type lookup, or an {@code ObjectProvider}/{@code Lazy} resolved from a
 * framework callback the class implements. To stay safe, Spring Booster co-locates
 * such configurations' {@code @Bean} beans on the (always main-thread) configuration
 * class.
 *
 * <p>Most configurations, however, do <em>none</em> of this: a "pure" configuration
 * simply returns {@code new X(injectedParameters)} from its {@code @Bean} methods and
 * never performs a dynamic by-type lookup. Co-locating those beans needlessly keeps
 * them &mdash; and the subtrees rooted at them &mdash; on the main thread. This
 * detector recognises the <em>dynamic</em> ones so that only they are co-located,
 * letting pure configurations' beans be backgrounded.
 *
 * <p>A configuration is treated as <strong>dynamic</strong> (and therefore co-located)
 * if <em>any</em> of the following hold &mdash; the detector deliberately errs towards
 * {@code dynamic} so that an unrecognised shape stays safe:
 * <ol>
 * <li>it is a <em>full</em> {@code @Configuration} (CGLIB-enhanced for
 * {@code proxyBeanMethods = true}), which enables {@code @Bean} self-invocation;</li>
 * <li>its type implements {@link Aware} (it captures an {@code ApplicationContext},
 * {@code BeanFactory}, {@code Environment}, &hellip; through a framework callback);</li>
 * <li>its type implements a framework callback interface whose name ends with
 * {@code Configurer} or {@code Customizer} (for example {@code WebMvcConfigurer});</li>
 * <li>it declares a field, or an autowired constructor parameter, whose type is a
 * captured-context type or a deferred-lookup wrapper
 * ({@code ApplicationContext}/{@code BeanFactory}/{@code Environment}/&hellip;,
 * {@link ObjectProvider}/{@link ObjectFactory}/{@code Provider}), or that is annotated
 * {@code @Lazy}.</li>
 * </ol>
 *
 * <p>Otherwise the configuration is <strong>pure</strong>.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see BeanDependencyGraph#addFactoryColocationEdges
 */
final class DynamicConfigurationDetector {

    /**
     * Bean definition attribute set by {@code ConfigurationClassPostProcessor} to mark a
     * {@code @Configuration} bean as {@code "full"} (CGLIB-enhanced, {@code @Bean}
     * self-invocation enabled) or {@code "lite"}. Referenced by its literal name so the
     * detector does not depend on a package-private framework constant.
     */
    private static final String CONFIGURATION_CLASS_ATTRIBUTE =
            "org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass";

    /** Value of {@link #CONFIGURATION_CLASS_ATTRIBUTE} for a CGLIB-enhanced configuration. */
    private static final String CONFIGURATION_CLASS_FULL = "full";

    /** Fully-qualified types whose presence at an injection point signals captured, dynamic access. */
    private static final String[] CAPTURED_CONTEXT_TYPES = {
        "org.springframework.context.ApplicationContext",
        "org.springframework.beans.factory.BeanFactory",
        "org.springframework.core.env.Environment",
        "org.springframework.context.ApplicationEventPublisher",
        "org.springframework.core.io.ResourceLoader",
    };

    /** The {@code @Lazy} annotation, used to flag a deferred injection point. */
    private static final @Nullable Class<? extends java.lang.annotation.Annotation> LAZY_ANNOTATION = lazyAnnotation();

    private DynamicConfigurationDetector() {}

    /**
     * Determine whether the given factory bean is a <em>dynamic</em> configuration that
     * must keep its {@code @Bean} beans co-located on the main thread.
     * @param beanFactory the bean factory to introspect
     * @param factoryBeanName the name of the configuration / factory bean
     * @return {@code true} if the configuration is dynamic (co-locate), {@code false} if it
     * is pure (its {@code @Bean} beans may be backgrounded)
     */
    static boolean isDynamicConfiguration(ConfigurableListableBeanFactory beanFactory, String factoryBeanName) {
        return isDynamicConfiguration(beanFactory, factoryBeanName, false);
    }

    /**
     * Determine whether the given factory bean is a <em>dynamic</em> configuration that
     * must keep its {@code @Bean} beans co-located on the main thread, optionally applying
     * the experimental build-time {@linkplain BytecodeLookupDetector bytecode
     * lookup-detection} refinement.
     *
     * <p>The reflective classification is computed first. When
     * {@code bytecodeLookupDetection} is {@code false} (the default) that reflective result
     * is returned unchanged. When it is {@code true}, a configuration the reflective pass
     * flagged as <em>dynamic</em> is re-examined at the bytecode level: if the scan
     * <em>proves</em> the configuration performs no dynamic bean lookup, it is downgraded to
     * <em>pure</em>. An inconclusive scan leaves the conservative <em>dynamic</em>
     * classification in place, so the refinement only ever relaxes a false positive and is
     * always safe.
     * @param beanFactory the bean factory to introspect
     * @param factoryBeanName the name of the configuration / factory bean
     * @param bytecodeLookupDetection whether to apply the bytecode lookup-detection refinement
     * @return {@code true} if the configuration is dynamic (co-locate), {@code false} if it
     * is pure (its {@code @Bean} beans may be backgrounded)
     */
    static boolean isDynamicConfiguration(
            ConfigurableListableBeanFactory beanFactory, String factoryBeanName, boolean bytecodeLookupDetection) {
        boolean reflectiveDynamic = isReflectivelyDynamic(beanFactory, factoryBeanName);
        if (!reflectiveDynamic || !bytecodeLookupDetection) {
            return reflectiveDynamic;
        }
        Class<?> type = safeGetType(beanFactory, factoryBeanName);
        if (type == null) {
            // Cannot introspect the configuration: stay safe and co-locate.
            return true;
        }
        try {
            // The bytecode scan only ever downgrades a reflective false positive to pure;
            // an inconclusive scan keeps the conservative dynamic classification.
            return BytecodeLookupDetector.detect(type) != BytecodeLookupDetector.Result.PURE;
        } catch (Throwable ex) {
            return true;
        }
    }

    private static boolean isReflectivelyDynamic(ConfigurableListableBeanFactory beanFactory, String factoryBeanName) {
        try {
            BeanDefinition bd = safeGetMergedBeanDefinition(beanFactory, factoryBeanName);
            if (bd != null && CONFIGURATION_CLASS_FULL.equals(bd.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE))) {
                return true;
            }
            Class<?> type = safeGetType(beanFactory, factoryBeanName);
            if (type == null) {
                // Cannot introspect the configuration: stay safe and co-locate.
                return true;
            }
            if (isCglibEnhanced(type) || Aware.class.isAssignableFrom(type)) {
                return true;
            }
            if (implementsCallbackInterface(type)) {
                return true;
            }
            return capturesContextOrDeferredLookup(type);
        } catch (Throwable ex) {
            // Best-effort analysis: any failure falls back to the safe (dynamic) classification.
            return true;
        }
    }

    private static boolean isCglibEnhanced(Class<?> type) {
        return type.getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR);
    }

    private static boolean implementsCallbackInterface(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                String simpleName = iface.getSimpleName();
                if (simpleName.endsWith("Configurer") || simpleName.endsWith("Customizer")) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean capturesContextOrDeferredLookup(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (isDynamicAccessType(field.getType()) || isLazy(field)) {
                    return true;
                }
            }
            for (Constructor<?> constructor : current.getDeclaredConstructors()) {
                for (Parameter parameter : constructor.getParameters()) {
                    if (isDynamicAccessType(parameter.getType()) || isLazy(parameter)) {
                        return true;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean isDynamicAccessType(Class<?> type) {
        if (ObjectProvider.class.isAssignableFrom(type) || ObjectFactory.class.isAssignableFrom(type)) {
            return true;
        }
        for (String contextType : CAPTURED_CONTEXT_TYPES) {
            if (isAssignableTo(type, contextType)) {
                return true;
            }
        }
        return isAssignableTo(type, "jakarta.inject.Provider") || isAssignableTo(type, "javax.inject.Provider");
    }

    private static boolean isLazy(java.lang.reflect.AnnotatedElement element) {
        return LAZY_ANNOTATION != null && AnnotatedElementUtils.hasAnnotation(element, LAZY_ANNOTATION);
    }

    private static boolean isAssignableTo(Class<?> type, String targetClassName) {
        try {
            Class<?> target = ClassUtils.forName(targetClassName, DynamicConfigurationDetector.class.getClassLoader());
            return target.isAssignableFrom(type);
        } catch (Throwable ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends java.lang.annotation.Annotation> lazyAnnotation() {
        try {
            Class<?> type = ClassUtils.forName(
                    "org.springframework.context.annotation.Lazy", DynamicConfigurationDetector.class.getClassLoader());
            if (type.isAnnotation()) {
                return (Class<? extends java.lang.annotation.Annotation>) type;
            }
        } catch (Throwable ex) {
            // Lazy not on the classpath; ignore.
        }
        return null;
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
}
