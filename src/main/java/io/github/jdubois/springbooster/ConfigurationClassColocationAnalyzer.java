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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ClassUtils;

/**
 * Static bytecode analysis (via Spring's bundled ASM) that decides whether the blanket
 * {@code @Bean} <em>co-location</em> rule of {@link BeanDependencyGraph#addFactoryColocationEdges}
 * can be safely relaxed for a given bean factory.
 *
 * <p>By default Spring Booster keeps every {@code @Bean} factory-method bean on its
 * configuration class's (always main-thread) thread, because configuration classes are
 * the primary site of <em>dynamic, by-type</em> bean access that no injection-point
 * analysis can see: a captured {@code ApplicationContext}/{@code BeanFactory}, an
 * {@code ObjectProvider}/{@code Lazy} resolved from a framework callback the class
 * implements, or a CGLIB {@code @Bean} self-invocation. This analyzer looks for
 * <em>evidence</em> of those channels in each configuration class's bytecode. A
 * configuration class is considered <em>provably safe</em> when it:
 * <ul>
 * <li>extends only {@link Object} (a non-trivial superclass may itself capture the
 * context or implement a framework callback);</li>
 * <li>implements no interface (framework-callback interfaces such as
 * {@code WebMvcConfigurer} and {@code *Aware} are the dominant invisible-lookup
 * channel);</li>
 * <li>declares no field, and no method parameter, of a context-handle type
 * ({@code ApplicationContext}, {@code BeanFactory} and friends, {@code ObjectProvider},
 * {@code ObjectFactory}, {@code ApplicationEventPublisher});</li>
 * <li>contains no {@code @Bean} self-invocation &mdash; no method body invokes another
 * {@code @Bean} method declared on the same class.</li>
 * </ul>
 *
 * <p>The decision is <em>context-wide and conservative</em>: because an unsafe
 * configuration can pull <em>any</em> configuration's {@code @Bean} bean by type, the
 * {@code @Bean} beans of the whole context may only re-enter the background set when
 * <em>every</em> configuration class is provably safe. {@link #canSkipColocation} returns
 * {@code true} only in that case; any class that cannot be read or analysed is treated as
 * unsafe (so the safe blanket co-location is kept).
 *
 * <p>This class performs pure analysis and never triggers bean creation.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see BeanDependencyGraph
 * @see ParallelBootstrapSettings#isEvidenceBasedColocation()
 */
final class ConfigurationClassColocationAnalyzer {

    /**
     * Internal names (slash form) of types that hand a configuration class a channel to
     * resolve beans by type at runtime. A field or method parameter of any of these makes
     * the configuration class unsafe for evidence-based co-location.
     */
    private static final Set<String> CONTEXT_HANDLE_TYPES = Set.of(
            "org/springframework/context/ApplicationContext",
            "org/springframework/context/ConfigurableApplicationContext",
            "org/springframework/context/ApplicationEventPublisher",
            "org/springframework/context/ResourceLoaderAware",
            "org/springframework/beans/factory/BeanFactory",
            "org/springframework/beans/factory/HierarchicalBeanFactory",
            "org/springframework/beans/factory/ListableBeanFactory",
            "org/springframework/beans/factory/config/ConfigurableBeanFactory",
            "org/springframework/beans/factory/config/AutowireCapableBeanFactory",
            "org/springframework/beans/factory/config/ConfigurableListableBeanFactory",
            "org/springframework/beans/factory/ObjectProvider",
            "org/springframework/beans/factory/ObjectFactory");

    private ConfigurationClassColocationAnalyzer() {}

