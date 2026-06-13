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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * Experimental build-time <em>bytecode lookup-detector</em> (prototype).
 *
 * <p>The reflective {@link DynamicConfigurationDetector} is deliberately conservative:
 * it classifies a {@code @Configuration} class as <em>dynamic</em> from coarse structural
 * signals &mdash; any <em>full</em> (CGLIB-enhanced) {@code @Configuration}, any class that
 * implements an {@code Aware}/{@code *Configurer}/{@code *Customizer} callback, or any class
 * that merely <em>declares</em> a field/parameter of a captured-context or deferred-lookup
 * type. Those signals only say the configuration <em>could</em> perform an invisible by-type
 * lookup; many of them never actually do, and co-locating their {@code @Bean} beans needlessly
 * keeps the whole context on the main thread.
 *
 * <p>This detector inspects the actual bytecode of the configuration class (and its
 * superclasses) and reports whether it <em>really</em> performs a dynamic bean lookup:
 * <ul>
 * <li>a {@code BeanFactory}/{@code ApplicationContext} {@code getBean*} /
 * {@code getBeanProvider} / {@code getBeansOfType} / {@code getBeansWithAnnotation} call;</li>
 * <li>a dereference of an {@code ObjectProvider}/{@code ObjectFactory}/{@code Provider}
 * handle ({@code getObject}, {@code getIfAvailable}, {@code getIfUnique}, {@code stream},
 * {@code orderedStream}, {@code iterator}, {@code forEach}, {@code get});</li>
 * <li>a CGLIB {@code @Bean} self-invocation &mdash; a call to another {@code @Bean} method
 * declared on the same configuration class, which the full-configuration proxy intercepts to
 * return the managed singleton.</li>
 * </ul>
 *
 * <p>The result is deliberately three-valued. A scan only yields {@link Result#PURE} when the
 * entire analyzable class hierarchy could be read and contained none of the above; any I/O or
 * parsing failure yields {@link Result#INCONCLUSIVE}, which callers must treat as
 * <em>dynamic</em>. The refinement therefore only ever <em>relaxes</em> a conservatively-dynamic
 * classification when it has positive proof of purity, and never makes a pure configuration
 * look dynamic.
 *
 * <p>Because parsing class files is comparatively expensive, this analysis is intended for
 * build-time (Spring AOT) planning, off the startup critical path.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see DynamicConfigurationDetector
 * @see ParallelBootstrapSettings#isBytecodeLookupDetection()
 */
final class BytecodeLookupDetector {

    /** The outcome of a bytecode scan. */
    enum Result {

        /** The class provably performs no dynamic bean lookup. */
        PURE,

        /** The class performs (at least one) dynamic bean lookup. */
        DYNAMIC,

        /** The class could not be fully analyzed; callers must treat this as dynamic. */
        INCONCLUSIVE
    }

    /** Method names on a bean factory / application context that denote a dynamic lookup. */
    private static final Set<String> LOOKUP_METHOD_NAMES = Set.of(
            "getBean",
            "getBeanProvider",
            "getBeansOfType",
            "getBeanNamesForType",
            "getBeansWithAnnotation",
            "getBeanNamesForAnnotation");

    /** Method names on a deferred-lookup handle whose invocation resolves a bean. */
    private static final Set<String> PROVIDER_METHOD_NAMES = Set.of(
            "getObject", "getIfAvailable", "getIfUnique", "stream", "orderedStream", "iterator", "forEach", "get");

    /** Internal-name fragments of owner types that expose a dynamic by-type lookup. */
    private static final String[] LOOKUP_OWNER_FRAGMENTS = {
        "org/springframework/beans/factory/BeanFactory",
        "org/springframework/beans/factory/ListableBeanFactory",
        "org/springframework/beans/factory/HierarchicalBeanFactory",
        "org/springframework/beans/factory/config/ConfigurableBeanFactory",
        "org/springframework/beans/factory/config/ConfigurableListableBeanFactory",
        "org/springframework/context/ApplicationContext",
        "org/springframework/context/ConfigurableApplicationContext",
    };

    /** Internal names of deferred-lookup handle owner types. */
    private static final String[] PROVIDER_OWNERS = {
        "org/springframework/beans/factory/ObjectProvider",
        "org/springframework/beans/factory/ObjectFactory",
        "jakarta/inject/Provider",
        "javax/inject/Provider",
    };

    private BytecodeLookupDetector() {}

    /**
     * Scan the given configuration type and report whether it performs a dynamic bean
     * lookup.
     * @param configType the configuration class (a CGLIB-enhanced type is unwrapped to its
     * user class before scanning)
     * @return the three-valued scan result
     */
    static Result detect(Class<?> configType) {
        Class<?> userClass = ClassUtils.getUserClass(configType);
        Set<String> beanMethodNames = collectBeanMethodNames(userClass);
        Class<?> current = userClass;
        while (current != null && current != Object.class) {
            Result result = scanClass(current, beanMethodNames);
            if (result != Result.PURE) {
                // DYNAMIC short-circuits; INCONCLUSIVE means we cannot prove purity.
                return result;
            }
            current = current.getSuperclass();
        }
        return Result.PURE;
    }

    private static Set<String> collectBeanMethodNames(Class<?> userClass) {
        Set<String> names = new HashSet<>();
        Class<?> current = userClass;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (isBeanMethod(method)) {
                    names.add(method.getName());
                }
            }
            current = current.getSuperclass();
        }
        return names;
    }

    private static boolean isBeanMethod(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, org.springframework.context.annotation.Bean.class);
    }

    private static Result scanClass(Class<?> type, Set<String> beanMethodNames) {
        String resource = ClassUtils.convertClassNameToResourcePath(type.getName()) + ".class";
        ClassLoader classLoader = type.getClassLoader();
        if (classLoader == null) {
            classLoader = BytecodeLookupDetector.class.getClassLoader();
        }
        try (InputStream inputStream = classLoader.getResourceAsStream(resource)) {
            if (inputStream == null) {
                return Result.INCONCLUSIVE;
            }
            ClassReader reader = new ClassReader(inputStream.readAllBytes());
            String ownerInternalName = reader.getClassName();
            LookupScanningClassVisitor visitor = new LookupScanningClassVisitor(ownerInternalName, beanMethodNames);
            reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return visitor.dynamicLookupFound ? Result.DYNAMIC : Result.PURE;
        } catch (IOException | RuntimeException ex) {
            return Result.INCONCLUSIVE;
        }
    }

    private static boolean ownerMatches(String owner, String[] fragments) {
        for (String fragment : fragments) {
            if (owner.equals(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static final class LookupScanningClassVisitor extends ClassVisitor {

        private final String ownerInternalName;

        private final Set<String> beanMethodNames;

        private boolean dynamicLookupFound;

        private LookupScanningClassVisitor(String ownerInternalName, Set<String> beanMethodNames) {
            super(SpringAsmInfo.ASM_VERSION);
            this.ownerInternalName = ownerInternalName;
            this.beanMethodNames = beanMethodNames;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            return new LookupScanningMethodVisitor();
        }

        private final class LookupScanningMethodVisitor extends MethodVisitor {

            private LookupScanningMethodVisitor() {
                super(SpringAsmInfo.ASM_VERSION);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if (isDynamicLookup(owner, name)) {
                    LookupScanningClassVisitor.this.dynamicLookupFound = true;
                }
            }

            private boolean isDynamicLookup(String owner, String name) {
                // A by-type lookup on a captured bean factory / application context.
                if (LOOKUP_METHOD_NAMES.contains(name) && ownerMatches(owner, LOOKUP_OWNER_FRAGMENTS)) {
                    return true;
                }
                // A dereference of a deferred-lookup handle (ObjectProvider/ObjectFactory/Provider).
                if (PROVIDER_METHOD_NAMES.contains(name) && ownerMatches(owner, PROVIDER_OWNERS)) {
                    return true;
                }
                // A CGLIB @Bean self-invocation: a call to another @Bean method on this class,
                // which the full-configuration proxy intercepts to return the managed singleton.
                return owner.equals(LookupScanningClassVisitor.this.ownerInternalName)
                        && LookupScanningClassVisitor.this.beanMethodNames.contains(name);
            }
        }
    }
}
