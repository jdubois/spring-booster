# Spring Booster

**Opt-in parallel bean instantiation for the Spring application context bootstrap.**

Spring Booster speeds up application startup by instantiating independent
non-lazy singleton beans **concurrently** during context refresh, instead of one
after another on a single thread. It is a thin, self-contained add-on that builds
on the *background bean initialization* machinery already present in the Spring
Framework (`AbstractBeanDefinition.setBackgroundInit(...)` and the bean factory
*bootstrap executor*), and drives it automatically through a conservative
dependency analysis.

The feature is **strictly opt-in**: nothing is parallelized unless you enable it
explicitly, and it always degrades gracefully to the normal sequential bootstrap
if anything goes wrong.

> ℹ️ **Safe with the accept-all default on fully auto-configured Spring Boot apps.**
> The dependency analysis models **by-type / `@Autowired` / `ObjectProvider`
> autowiring** in addition to explicit references, and candidate selection is
> **connectivity-safe**: a bean is parallelized only when no dependency edge connects
> it — in either direction — to a bean that runs on the main thread. On top of that,
> whenever the context contains a **dynamic** `@Configuration` (one that implements an
> `Aware`/`*Configurer`/`*Customizer` callback, is a CGLIB-proxied full `@Configuration`,
> or captures an `ApplicationContext`/`BeanFactory`/`ObjectProvider`/`@Lazy`), **every**
> bean produced by a `@Bean` **factory method is kept on the main thread**, because such
> classes are the main source of dynamic, by-type lookups (`getBean`, `ObjectProvider`,
> `Lazy`) that no static analysis can see — and those lookups can target a `@Bean` of
> *any* configuration, not just their own. Only when the context has *no* dynamic
> configuration at all do `@Bean` beans — and the subtrees below them — background. With
> these rules the **default accept-all filter boots a fully auto-configured Spring Boot web
> app reliably** (verified on Spring Petclinic). Set `backgroundFactoryMethodBeans(true)`
> to parallelize `@Bean` beans even when dynamic configurations are present, or
> `deferProviderEdges(true)` to let `ObjectProvider`/`@Lazy` dependencies cross the
> background boundary — both best paired with a `candidateFilter`. See
> [SPECIFICATION.md](SPECIFICATION.md) §5.

---

## What it does

* Builds an **approximate, conservative dependency graph** of the registered
  bean definitions, using both statically introspectable references (`depends-on`,
  factory-bean references, and constructor/property `BeanReference`s) **and** by-type
  / `@Autowired` / `ObjectProvider` autowiring edges (resolved without instantiating
  any beans).
* Identifies **independent beans** that are safe to create concurrently using
  **connectivity-safe selection** — excluding beans in dependency cycles, shared
  factory beans, framework infrastructure beans such as `BeanPostProcessor`s, anything
  you opt out, and any bean connected by a dependency edge to a bean that must run on
  the main thread.
* **Keeps `@Bean` factory-method beans on the main thread by default whenever the
  context contains a *dynamic* configuration**, co-located with their `@Configuration`
  class — configurations that implement `Aware`/`*Configurer`/`*Customizer` callbacks, are
  CGLIB-proxied full `@Configuration`s, or capture an `ApplicationContext`/`BeanFactory`/
  `ObjectProvider`/`@Lazy` are the main source of dynamic, by-type lookups (`getBean`,
  `ObjectProvider`, `Lazy`) that static analysis cannot see, and such a lookup can target a
  `@Bean` of *any* configuration. If a context has no dynamic configuration at all, its
  `@Bean` beans background. Opt in with `backgroundFactoryMethodBeans(true)` to parallelize
  `@Bean` beans even when dynamic configurations are present.
* Marks those beans for background initialization and installs a **bounded
  bootstrap thread pool** (sized by default at twice the available processor
  count) that the bean factory uses during `preInstantiateSingletons()`.
* Can **precompute the conservative bootstrap plan at build time** during Spring AOT
  processing, package it as a generated resource, and reuse it at runtime instead of
  recomputing the bean graph on startup.
