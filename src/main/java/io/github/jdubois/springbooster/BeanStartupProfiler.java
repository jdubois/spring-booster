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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.Assert;

/**
 * An opt-in, low-overhead startup profiler that records, for each singleton bean
 * created during context refresh, the thread it was created on and the wall-clock
 * time its creation took.
 *
 * <p>The profiler is an {@link InstantiationAwareBeanPostProcessor}: it stamps a
 * start time when a bean's instantiation begins
 * ({@link #postProcessBeforeInstantiation}) and computes the elapsed time once the
 * bean has been fully initialized ({@link #postProcessAfterInitialization}). It never
 * alters the beans it observes — {@code postProcessBeforeInstantiation} always returns
 * {@code null} and {@code postProcessAfterInitialization} always returns the bean
 * unchanged — so enabling profiling cannot change application semantics.
 *
 * <p>Because instantiation and initialization of a single bean happen on one thread,
 * the captured {@linkplain BeanStartupRecord#threadName() thread name} is reliable, and
 * the {@linkplain BeanStartupRecord#background() background flag} is derived from the
 * configured bootstrap thread-name prefix. The recorded
 * {@linkplain BeanStartupRecord#durationNanos() duration} is <em>inclusive</em>: it
 * spans from the start of the bean's instantiation to the end of its initialization and
 * therefore also covers the creation of any dependencies created in between. This mirrors
 * how nested {@link org.springframework.core.metrics.ApplicationStartup} steps account
 * for time and makes the slowest entries in {@link #report()} a good starting point for
 * deciding which heavyweight beans are worth backgrounding.
 *
 * <p>This class is the observability foundation described in the project specification:
 * it turns tuning from guesswork into data and is intended to feed candidate selection
 * for parallel bootstrap.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapSettings#isProfileStartup()
 */
public final class BeanStartupProfiler implements InstantiationAwareBeanPostProcessor, PriorityOrdered {

    /**
     * An immutable record of a single bean's creation: its name, the thread it was
     * created on, the inclusive wall-clock duration of its creation in nanoseconds, and
     * whether that thread was a parallel-bootstrap background thread.
     *
     * @param beanName the name of the bean
     * @param threadName the name of the thread the bean was created on
     * @param durationNanos the inclusive wall-clock creation time, in nanoseconds
     * @param background whether the bean was created on a bootstrap background thread
     */
    public record BeanStartupRecord(String beanName, String threadName, long durationNanos, boolean background) {

        /**
         * The creation duration expressed in milliseconds.
         * @return the duration in milliseconds
         */
        public double durationMillis() {
            return this.durationNanos / 1_000_000.0d;
        }
    }

    private final String backgroundThreadPrefix;

    private final ConcurrentHashMap<String, long[]> pending = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<BeanStartupRecord> records = new ConcurrentLinkedQueue<>();

    /**
     * Create a profiler that classifies beans created on threads whose name starts with
     * the given prefix as having been created in the background.
     * @param backgroundThreadPrefix the bootstrap thread-name prefix (must not be empty)
     */
    public BeanStartupProfiler(String backgroundThreadPrefix) {
        Assert.hasText(backgroundThreadPrefix, "'backgroundThreadPrefix' must not be empty");
        this.backgroundThreadPrefix = backgroundThreadPrefix;
    }

    @Override
    public int getOrder() {
        // Run first among post-processors so the start stamp is taken as early as
        // possible and the end stamp as late as possible, capturing the full creation.
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }

    @Override
    public @Nullable Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws BeansException {
        // Record the start time and creating thread; never short-circuit instantiation.
        this.pending.put(
                beanName, new long[] {System.nanoTime(), Thread.currentThread().getId()});
        return null;
    }

    @Override
    public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        long[] start = this.pending.remove(beanName);
        if (start != null) {
            long durationNanos = Math.max(0L, System.nanoTime() - start[0]);
            String threadName = Thread.currentThread().getName();
            boolean background = threadName.startsWith(this.backgroundThreadPrefix);
            this.records.add(new BeanStartupRecord(beanName, threadName, durationNanos, background));
        }
        return bean;
    }

    /**
     * The recorded per-bean startup measurements, ordered from slowest to fastest.
     * @return an immutable snapshot of the startup records
     */
    public List<BeanStartupRecord> getRecords() {
        List<BeanStartupRecord> snapshot = new ArrayList<>(this.records);
        snapshot.sort(Comparator.comparingLong(BeanStartupRecord::durationNanos).reversed());
        return List.copyOf(snapshot);
    }

    /**
     * The number of beans that were created on a parallel-bootstrap background thread.
     * @return the count of beans created in the background
     */
    public long backgroundBeanCount() {
        return this.records.stream().filter(BeanStartupRecord::background).count();
    }

    /**
     * Render a human-readable summary of the recorded startup measurements, listing the
     * slowest beans first. Returns a short notice when nothing was instrumented.
     * @param maxBeans the maximum number of beans to list (must be positive)
     * @return a multi-line report string
     */
    public String report(int maxBeans) {
        Assert.isTrue(maxBeans > 0, "'maxBeans' must be positive");
        List<BeanStartupRecord> sorted = getRecords();
        if (sorted.isEmpty()) {
            return "Spring Booster startup profile: no beans were instrumented.";
        }
        long backgroundCount = backgroundBeanCount();
        StringBuilder sb = new StringBuilder();
        sb.append("Spring Booster startup profile: ")
                .append(sorted.size())
                .append(" bean(s) instrumented, ")
                .append(backgroundCount)
                .append(" created on bootstrap background thread(s).")
                .append(System.lineSeparator());
        sb.append("Slowest beans (inclusive wall-clock):").append(System.lineSeparator());
        int limit = Math.min(maxBeans, sorted.size());
        for (int i = 0; i < limit; i++) {
            BeanStartupRecord record = sorted.get(i);
            sb.append(String.format(
                    "  %2d. %-40s %8.2f ms  [%s%s]",
                    i + 1,
                    record.beanName(),
                    record.durationMillis(),
                    record.threadName(),
                    record.background() ? ", background" : ""));
            if (i < limit - 1) {
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /**
     * Render a report listing up to the ten slowest beans.
     * @return a multi-line report string
     * @see #report(int)
     */
    public String report() {
        return report(10);
    }

    /**
     * The total inclusive creation time across all instrumented beans, expressed in the
     * requested time unit. Note that, because durations are inclusive of nested
     * dependency creation, this total is expected to exceed the actual wall-clock refresh
     * time and is most useful for relative comparison rather than as an absolute figure.
     * @param unit the time unit to express the total in (must not be {@code null})
     * @return the summed inclusive creation time in the given unit
     */
    public long totalDuration(TimeUnit unit) {
        Assert.notNull(unit, "'unit' must not be null");
        long totalNanos = this.records.stream()
                .mapToLong(BeanStartupRecord::durationNanos)
                .sum();
        return unit.convert(totalNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Look up the recorded measurement for a single bean, if it was instrumented.
     * @param beanName the bean name
     * @return the record, or {@code null} if the bean was not instrumented
     */
    public @Nullable BeanStartupRecord getRecord(String beanName) {
        for (BeanStartupRecord record : this.records) {
            if (Objects.equals(record.beanName(), beanName)) {
                return record;
            }
        }
        return null;
    }
}
