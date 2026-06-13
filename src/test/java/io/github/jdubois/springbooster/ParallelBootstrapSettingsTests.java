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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ParallelBootstrapSettings}, focusing on the tuning options that
 * control when parallelism is engaged and how the bootstrap pool is sized.
 *
 * @author Spring Framework Team
 */
class ParallelBootstrapSettingsTests {

    @Test
    void defaultsEngageParallelismForAnyCandidateWithFixedPool() {
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.withDefaults();

        assertThat(settings.getMinimumBackgroundCandidates()).isEqualTo(1);
        assertThat(settings.isAdaptivePoolSize()).isFalse();
    }

    @Test
    void minimumBackgroundCandidatesIsConfigurable() {
        ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
                .minimumBackgroundCandidates(4)
                .build();

        assertThat(settings.getMinimumBackgroundCandidates()).isEqualTo(4);
    }

    @Test
    void minimumBackgroundCandidatesRejectsValuesBelowOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParallelBootstrapSettings.builder().minimumBackgroundCandidates(0));
    }

    @Test
    void adaptivePoolSizeIsConfigurable() {
        ParallelBootstrapSettings settings =
                ParallelBootstrapSettings.builder().adaptivePoolSize(true).build();

        assertThat(settings.isAdaptivePoolSize()).isTrue();
    }
}