* **Shuts the pool down** automatically once the context has refreshed, so it does
  not linger for the lifetime of the application.
* Falls back to the **normal sequential bootstrap** whenever the feature is
  disabled, when no beans are eligible, or when planning fails for any reason
  (global kill-switch + defensive try/catch).

## How to use it

### Annotation-based (most common)

```java
@Configuration
@EnableParallelBootstrap
public class AppConfig {
}
```

Tuning attributes are available:

```java
@EnableParallelBootstrap(poolSize = 8, threadNamePrefix = "boot-", enabled = true)
```

Build-time planning and fallback behavior can also be tuned:

```java
@EnableParallelBootstrap(
        buildTimePlanningEnabled = true,
        runtimePlanningEnabled = true,
        generatedPlanRequired = false)
```

### Programmatic (e.g. Spring Boot, or any code that builds the context)

```java
var context = new AnnotationConfigApplicationContext();
new ParallelBootstrapApplicationContextInitializer().initialize(context);
context.register(AppConfig.class);
context.refresh();
```

In Spring Boot 4 the initializer can be registered via `META-INF/spring.factories`:

```
org.springframework.context.ApplicationContextInitializer=\
io.github.jdubois.springbooster.ParallelBootstrapApplicationContextInitializer
```

### Full control with custom settings

```java
ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
        .poolSize(8)
        .threadNamePrefix("boot-")
        .buildTimePlanningEnabled(true)
        .runtimePlanningEnabled(true)
        .generatedPlanRequired(false)
        .candidateFilter(beanName -> beanName.startsWith("com.example."))
        .build();

context.addBeanFactoryPostProcessor(
        new ParallelBootstrapBeanFactoryPostProcessor(settings));
```

You can also opt a single bean definition out of background initialization:

```java
beanDefinition.setAttribute(ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE, Boolean.TRUE);
```

### Backgrounding `@Bean` factory-method beans

By default, beans produced by `@Bean` factory methods stay on the main thread
(co-located with their `@Configuration` class) **whenever the context contains a
*dynamic* configuration** — one that implements an `Aware`/`*Configurer`/`*Customizer`
callback, is a CGLIB-proxied full `@Configuration`, or captures an
`ApplicationContext`/`BeanFactory`/`ObjectProvider`/`@Lazy`. Because such a configuration's
invisible by-type lookups can pull a `@Bean` of *any* configuration (even a pure one),
every `@Bean` bean is kept on the main thread in that case. Only in a context with no
dynamic configuration at all are `@Bean` beans backgrounded automatically. To background
`@Bean` beans even when dynamic configurations are present, for maximum parallelism, opt
in:

```java
@EnableParallelBootstrap(backgroundFactoryMethodBeans = true)
// or
ParallelBootstrapSettings.builder().backgroundFactoryMethodBeans(true).build();
```

This reintroduces the risk that a main-thread bean pulls a backgrounded `@Bean` bean
by type through a call the analysis cannot see, so pair it with a `candidateFilter`
scoped to beans you know are safe.

### Backgrounding specific `@Bean` beans (allowlist)

If you want to background only a **few specific** heavyweight `@Bean` beans — for
example a `springSecurityFilterChain` you know is independent of the database stack —
without exposing every other `@Bean` bean to the invisible by-type lookup risk, name
them explicitly instead of flipping `backgroundFactoryMethodBeans` for the whole
context:

```java
@EnableParallelBootstrap(backgroundBeanNames = {"springSecurityFilterChain"})
// or
ParallelBootstrapSettings.builder()
        .backgroundBeanNames("springSecurityFilterChain")
        .build();
```

You can also opt a single bean definition in via an attribute (the inverse of the
opt-out attribute):

```java
beanDefinition.setAttribute(ParallelBootstrapSettings.FORCE_BACKGROUND_ATTRIBUTE, Boolean.TRUE);
```

