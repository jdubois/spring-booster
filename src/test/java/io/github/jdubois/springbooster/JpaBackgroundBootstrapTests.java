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

import java.util.List;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

/**
 * Tests for {@link JpaBackgroundBootstrap}, the opt-in JPA {@code EntityManagerFactory} background
 * bootstrap wiring. The production target type ({@code AbstractEntityManagerFactoryBean}) lives in
 * {@code spring-orm}; these tests drive the helper through its {@code targetTypeNames} seam against
 * a local stub {@link FactoryBean} so they need no JPA provider.
 *
 * @author Spring Framework Team
 */
class JpaBackgroundBootstrapTests {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    private final Executor executor = Runnable::run;

    @Test
    void wiresBootstrapExecutorOntoMatchingFactoryBean() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));

        int wired = JpaBackgroundBootstrap.apply(
                this.beanFactory, this.executor, List.of(StubEntityManagerFactoryBean.class.getName()));

        assertThat(wired).isEqualTo(1);
        // Spring applies the bean-definition property on instantiation, so the FactoryBean actually
        // receives the deferred bootstrap executor.
        StubEntityManagerFactoryBean factoryBean = (StubEntityManagerFactoryBean)
                this.beanFactory.getBean(BeanFactory.FACTORY_BEAN_PREFIX + "entityManagerFactory");
        assertThat(factoryBean.getBootstrapExecutor()).isInstanceOf(AsyncTaskExecutor.class);
    }

    @Test
    void wrapsPlainExecutorAsAsyncTaskExecutor() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));

        JpaBackgroundBootstrap.apply(
                this.beanFactory, this.executor, List.of(StubEntityManagerFactoryBean.class.getName()));

        Object value = this.beanFactory
                .getBeanDefinition("entityManagerFactory")
                .getPropertyValues()
                .get(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY);
        assertThat(value).isInstanceOf(TaskExecutorAdapter.class);
    }

    @Test
    void passesThroughExecutorThatIsAlreadyAnAsyncTaskExecutor() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));
        AsyncTaskExecutor asyncExecutor = new SimpleAsyncTaskExecutor();

        JpaBackgroundBootstrap.apply(
                this.beanFactory, asyncExecutor, List.of(StubEntityManagerFactoryBean.class.getName()));

        Object value = this.beanFactory
                .getBeanDefinition("entityManagerFactory")
                .getPropertyValues()
                .get(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY);
        assertThat(value).isSameAs(asyncExecutor);
    }

    @Test
    void doesNotOverrideExplicitlyConfiguredBootstrapExecutor() {
        RootBeanDefinition bd = new RootBeanDefinition(StubEntityManagerFactoryBean.class);
        AsyncTaskExecutor userExecutor = new SimpleAsyncTaskExecutor();
        bd.getPropertyValues().add(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY, userExecutor);
        this.beanFactory.registerBeanDefinition("entityManagerFactory", bd);

        int wired = JpaBackgroundBootstrap.apply(
                this.beanFactory, this.executor, List.of(StubEntityManagerFactoryBean.class.getName()));

        assertThat(wired).isZero();
        Object value = this.beanFactory
                .getBeanDefinition("entityManagerFactory")
                .getPropertyValues()
                .get(JpaBackgroundBootstrap.BOOTSTRAP_EXECUTOR_PROPERTY);
        assertThat(value).isSameAs(userExecutor);
    }

    @Test
    void isInertWhenNoBeanMatchesTheTargetType() {
        this.beanFactory.registerBeanDefinition("plain", new RootBeanDefinition(Object.class));

        int wired = JpaBackgroundBootstrap.apply(
                this.beanFactory, this.executor, List.of(StubEntityManagerFactoryBean.class.getName()));

        assertThat(wired).isZero();
    }

    @Test
    void isInertWhenTargetTypeIsAbsentFromTheClasspath() {
        this.beanFactory.registerBeanDefinition(
                "entityManagerFactory", new RootBeanDefinition(StubEntityManagerFactoryBean.class));

        int wired = JpaBackgroundBootstrap.apply(
                this.beanFactory, this.executor, List.of("com.example.NotOnClasspath"));

        assertThat(wired).isZero();
    }

    /**
     * A minimal {@link FactoryBean} that mimics the JavaBean shape of
     * {@code AbstractEntityManagerFactoryBean} relevant to this wiring: a
     * {@code setBootstrapExecutor(AsyncTaskExecutor)} / {@code getBootstrapExecutor()} property.
     */
    static class StubEntityManagerFactoryBean implements FactoryBean<Object> {

        private @Nullable AsyncTaskExecutor bootstrapExecutor;

        public void setBootstrapExecutor(@Nullable AsyncTaskExecutor bootstrapExecutor) {
            this.bootstrapExecutor = bootstrapExecutor;
        }

        public @Nullable AsyncTaskExecutor getBootstrapExecutor() {
            return this.bootstrapExecutor;
        }

        @Override
        public Object getObject() {
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            return Object.class;
        }
    }
}
