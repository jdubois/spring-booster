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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.GeneratedFiles.Kind;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.InputStreamSource;
import org.springframework.javapoet.ClassName;

/**
 * Tests for the ahead-of-time planning path of
 * {@link ParallelBootstrapBeanFactoryPostProcessor} and the generated
 * {@link ParallelBootstrapAotContribution}.
 *
 * @author Spring Framework Team
 */
class ParallelBootstrapAotContributionTests {

    @Test
    void applyAotPlanMarksBeansAndInstallsExecutor() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));
        beanFactory.registerBeanDefinition("b", new RootBeanDefinition(Object.class));

        ParallelBootstrapBeanFactoryPostProcessor.applyAotPlan(beanFactory, List.of("a"), 3, "aot-test-");

        try {
            assertThat(isBackgroundInit(beanFactory, "a")).isTrue();
            assertThat(isBackgroundInit(beanFactory, "b")).isFalse();
            assertThat(beanFactory.getBootstrapExecutor()).isInstanceOf(ThreadPoolExecutor.class);
            assertThat(((ThreadPoolExecutor) beanFactory.getBootstrapExecutor()).getCorePoolSize())
                    .isEqualTo(3);
        } finally {
            ((ThreadPoolExecutor) beanFactory.getBootstrapExecutor()).shutdown();
        }
    }

    @Test
    void applyAotPlanDoesNotOverrideExistingExecutor() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));
        beanFactory.setBootstrapExecutor(Runnable::run);

        ParallelBootstrapBeanFactoryPostProcessor.applyAotPlan(beanFactory, List.of("a"), 3, "aot-test-");

        assertThat(isBackgroundInit(beanFactory, "a")).isFalse();
    }

    @Test
    void processAheadOfTimeReturnsNullWhenDisabled() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().enabled(false).build();

        BeanFactoryInitializationAotContribution contribution =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).processAheadOfTime(beanFactory);

        assertThat(contribution).isNull();
    }

    @Test
    void processAheadOfTimeReturnsNullWhenBelowMinimumCandidates() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .minimumBackgroundCandidates(2)
                .build();

        BeanFactoryInitializationAotContribution contribution =
                new ParallelBootstrapBeanFactoryPostProcessor(settings).processAheadOfTime(beanFactory);

        assertThat(contribution).isNull();
    }

    @Test
    void processAheadOfTimeReturnsContributionForCandidates() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("a", new RootBeanDefinition(Object.class));
        beanFactory.registerBeanDefinition("b", new RootBeanDefinition(Object.class));

        BeanFactoryInitializationAotContribution contribution =
                new ParallelBootstrapBeanFactoryPostProcessor().processAheadOfTime(beanFactory);

        assertThat(contribution).isInstanceOf(ParallelBootstrapAotContribution.class);
    }

    @Test
    void aotGenerationEmitsPrecomputedPlan() throws IOException {
        InMemoryGeneratedFiles generatedFiles = new InMemoryGeneratedFiles();
        DefaultGenerationContext generationContext = new DefaultGenerationContext(
                new ClassNameGenerator(ClassName.get("com.example", "Test")), generatedFiles);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBeanDefinition("alpha", new RootBeanDefinition(Object.class));
            context.registerBeanDefinition("beta", new RootBeanDefinition(Object.class));
            RootBeanDefinition postProcessor = new RootBeanDefinition(ParallelBootstrapBeanFactoryPostProcessor.class);
            postProcessor.setRole(AbstractBeanDefinition.ROLE_INFRASTRUCTURE);
            context.registerBeanDefinition("parallelBootstrap", postProcessor);

            new ApplicationContextAotGenerator().processAheadOfTime(context, generationContext);
        }
        generationContext.writeGeneratedContent();

        String generatedSources = readGeneratedSources(generatedFiles);
        assertThat(generatedSources)
                .contains("applyAotPlan")
                .contains("\"alpha\"")
                .contains("\"beta\"");
    }

    private static String readGeneratedSources(InMemoryGeneratedFiles generatedFiles) throws IOException {
        StringBuilder sources = new StringBuilder();
        Map<String, InputStreamSource> files = generatedFiles.getGeneratedFiles(Kind.SOURCE);
        for (InputStreamSource source : files.values()) {
            try (InputStream in = source.getInputStream()) {
                sources.append(new String(in.readAllBytes()));
            }
        }
        return sources.toString();
    }

    private static boolean isBackgroundInit(DefaultListableBeanFactory beanFactory, String beanName) {
        return ((AbstractBeanDefinition) beanFactory.getBeanDefinition(beanName)).isBackgroundInit();
    }
}