Naming a bean drops **only** its `@Configuration`→`@Bean` co-location edge; the bean
must still clear every other safety check — it is not backgrounded if it is in a
cycle, is a forced-mainline `depends-on`/factory target (such as a Flyway/Liquibase
migrator that the JPA `EntityManagerFactory` declares a `depends-on` against), is
opted out, or is connected by a genuine, visible sync edge to a main-thread bean. To
background a whole independent subtree, allowlist its members together. An invisible
eager by-type pull still fails fast with `BeanCurrentlyInCreationException` and falls
back to the sequential bootstrap, so the allowlist stays faithful to the
"sequential when in doubt" design goal.

### Deferring `ObjectProvider` / `@Lazy` edges

`ObjectProvider`/`ObjectFactory`/`Provider`/`@Lazy` are Spring's escape hatch for
crossing the main-thread → background boundary: the dependency is resolved *after*
the dependent is constructed, so a main-thread bean depending on a background subtree
only through a provider need not drag it back to the main thread. By default these edges
are still treated as hard synchronous edges (a provider *could* be dereferenced eagerly
during init, which static analysis cannot see). Opt in to let them cross the boundary:

```java
@EnableParallelBootstrap(deferProviderEdges = true)
// or
ParallelBootstrapSettings.builder().deferProviderEdges(true).build();
```

If a deferred provider is in fact dereferenced eagerly (e.g. inside a constructor or an
init callback such as `WebMvcConfigurer.addArgumentResolvers`), the bootstrap fails fast
with `BeanCurrentlyInCreationException` rather than producing wrong results — which is
why this is opt-in.

### Backgrounding shared-infrastructure consumers

Some heavyweight beans are independent of each other yet share a single
fully-constructed infrastructure singleton — the classic example is Flyway and
Liquibase both reading the same `DataSource`. Normally the shared `DataSource` (a
main-thread `depends-on` target) drags both consumers back onto the main thread,
serializing them. The opt-in `backgroundSharedInfraConsumers` flag teaches the
planner that *depending on an already-finished singleton is safe*, so those
independent consumers can run concurrently:

```java
@EnableParallelBootstrap(backgroundSharedInfraConsumers = true)
// or
ParallelBootstrapSettings.builder()
        .backgroundSharedInfraConsumers(true)
        .build();
```

The relaxation is deliberately narrow: it only exempts a *completed-leaf barrier* —
a forced-mainline, acyclic, terminal singleton with no background dependency of its
own — from propagating main-thread-ness to the beans that merely *read* it. The
reverse direction (a main-thread bean that depends on a candidate) is never exempted,
because that is a genuine in-flight pull. It is off by default and falls back to
sequential whenever the predicate is even slightly violated, so it stays faithful to
the library's "sequential when in doubt" design goal. The flag keeps factory-method
`@Bean` co-location active (so unrelated infrastructure stays on the main thread) and
selectively frees only the verified pure barrier consumers, so it works for `@Bean`
consumers like Flyway/Liquibase on its own — you do **not** need to also enable
`backgroundFactoryMethodBeans`.

### Naming shared infrastructure as a barrier

`backgroundSharedInfraConsumers` only recognises a shared singleton as a barrier when
the framework *force-instantiates* it on the main thread (a `depends-on` target, factory
bean, or configuration class). A `DataSource` exposed **only** as a co-located `@Bean`
of a dynamic auto-configuration is not in that set, so its consumers stay serialized
behind it. When you know such a bean is a completed, read-only leaf, name it as a
barrier so its independent consumers can overlap:

```java
@EnableParallelBootstrap(barrierBeanNames = {"dataSource"})
// or
ParallelBootstrapSettings.builder()
        .barrierBeanNames("dataSource")
        .build();
```

