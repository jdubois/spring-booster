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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Serialized, build-time parallel-bootstrap plan reused at runtime.
 */
final class ParallelBootstrapPlan {

    static final String RESOURCE_LOCATION = "META-INF/spring-booster/parallel-bootstrap.plan";

    private static final String FORMAT_VERSION = "1";

    private final List<String> candidateBeanNames;

    private final Set<String> forcedMainlineBeanNames;

    private final Map<String, Set<String>> syncDependencies;

    private final Set<String> beanNames;

    private final String settingsFingerprint;

    private final String beanFactoryFingerprint;

    ParallelBootstrapPlan(
            List<String> candidateBeanNames,
            Set<String> forcedMainlineBeanNames,
            Map<String, Set<String>> syncDependencies,
            Set<String> beanNames,
            String settingsFingerprint,
            String beanFactoryFingerprint) {

        this.candidateBeanNames = List.copyOf(candidateBeanNames);
        this.forcedMainlineBeanNames = Collections.unmodifiableSet(new LinkedHashSet<>(forcedMainlineBeanNames));
        Map<String, Set<String>> dependenciesCopy = new LinkedHashMap<>();
        syncDependencies.forEach((beanName, dependencies) ->
                dependenciesCopy.put(beanName, Collections.unmodifiableSet(new LinkedHashSet<>(dependencies))));
        this.syncDependencies = Collections.unmodifiableMap(dependenciesCopy);
        this.beanNames = Collections.unmodifiableSet(new LinkedHashSet<>(beanNames));
        this.settingsFingerprint = settingsFingerprint;
        this.beanFactoryFingerprint = beanFactoryFingerprint;
    }

    List<String> getCandidateBeanNames() {
        return this.candidateBeanNames;
    }

    Set<String> getForcedMainlineBeanNames() {
        return this.forcedMainlineBeanNames;
    }

    Map<String, Set<String>> getSyncDependencies() {
        return this.syncDependencies;
    }

    Set<String> getBeanNames() {
        return this.beanNames;
    }

    String getSettingsFingerprint() {
        return this.settingsFingerprint;
    }

    String getBeanFactoryFingerprint() {
        return this.beanFactoryFingerprint;
    }

    String toResourceContent() {
        Properties properties = new Properties();
        properties.setProperty("format.version", FORMAT_VERSION);
        properties.setProperty("settings.fingerprint", this.settingsFingerprint);
        properties.setProperty("beanFactory.fingerprint", this.beanFactoryFingerprint);
        properties.setProperty("bean.names", encodeNames(new TreeSet<>(this.beanNames)));
        properties.setProperty("candidates", encodeNames(this.candidateBeanNames));
        properties.setProperty("forced.mainline", encodeNames(new TreeSet<>(this.forcedMainlineBeanNames)));
        Set<String> syncKeys = this.syncDependencies.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        properties.setProperty("sync.keys", encodeNames(syncKeys));
        for (String beanName : syncKeys) {
            properties.setProperty(
                    "sync." + encodeName(beanName), encodeNames(new TreeSet<>(this.syncDependencies.get(beanName))));
        }
        StringBuilder builder = new StringBuilder();
        properties.forEach(
                (key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }

    static ParallelBootstrapPlan fromResourceContent(String content) {
        Assert.hasText(content, "'content' must not be empty");
        Properties properties = new Properties();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            Assert.isTrue(separator > 0, () -> "Invalid plan line: " + abbreviate(line));
            properties.setProperty(line.substring(0, separator), line.substring(separator + 1));
        }
        Assert.isTrue(
                FORMAT_VERSION.equals(properties.getProperty("format.version")), "Unsupported plan format version");
        Set<String> beanNames = new LinkedHashSet<>(decodeNames(properties.getProperty("bean.names")));
        List<String> candidates = decodeNames(properties.getProperty("candidates"));
        Set<String> forcedMainline = new LinkedHashSet<>(decodeNames(properties.getProperty("forced.mainline")));
        Map<String, Set<String>> syncDependencies = new LinkedHashMap<>();
        for (String beanName : decodeNames(properties.getProperty("sync.keys"))) {
            String encodedDependencies = properties.getProperty("sync." + encodeName(beanName), "");
            syncDependencies.put(beanName, new LinkedHashSet<>(decodeNames(encodedDependencies)));
        }
        return new ParallelBootstrapPlan(
                candidates,
                forcedMainline,
                syncDependencies,
                beanNames,
                properties.getProperty("settings.fingerprint", ""),
                properties.getProperty("beanFactory.fingerprint", ""));
    }

    private static String encodeNames(Collection<String> beanNames) {
        return beanNames.stream().map(ParallelBootstrapPlan::encodeName).collect(Collectors.joining(","));
    }

    private static List<String> decodeNames(@Nullable String encodedNames) {
        if (encodedNames == null || encodedNames.isEmpty()) {
            return List.of();
        }
        List<String> decoded = new ArrayList<>();
        for (String encodedName : encodedNames.split(",")) {
            if (!encodedName.isEmpty()) {
                decoded.add(decodeName(encodedName));
            }
        }
        return decoded;
    }

    private static String encodeName(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String abbreviate(String value) {
        return (value.length() <= 80 ? value : value.substring(0, 77) + "...");
    }
}
