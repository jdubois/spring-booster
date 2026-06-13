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

import java.util.List;
import javax.lang.model.element.Modifier;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.javapoet.CodeBlock;

/**
 * {@link BeanFactoryInitializationAotContribution} that emits an ahead-of-time computed
 * parallel bootstrap plan as generated code.
 *
 * <p>{@link ParallelBootstrapBeanFactoryPostProcessor#processAheadOfTime} runs the normal
 * dependency analysis and candidate selection once at build time and hands the resulting
 * background bean names and pool size to this contribution. The generated initializer
 * &mdash; invoked from the AOT-generated application context initializer before refresh
 * &mdash; simply calls
 * {@link ParallelBootstrapBeanFactoryPostProcessor#applyAotPlan(org.springframework.beans.factory.config.ConfigurableListableBeanFactory, List, int, String)},
 * which marks the precomputed beans for background initialization and installs the bounded
 * bootstrap executor. No dependency graph is built and no reflection is performed at
 * runtime, so the planning cost approaches zero and the feature is GraalVM native-image
 * friendly.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor#processAheadOfTime
 * @see ParallelBootstrapBeanFactoryPostProcessor#applyAotPlan
 */
class ParallelBootstrapAotContribution implements BeanFactoryInitializationAotContribution {

    private final List<String> backgroundBeanNames;

    private final int poolSize;

    private final String threadNamePrefix;

    ParallelBootstrapAotContribution(List<String> backgroundBeanNames, int poolSize, String threadNamePrefix) {
        this.backgroundBeanNames = List.copyOf(backgroundBeanNames);
        this.poolSize = poolSize;
        this.threadNamePrefix = threadNamePrefix;
    }

    @Override
    public void applyTo(
            GenerationContext generationContext, BeanFactoryInitializationCode beanFactoryInitializationCode) {
        var generatedMethod = beanFactoryInitializationCode.getMethods().add("applyParallelBootstrap", method -> {
            method.addJavadoc("Apply the ahead-of-time computed parallel bootstrap plan.");
            method.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
            method.addParameter(DefaultListableBeanFactory.class, "beanFactory");
            method.addStatement(
                    "$T.applyAotPlan(beanFactory, $L, $L, $S)",
                    ParallelBootstrapBeanFactoryPostProcessor.class,
                    beanNamesCode(),
                    this.poolSize,
                    this.threadNamePrefix);
        });
        beanFactoryInitializationCode.addInitializer(generatedMethod.toMethodReference());
    }

    /**
     * Render the background bean names as a {@code java.util.List.of(...)} expression.
     */
    private CodeBlock beanNamesCode() {
        CodeBlock names = this.backgroundBeanNames.stream()
                .map(name -> CodeBlock.of("$S", name))
                .collect(CodeBlock.joining(", "));
        return CodeBlock.of("$T.of($L)", List.class, names);
    }
}
