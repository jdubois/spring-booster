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

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.Assert;

/**
 * {@link ApplicationContextInitializer} that registers a
 * {@link ParallelBootstrapBeanFactoryPostProcessor} programmatically, enabling
 * parallel singleton instantiation without requiring the
 * {@link EnableParallelBootstrap @EnableParallelBootstrap} annotation.
 *
 * <p>This is the preferred entry point when the application context is created
 * programmatically, or when registering the initializer through the
 * {@code context.initializer.classes} property or a {@code spring.factories} entry:
 *
 * <pre class="code">
 * var context = new AnnotationConfigApplicationContext();
 * new ParallelBootstrapApplicationContextInitializer().initialize(context);
 * context.register(AppConfig.class);
 * context.refresh();
 * </pre>
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor
 * @see EnableParallelBootstrap
 */
public class ParallelBootstrapApplicationContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final ParallelBootstrapSettings settings;

    /**
     * Create an initializer with default settings.
     */
    public ParallelBootstrapApplicationContextInitializer() {
        this(ParallelBootstrapSettings.withDefaults());
    }

    /**
     * Create an initializer with the given settings.
     * @param settings the parallel bootstrap settings (must not be {@code null})
     */
    public ParallelBootstrapApplicationContextInitializer(ParallelBootstrapSettings settings) {
        Assert.notNull(settings, "'settings' must not be null");
        this.settings = settings;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.addBeanFactoryPostProcessor(new ParallelBootstrapBeanFactoryPostProcessor(this.settings));
    }
}
