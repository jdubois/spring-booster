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

import io.github.jdubois.springbooster.BeanStartupProfiler.BeanStartupRecord;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BeanStartupProfiler} exercised directly, without a full
 * application context.
 *
 * @author Spring Framework Team
 */
class BeanStartupProfilerTests {

    private final BeanStartupProfiler profiler = new BeanStartupProfiler("parallel-bootstrap-");

    @Test
    void recordsThreadAndDurationForInstrumentedBean() {
        profiler.postProcessBeforeInstantiation(Object.class, "alpha");
        Object bean = new Object();

        Object result = profiler.postProcessAfterInitialization(bean, "alpha");

        assertThat(result).isSameAs(bean);
        BeanStartupRecord record = profiler.getRecord("alpha");
        assertThat(record).isNotNull();
        assertThat(record.beanName()).isEqualTo("alpha");
        assertThat(record.threadName()).isEqualTo(Thread.currentThread().getName());
        assertThat(record.durationNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(record.background()).isFalse();
    }

    @Test
    void beforeInstantiationNeverShortCircuits() {
        assertThat(profiler.postProcessBeforeInstantiation(Object.class, "alpha"))
                .isNull();
    }

    @Test
    void afterInitializationWithoutStartIsIgnored() {
        Object bean = new Object();

        Object result = profiler.postProcessAfterInitialization(bean, "never-started");

        assertThat(result).isSameAs(bean);
        assertThat(profiler.getRecord("never-started")).isNull();
        assertThat(profiler.getRecords()).isEmpty();
    }

    @Test
    void recordsAreSortedSlowestFirst() throws InterruptedException {
        record("fast", 0);
        record("slow", 25);

        assertThat(profiler.getRecords())
                .extracting(BeanStartupRecord::beanName)
                .containsExactly("slow", "fast");
    }

    @Test
    void classifiesBackgroundThreadsByPrefix() throws InterruptedException {
        Thread worker = new Thread(
                () -> {
                    profiler.postProcessBeforeInstantiation(Object.class, "bg");
                    profiler.postProcessAfterInitialization(new Object(), "bg");
                },
                "parallel-bootstrap-1");
        worker.start();
        worker.join();

        BeanStartupRecord record = profiler.getRecord("bg");
        assertThat(record).isNotNull();
        assertThat(record.background()).isTrue();
        assertThat(profiler.backgroundBeanCount()).isEqualTo(1L);
    }

    @Test
    void reportListsSlowestBeansAndIsBoundedByLimit() throws InterruptedException {
        record("one", 0);
        record("two", 0);
        record("three", 0);

        String report = profiler.report(2);

        assertThat(report).contains("3 bean(s) instrumented");
        assertThat(report.lines().filter(line -> line.contains(". ")).count()).isEqualTo(2L);
    }

    @Test
    void reportHandlesNoInstrumentedBeans() {
        assertThat(profiler.report()).contains("no beans were instrumented");
    }

    @Test
    void totalDurationSumsRecords() throws InterruptedException {
        record("one", 0);
        record("two", 0);

        assertThat(profiler.totalDuration(TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void rejectsBlankPrefix() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BeanStartupProfiler("  "));
    }

    @Test
    void reportRejectsNonPositiveLimit() {
        record0("alpha");
        assertThatIllegalArgumentException().isThrownBy(() -> profiler.report(0));
    }

    private void record(String beanName, long sleepMillis) throws InterruptedException {
        profiler.postProcessBeforeInstantiation(Object.class, beanName);
        if (sleepMillis > 0) {
            Thread.sleep(sleepMillis);
        }
        profiler.postProcessAfterInitialization(new Object(), beanName);
    }

    private void record0(String beanName) {
        profiler.postProcessBeforeInstantiation(Object.class, beanName);
        profiler.postProcessAfterInitialization(new Object(), beanName);
    }
}
