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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Curated, opinionated pre-list of the well-known Spring Boot Web auto-configuration beans
 * that are safe to start in the background, used by the opt-in
 * {@link ParallelBootstrapSettings#isSpringBootWebProfile() Spring Boot Web profile}.
 *
 * <p>The generic {@link ParallelBootstrapBeanFactoryPostProcessor#planCandidates planner} is
 * deliberately conservative: it co-locates every {@code @Bean} factory-method bean with its
 * configuration class, so on a fully auto-configured application it backgrounds only the cheap
 * component beans and leaves the heavyweight framework {@code @Bean} beans on the main thread.
 * This class instead targets the canonical Spring Boot Web architecture (an embedded servlet
 * container plus Spring MVC, Jackson, optional Spring Security and Spring Cache) directly: it
 * names the specific framework beans that are known to be independent and worth overlapping.
 *
 * <p><b>The profile only ever generates a pre-list.</b> Resolved bean names are fed to the same
 * proven relaxation primitive as the per-bean
 * {@link ParallelBootstrapSettings#getBackgroundBeanNames() allowlist} (drop the
 * configuration&rarr;{@code @Bean} co-location edge); every freed bean must still clear the
 * candidate filter, the opt-out attribute, the infrastructure-type gate, cycle detection, the
 * forced-mainline {@code depends-on}/{@code FactoryBean} rule, and the connectivity-safe mainline
 * propagation. The structurally pinned heavyweights of a JPA web app &mdash; the
 * {@code EntityManagerFactory} {@code FactoryBean}, the {@code DataSource} its consumers pull
 * mainline, the JPA-pinned Liquibase/Flyway migrator, and the embedded servlet container created
 * before singleton instantiation &mdash; are therefore deliberately <em>absent</em> from the
 * registry: they cannot be backgrounded regardless of the pre-list, so the registry lists only the
 * genuinely freeable infrastructure.
 *
 * <p>Because Spring Booster depends only on {@code spring-context}, every Spring Web, Spring
 * Security and Spring Cache type is referenced by its fully-qualified class name and resolved
 * reflectively; an entry whose type is not on the classpath simply never matches.
 *
 * @author Spring Framework Team
 * @since 7.1
 * @see ParallelBootstrapSettings#isSpringBootWebProfile()
 */
final class SpringBootWebProfile {

    /**
     * A single curated registry entry: a logical bean identified by its canonical Spring Boot
     * bean name(s) and by the fully-qualified name(s) of the type it (or its supertypes /
     * interfaces) is assignable to. A registered bean matches the entry when its name is one of
     * {@code canonicalNames} <em>or</em> its resolved type is assignable to one of
     * {@code typeNames}.
     */
    record Entry(List<String> canonicalNames, List<String> typeNames) {}

    /**
     * A named group of registry entries that share an independence relationship.
     *
     * <p>Every member of a group is freed from {@code @Bean} co-location. When
     * {@code mutuallyIndependent} is {@code true} the planner additionally drops the sync edges
     * <em>between</em> resolved members of the same group (so an asserted-independent pair overlaps
     * even if the static graph believes one depends on the other); when {@code false} the
     * inter-member sync edges are preserved, so a member that genuinely depends on another member
     * stays correctly ordered behind it.
     */
    record Group(String name, boolean mutuallyIndependent, List<Entry> entries) {}

    /**
     * The result of resolving a {@link Group} against a concrete bean factory: the actual bean
     * names that are present in the context, together with the group's independence flag.
     */
    record ResolvedGroup(String name, boolean mutuallyIndependent, Set<String> beanNames) {}

    /**
     * Fully-qualified names of marker types whose presence as a registered bean identifies the
     * context as a Spring Boot Web application. Resolved reflectively (assignability), with a
     * simple-name fallback to stay robust across the Spring Boot module reshuffles that have moved
     * the web-server-factory packages between versions.
     */
    private static final List<String> WEB_CONTEXT_MARKER_TYPES = List.of(
            "org.springframework.boot.web.server.WebServerFactory",
            "org.springframework.boot.web.servlet.server.ServletWebServerFactory",
            "org.springframework.boot.web.server.servlet.ServletWebServerFactory",
            "org.springframework.boot.web.reactive.server.ReactiveWebServerFactory",
            "org.springframework.boot.web.server.reactive.ReactiveWebServerFactory",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.springframework.web.reactive.DispatcherHandler");

    /**
     * Simple type names that identify a web-server factory bean even when the exact package is one
     * this build does not know about (Spring Boot has relocated these between minor versions).
     */
    private static final Set<String> WEB_CONTEXT_MARKER_SIMPLE_NAMES =
            Set.of("WebServerFactory", "ServletWebServerFactory", "ReactiveWebServerFactory");

    /**
     * The curated registry. The three groups are mutually independent at the bean-graph level
     * (they share no dependency edges), so each is expressed with allowlist semantics
     * ({@code mutuallyIndependent = false}): every member is freed from co-location while its real,
     * intra-group dependencies stay ordered, and the connectivity-safe propagation keeps any member
     * a main-thread bean genuinely pulls on the main thread.
     */
    private static final List<Group> CATALOG = List.of(
            // Web / MVC / Jackson: the JSON mapper and the MVC handler infrastructure. These are
            // independent of the persistence stack and dominated by the leaf ObjectMapper.
            new Group(
                    "web",
                    false,
                    List.of(
                            new Entry(
                                    List.of("jacksonObjectMapper", "objectMapper"),
                                    List.of(
                                            "com.fasterxml.jackson.databind.ObjectMapper",
                                            "tools.jackson.databind.ObjectMapper")),
                            new Entry(
                                    List.of("requestMappingHandlerMapping"),
                                    List.of(
                                            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping")),
                            new Entry(
                                    List.of("requestMappingHandlerAdapter"),
                                    List.of(
                                            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter")),
                            new Entry(
                                    List.of("localeResolver"),
                                    List.of("org.springframework.web.servlet.LocaleResolver")))),
            // Security: the Spring Security filter chain. The canonical safe-to-background
            // heavyweight when it does not touch the database.
            new Group(
                    "security",
                    false,
                    List.of(new Entry(
                            List.of("springSecurityFilterChain"),
                            List.of("org.springframework.security.web.FilterChainProxy")))),
            // Cache: the cache manager. Independent of the persistence stack at the bean-graph
            // level, so it may overlap with the JPA bootstrap.
            new Group(
                    "cache",
                    false,
                    List.of(new Entry(
                            List.of("cacheManager"), List.of("org.springframework.cache.CacheManager")))));

    private SpringBootWebProfile() {}

    /**
     * Whether the given bean factory looks like a Spring Boot Web application, i.e. it registers a
     * bean that is (or is assignable to) one of the {@link #WEB_CONTEXT_MARKER_TYPES web markers}.
     * The check never instantiates a bean &mdash; it inspects resolved bean types only.
     */
    static boolean isWebContext(ConfigurableListableBeanFactory beanFactory) {
        ClassLoader classLoader = resolveClassLoader(beanFactory);
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = safeGetType(beanFactory, beanName);
            if (type == null) {
                continue;
            }
            if (isAssignableToAny(type, WEB_CONTEXT_MARKER_TYPES, classLoader)
                    || hasSupertypeSimpleName(type, WEB_CONTEXT_MARKER_SIMPLE_NAMES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the curated registry against the given bean factory: for every catalog group, collect
     * the names of the registered, in-graph beans that match one of its entries (by canonical name
     * or by assignable type). Groups that resolve to fewer than one member are dropped.
     * @param beanFactory the bean factory to resolve against
     * @param graphNodes the bean names present in the dependency graph (resolution is limited to
     * these so the profile never references a bean the planner is not considering)
     * @return the resolved, non-empty groups
     */
    static List<ResolvedGroup> resolve(ConfigurableListableBeanFactory beanFactory, Set<String> graphNodes) {
        ClassLoader classLoader = resolveClassLoader(beanFactory);
        List<ResolvedGroup> resolved = new ArrayList<>();
        for (Group group : CATALOG) {
            Set<String> beanNames = new LinkedHashSet<>();
            for (Entry entry : group.entries()) {
                collectMatches(beanFactory, graphNodes, entry, classLoader, beanNames);
            }
            if (!beanNames.isEmpty()) {
                resolved.add(new ResolvedGroup(group.name(), group.mutuallyIndependent(), beanNames));
            }
        }
        return resolved;
    }

    private static void collectMatches(
            ConfigurableListableBeanFactory beanFactory,
            Set<String> graphNodes,
            Entry entry,
            ClassLoader classLoader,
            Set<String> into) {
        // Match by canonical name first: well-known Spring Boot bean names are stable and the
        // cheapest, most reliable signal.
        for (String canonicalName : entry.canonicalNames()) {
            if (graphNodes.contains(canonicalName)) {
                into.add(canonicalName);
            }
        }
        // Then match by assignable type, which survives bean renames and custom user-defined names.
        for (String beanName : graphNodes) {
            if (into.contains(beanName)) {
                continue;
            }
            Class<?> type = safeGetType(beanFactory, beanName);
            if (type != null && isAssignableToAny(type, entry.typeNames(), classLoader)) {
                into.add(beanName);
            }
        }
    }

    private static boolean isAssignableToAny(Class<?> type, List<String> targetTypeNames, ClassLoader classLoader) {
        for (String targetTypeName : targetTypeNames) {
            Class<?> target = loadClass(targetTypeName, classLoader);
            if (target != null && target.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSupertypeSimpleName(Class<?> type, Set<String> simpleNames) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (simpleNames.contains(current.getSimpleName())) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (simpleNames.contains(iface.getSimpleName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @Nullable Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static ClassLoader resolveClassLoader(ConfigurableListableBeanFactory beanFactory) {
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return (contextClassLoader != null) ? contextClassLoader : SpringBootWebProfile.class.getClassLoader();
    }

    private static @Nullable Class<?> safeGetType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