    /**
     * Determine whether the blanket {@code @Bean} co-location rule can be skipped for the
     * given bean factory, i.e. whether <em>every</em> configuration class that hosts
     * {@code @Bean} factory methods is provably free of invisible by-type lookup channels.
     * @param beanFactory the bean factory to introspect
     * @return {@code true} only when every configuration class is provably safe, so
     * {@code @Bean} beans may re-enter the background candidate set; {@code false}
     * otherwise (keeping the safe blanket co-location)
     */
    static boolean canSkipColocation(ConfigurableListableBeanFactory beanFactory) {
        Map<String, Set<String>> configToBeanMethods = mapConfigurationsToBeanMethods(beanFactory);
        if (configToBeanMethods.isEmpty()) {
            // No factory-method beans at all: nothing to co-locate, so the rule is moot.
            return true;
        }
        for (Map.Entry<String, Set<String>> entry : configToBeanMethods.entrySet()) {
            if (!isProvablySafe(beanFactory, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build a map from each configuration bean name (a bean that is the factory of at
     * least one {@code @Bean} method bean) to the set of factory-method names it hosts.
     */
    private static Map<String, Set<String>> mapConfigurationsToBeanMethods(
            ConfigurableListableBeanFactory beanFactory) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = safeGetMergedBeanDefinition(beanFactory, beanName);
            if (bd == null) {
                continue;
            }
            String factoryBeanName = bd.getFactoryBeanName();
            String factoryMethodName = bd.getFactoryMethodName();
            if (factoryBeanName != null && factoryMethodName != null) {
                result.computeIfAbsent(factoryBeanName, key -> new HashSet<>()).add(factoryMethodName);
            }
        }
        return result;
    }

    private static boolean isProvablySafe(
            ConfigurableListableBeanFactory beanFactory, String configBeanName, Set<String> beanMethodNames) {
        Class<?> type = safeGetType(beanFactory, configBeanName);
        if (type == null) {
            return false;
        }
        Class<?> userClass = ClassUtils.getUserClass(type);
        byte[] bytecode = readClassBytes(userClass);
        if (bytecode == null) {
            // Cannot inspect the class (e.g. no resource available): assume unsafe.
            return false;
        }
        try {
            SafetyClassVisitor visitor = new SafetyClassVisitor(beanMethodNames);
            new ClassReader(bytecode).accept(visitor, ClassReader.SKIP_FRAMES);
            return visitor.isSafe();
        } catch (RuntimeException ex) {
            // Any analysis failure is treated as unsafe so co-location is preserved.
            return false;
        }
    }

    private static byte @Nullable [] readClassBytes(Class<?> clazz) {
        String resource = clazz.getName().replace('.', '/') + ".class";
        ClassLoader classLoader = clazz.getClassLoader();
        if (classLoader == null) {
            classLoader = ConfigurationClassColocationAnalyzer.class.getClassLoader();
        }
        if (classLoader == null) {
            return null;
        }
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException ex) {
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

    private static @Nullable Class<?> safeGetType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * ASM {@link ClassVisitor} that records any evidence of an invisible by-type lookup
     * channel in a configuration class.
     */
    private static final class SafetyClassVisitor extends ClassVisitor {

        private final Set<String> beanMethodNames;

        private @Nullable String className;

        private boolean unsafe;

        SafetyClassVisitor(Set<String> beanMethodNames) {
            super(SpringAsmInfo.ASM_VERSION);
            this.beanMethodNames = beanMethodNames;
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                @Nullable String signature,
                @Nullable String superName,
                String @Nullable [] interfaces) {
            this.className = name;
            if (superName != null && !"java/lang/Object".equals(superName)) {
                // A non-trivial superclass may capture the context or implement a callback.
                this.unsafe = true;
            }
            if (interfaces != null && interfaces.length > 0) {
                // Framework-callback / *Aware interfaces are the dominant lookup channel.
                this.unsafe = true;
            }
        }

        @Override
        public @Nullable FieldVisitor visitField(
                int access, String name, String descriptor, @Nullable String signature, @Nullable Object value) {
            if (isContextHandle(descriptor)) {
                this.unsafe = true;
            }
            return null;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                @Nullable String signature,
                String @Nullable [] exceptions) {
            for (Type argumentType : Type.getArgumentTypes(descriptor)) {
                if (isContextHandle(argumentType)) {
                    this.unsafe = true;
                }
            }
            if (this.unsafe) {
                return null;
            }
            return new SelfInvocationMethodVisitor();
        }

        private boolean isContextHandle(String descriptor) {
            return isContextHandle(Type.getType(descriptor));
        }

        private boolean isContextHandle(Type type) {
            return type.getSort() == Type.OBJECT && CONTEXT_HANDLE_TYPES.contains(type.getInternalName());
        }

        boolean isSafe() {
            return !this.unsafe;
        }

        /**
         * Flags a {@code @Bean} self-invocation: a method body that calls another
         * {@code @Bean} method declared on the same configuration class (the CGLIB
         * self-invocation channel).
         */
        private final class SelfInvocationMethodVisitor extends MethodVisitor {

            SelfInvocationMethodVisitor() {
                super(SpringAsmInfo.ASM_VERSION);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL)
                        && owner.equals(SafetyClassVisitor.this.className)
                        && SafetyClassVisitor.this.beanMethodNames.contains(name)) {
                    SafetyClassVisitor.this.unsafe = true;
                }
            }
        }
    }
}
