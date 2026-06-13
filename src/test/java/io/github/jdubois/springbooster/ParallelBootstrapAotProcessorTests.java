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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.GeneratedFiles;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.javapoet.ClassName;

class ParallelBootstrapAotProcessorTests {

    @Test
    void writesGeneratedPlanResource() throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .backgroundFactoryMethodBeans(true)
                .build();
        ParallelBootstrapInfrastructure.register(beanFactory, settings);
        beanFactory.registerBeanDefinition("leaf", new RootBeanDefinition(Leaf.class));
        beanFactory.registerBeanDefinition("consumer", new RootBeanDefinition(Consumer.class));

        BeanFactoryInitializationAotContribution contribution =
                new ParallelBootstrapAotProcessor().processAheadOfTime(beanFactory);

        assertThat(contribution).isNotNull();
        InMemoryGeneratedFiles generatedFiles = new InMemoryGeneratedFiles();
        DefaultGenerationContext generationContext = new DefaultGenerationContext(
                new ClassNameGenerator(ClassName.get("com.example", "Test")), generatedFiles);
        contribution.applyTo(generationContext, null);

        String content = generatedFiles.getGeneratedFileContent(
                GeneratedFiles.Kind.RESOURCE, ParallelBootstrapPlan.RESOURCE_LOCATION);
        ParallelBootstrapPlan plan = ParallelBootstrapPlan.fromResourceContent(content);
        assertThat(plan.getCandidateBeanNames()).contains("leaf", "consumer");
        assertThat(plan.getBeanNames())
                .contains("leaf", "consumer", ParallelBootstrapInfrastructure.POST_PROCESSOR_BEAN_NAME);
        assertThat(plan.getSyncDependencies()).containsKey("consumer");
        assertThat(plan.getSyncDependencies().get("consumer")).contains("leaf");
    }

    @Test
    void skipsAotGenerationForCustomCandidateFilter() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .candidateFilter(name -> name.startsWith("a"))
                .build();
        ParallelBootstrapInfrastructure.register(beanFactory, settings);
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));

        BeanFactoryInitializationAotContribution contribution =
                new ParallelBootstrapAotProcessor().processAheadOfTime(beanFactory);

        assertThat(contribution).isNull();
    }

    static class Leaf {}

    static class Consumer {
        Consumer(Leaf leaf) {}
    }
}