A non-empty `barrierBeanNames` activates the completed-leaf relaxation on its own (you
do **not** also need `backgroundSharedInfraConsumers`); when only the named list is set,
structural auto-detection is skipped and just your named barriers are honoured. A named
bean is honoured only while it remains an acyclic leaf with respect to the other
background candidates, and only the *barrier → consumer* direction is exempted — a
main-thread bean that genuinely depends on the named bean still pins it. An incorrect
name is simply ignored, so the relaxation stays faithful to the "sequential when in
doubt" design goal.

### Declaring mutually independent heavyweights (co-background groups)

The allowlist backgrounds individual `@Bean` beans but keeps any sync edges *between*
them, so two co-located beans the static graph believes depend on one another are still
serialized. When you know a set of heavyweight beans are mutually independent and may be
built in parallel in any order, declare them as a co-background group:

```java
@EnableParallelBootstrap(coBackgroundGroups = {
        @EnableParallelBootstrap.CoBackgroundGroup({"flyway", "searchIndex"}),
        @EnableParallelBootstrap.CoBackgroundGroup({"cacheWarmer", "metricsBinder"})
})
// or
ParallelBootstrapSettings.builder()
        .coBackgroundGroup("flyway", "searchIndex")
        .coBackgroundGroup("cacheWarmer", "metricsBinder")
        .build();
```

For each group the planner drops each member's `@Configuration`→`@Bean` co-location
edge (like the allowlist) **and** removes the sync edges *between* members, letting a
member overlap a sibling it appeared to depend on. Only the sync-connectivity view is
touched: forced `depends-on`/factory-bean edges between members are preserved and still
ordered, cycle detection and layering are unaffected, and every member must still clear
`isSafeCandidate` and the mainline-propagation pass. An incorrect independence assertion
fails fast with `BeanCurrentlyInCreationException` and falls back to the sequential
bootstrap.

### Build-time planning (Spring AOT)

When Spring AOT processing runs, Spring Booster can precompute its conservative
parallel-bootstrap plan at build time and package it into the application as a
generated resource. The generated AOT initialization code also pre-marks the
selected bean definitions for background initialization, so they are ready to
run as soon as the runtime bootstrap executor is installed. If those generated
markers are unavailable, Spring Booster loads the generated plan next and uses
it directly when the current bean factory still matches the build-time
fingerprint. If the generated plan is missing or stale, Spring Booster falls
back to the existing runtime planner by default.

Use the new settings to control this behavior:

* `buildTimePlanningEnabled` — emit the generated plan during AOT processing
* `runtimePlanningEnabled` — allow runtime graph recomputation when no valid plan is
  available
* `generatedPlanRequired` — disable fallback and stay sequential unless a valid
  generated plan is present

> **Limit:** the AOT plan reuses the same conservative safety rules as runtime
> planning. It does **not** make dynamic bean lookups magically visible, so the
> default safety behavior for `@Bean` factory-method beans remains unchanged.

### Bytecode lookup-detection (experimental)

The reflective dynamic-configuration check is intentionally coarse: it flags a
configuration as *dynamic* from structural signals alone (a CGLIB-proxied full
`@Configuration`, an `Aware`/`*Configurer`/`*Customizer` callback, or a merely
*declared* `ApplicationContext`/`BeanFactory`/`ObjectProvider`/`@Lazy` field or
parameter). Many such configurations never actually look a bean up, yet their
`@Bean` beans — and, because co-location is context-wide, *every* configuration's
`@Bean` beans — are conservatively kept on the main thread.

