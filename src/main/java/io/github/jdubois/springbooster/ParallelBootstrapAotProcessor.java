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

import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

/**
 * Build-time AOT processor that precomputes and serializes a conservative Spring
 * Booster parallel-bootstrap plan.
 */
public final class ParallelBootstrapAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        ParallelBootstrapSettings settings = ParallelBootstrapInfrastructure.findSettings(beanFactory);
        if (settings == null || !settings.isEnabled() || !settings.isBuildTimePlanningEnabled()) {
            return null;
        }
        if (!settings.hasDefaultCandidateFilter()) {
            return null;
        }
        ParallelBootstrapPlan plan = new ParallelBootstrapPlanner(settings).createPlan(beanFactory);
        return new ResourceWritingContribution(plan);
    }

    private record ResourceWritingContribution(ParallelBootstrapPlan plan)
            implements BeanFactoryInitializationAotContribution {

        @Override
        public void applyTo(
                GenerationContext generationContext, BeanFactoryInitializationCode beanFactoryInitializationCode) {
            GeneratedMethod generatedMethod = beanFactoryInitializationCode.getMethods()
                    .add("markSpringBoosterBackgroundBeans", this::generateBackgroundInitMethod);
            beanFactoryInitializationCode.addInitializer(generatedMethod.toMethodReference());
            generationContext
                    .getGeneratedFiles()
                    .addResourceFile(ParallelBootstrapPlan.RESOURCE_LOCATION, this.plan.toResourceContent());
        }

        private void generateBackgroundInitMethod(org.springframework.javapoet.MethodSpec.Builder method) {
            method.addJavadoc("Mark precomputed Spring Booster beans for background initialization.");
            method.addModifiers(Modifier.PRIVATE);
            method.addParameter(ConfigurableListableBeanFactory.class, BeanFactoryInitializationCode.BEAN_FACTORY_VARIABLE);
            for (String beanName : this.plan.getCandidateBeanNames()) {
                method.addStatement(
                        "$T beanDefinition = $L.getBeanDefinition($S)",
                        BeanDefinition.class,
                        BeanFactoryInitializationCode.BEAN_FACTORY_VARIABLE,
                        beanName);
                method.beginControlFlow("if (beanDefinition instanceof $T abstractBeanDefinition)", AbstractBeanDefinition.class);
                method.addStatement("abstractBeanDefinition.setBackgroundInit(true)");
                method.endControlFlow();
            }
        }
    }
}
