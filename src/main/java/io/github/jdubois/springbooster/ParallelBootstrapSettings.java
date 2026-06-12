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

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.util.Assert;

/**
 * Configuration settings for {@link ParallelBootstrapBeanFactoryPostProcessor}.
 *
 * <p>Controls the size of the bounded bootstrap thread pool, the thread naming
 * prefix, an additional candidate predicate, and a global kill-switch that allows
 * disabling parallel bootstrapping without removing the post-processor.
 *
 * <p>Instances are created through {@link #builder()} and are immutable.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapBeanFactoryPostProcessor
 */
public final class ParallelBootstrapSettings {

	/**
	 * Bean definition attribute that, when set to {@code Boolean.TRUE}, explicitly
	 * opts a bean out of parallel background initialization.
	 */
	public static final String OPT_OUT_ATTRIBUTE = ParallelBootstrapSettings.class.getName() + ".optOut";

	private final boolean enabled;

	private final int poolSize;

	private final String threadNamePrefix;

	private final Predicate<String> candidateFilter;


	private ParallelBootstrapSettings(boolean enabled, int poolSize, String threadNamePrefix,
			Predicate<String> candidateFilter) {

		this.enabled = enabled;
		this.poolSize = poolSize;
		this.threadNamePrefix = threadNamePrefix;
		this.candidateFilter = candidateFilter;
	}


	/**
	 * Whether parallel bootstrapping is enabled (global kill-switch).
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * The number of threads in the bounded bootstrap pool.
	 */
	public int getPoolSize() {
		return this.poolSize;
	}

	/**
	 * The thread name prefix used for bootstrap threads.
	 */
	public String getThreadNamePrefix() {
		return this.threadNamePrefix;
	}

	/**
	 * An additional user-supplied filter applied to candidate bean names; a bean is
	 * only eligible for background initialization if this predicate returns
	 * {@code true}. Defaults to accepting every bean.
	 */
	public Predicate<String> getCandidateFilter() {
		return this.candidateFilter;
	}


	/**
	 * Create settings with sensible defaults: enabled, a pool size of twice the
	 * number of available processors, the {@code parallel-bootstrap-} thread prefix,
	 * and a candidate filter that accepts every bean.
	 */
	public static ParallelBootstrapSettings withDefaults() {
		return builder().build();
	}

	/**
	 * Create a new {@link Builder} pre-populated with default values.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Compute the default bootstrap pool size as twice the number of available
	 * processors, with a floor of {@code 2} so that at least some parallelism is
	 * available on single-core environments.
	 * <p>Bean bootstrap is frequently I/O- or blocking-bound (opening connection
	 * pools, warming caches, establishing remote clients), so the pool is
	 * deliberately oversized relative to the CPU count to keep cores busy while
	 * other threads wait. Workloads that are purely CPU-bound, or that block for
	 * unusually long, may benefit from tuning {@code poolSize} explicitly.
	 */
	public static int defaultPoolSize() {
		return Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
	}

	/**
	 * Determine whether the given bean definition has explicitly opted out of
	 * parallel background initialization via {@link #OPT_OUT_ATTRIBUTE}.
	 */
	static boolean isOptedOut(@Nullable BeanDefinition beanDefinition) {
		return (beanDefinition != null && Boolean.TRUE.equals(beanDefinition.getAttribute(OPT_OUT_ATTRIBUTE)));
	}


	/**
	 * Builder for {@link ParallelBootstrapSettings}.
	 */
	public static final class Builder {

		private boolean enabled = true;

		private int poolSize = defaultPoolSize();

		private String threadNamePrefix = "parallel-bootstrap-";

		private Predicate<String> candidateFilter = beanName -> true;

		private Builder() {
		}

		/**
		 * Set the global kill-switch. When {@code false}, the post-processor performs
		 * no work and the context bootstraps sequentially.
		 */
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		/**
		 * Set the bounded bootstrap pool size. Must be a positive number.
		 */
		public Builder poolSize(int poolSize) {
			Assert.isTrue(poolSize > 0, "'poolSize' must be positive");
			this.poolSize = poolSize;
			return this;
		}

		/**
		 * Set the thread name prefix for bootstrap threads.
		 */
		public Builder threadNamePrefix(String threadNamePrefix) {
			Assert.hasText(threadNamePrefix, "'threadNamePrefix' must not be empty");
			this.threadNamePrefix = threadNamePrefix;
			return this;
		}

		/**
		 * Set an additional candidate filter by bean name. Beans for which the
		 * predicate returns {@code false} are never marked for background
		 * initialization, regardless of the built-in safety checks.
		 */
		public Builder candidateFilter(Predicate<String> candidateFilter) {
			Assert.notNull(candidateFilter, "'candidateFilter' must not be null");
			this.candidateFilter = candidateFilter;
			return this;
		}

		/**
		 * Build the immutable {@link ParallelBootstrapSettings} instance.
		 */
		public ParallelBootstrapSettings build() {
			return new ParallelBootstrapSettings(this.enabled, this.poolSize,
					this.threadNamePrefix, this.candidateFilter);
		}
	}

}