The opt-in `bytecodeLookupDetection` setting adds a build-time bytecode scan
(using Spring's repackaged ASM) that inspects what a configuration class *really*
does. A configuration the reflective pass flagged as dynamic is re-examined for an
actual `getBean*`/`getBeanProvider` call on a captured `BeanFactory`/
`ApplicationContext`, an `ObjectProvider`/`ObjectFactory`/`Provider` dereference,
or a CGLIB `@Bean` self-invocation. If the scan *proves* the configuration performs
no such lookup it is downgraded to *pure*; an inconclusive scan (for example a class
file that cannot be read) leaves the conservative *dynamic* classification in place.
The refinement therefore only ever relaxes a false positive and is always safe.

```java
@EnableParallelBootstrap(bytecodeLookupDetection = true)
// or
ParallelBootstrapSettings.builder().bytecodeLookupDetection(true).build();
```

> **Status:** this is a prototype intended for build-time (Spring AOT) planning,
> off the startup critical path. It changes which configurations are classified as
> dynamic, but never relaxes the context-wide co-location rule when a genuine
> dynamic lookup remains.
### Using virtual threads for the bootstrap executor

By default the bootstrap executor is a **bounded platform-thread pool** sized at
twice the available processor count. Bean bootstrap is, however, frequently
*blocking-bound* — opening connection pools, warming caches, establishing remote
clients — and that is exactly the workload virtual threads are built for. Opt in to
run **one virtual thread per backgrounded bean** instead of the bounded pool:

```java
@EnableParallelBootstrap(useVirtualThreads = true)
// or
ParallelBootstrapSettings.builder().useVirtualThreads(true).build();
```

When enabled, an unbounded virtual-thread-per-task executor is installed and the
`poolSize` setting is ignored, so every independent blocking bean can make progress
concurrently without the pool-size ceiling and without oversubscribing the platform
carriers. This relies on the **Java 25 baseline**: since the fix for pinning on
`synchronized` (JDK 24, [JEP 491](https://openjdk.org/jeps/491)), a virtual thread
that blocks inside Spring's singleton-creation lock no longer pins its carrier, so
the blocking-bound part of bootstrap parallelizes cleanly. Purely CPU-bound bootstrap
workloads should keep the default bounded pool, whose size tracks the processor count.

Note that the real ceiling on startup speedup is the **critical path through the bean
dependency graph**: no threading model can beat the longest chain of dependent beans.
Virtual threads help most when there are many *independent*, blocking beans.

## Requirements

| | Version |
|---|---|
| Java | 25 or later |
| Spring Framework | 7.0.8 (the stable release used by **Spring Boot 4.1.0**) |

Spring Booster does not pin the Spring Framework version directly. Instead it
imports the `org.springframework.boot:spring-boot-dependencies:4.1.0` platform
BOM, so the Spring Framework version (and the versions of all other dependencies)
always match exactly what Spring Boot 4.1.0 ships. Bumping the Boot line in
`pom.xml` is the supported way to move to a newer Spring baseline.

## How to build

This project builds with **Maven** and ships the **Maven Wrapper**, so you do not
need a local Maven installation:

```bash
./mvnw verify
```

This compiles the code, runs the test suite, assembles the `jar`, `-sources.jar`
and `-javadoc.jar`, and verifies the code formatting.

> **Build with a Java 25 JDK.** The Palantir Java Format engine used by Spotless
> runs under the JDK that runs the build, so the build is verified against JDK 25
> (the project's baseline). Point `JAVA_HOME` at a JDK 25 before building.

### Code formatting

Java code is formatted with [Spotless](https://github.com/diffplug/spotless) using
[Palantir Java Format](https://github.com/palantir/palantir-java-format). Reformat
everything before committing:

```bash
./mvnw spotless:apply
```

`./mvnw verify` runs `spotless:check` and fails the build if anything is not
formatted.

## How to test

```bash
./mvnw test
```

The test suite (JUnit Jupiter + AssertJ) covers the dependency-graph analysis,
the candidate-planning logic of the post-processor, and full integration tests
that refresh a real application context with parallel bootstrap enabled and
assert that beans are wired correctly and created on bootstrap threads.

## How to install

### Install into the local Maven repository (`~/.m2`)

```bash
./mvnw install
```

This installs `io.github.jdubois:spring-booster:0.1.0-SNAPSHOT` (jar, sources,
javadoc and POM) into `~/.m2` so other local projects can depend on it.

### Consume it

**Maven**

```xml
<dependency>
    <groupId>io.github.jdubois</groupId>
    <artifactId>spring-booster</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to set
up a development environment, build with Maven, and submit changes.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
