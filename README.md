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
> beans produced by `@Bean` **factory methods are kept on the main thread by default**
> (co-located with their `@Configuration` class), because configuration classes are
> the main source of dynamic, by-type lookups (`getBean`, `ObjectProvider`, `Lazy`)
> that no static analysis can see. With these two rules the **default accept-all
> filter boots a fully auto-configured Spring Boot web app reliably** (verified on
> Spring Petclinic). Only your component-scanned beans are backgrounded by default; set
> `backgroundFactoryMethodBeans(true)` to also parallelize `@Bean` beans for maximum
> throughput (best paired with a `candidateFilter`). See
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
* **Keeps `@Bean` factory-method beans on the main thread by default**, co-located
  with their `@Configuration` class — configuration classes are the main source of
  dynamic, by-type lookups (`getBean`, `ObjectProvider`, `Lazy`) that static analysis
  cannot see — which is what makes accept-all bootstrapping safe. Opt in with
  `backgroundFactoryMethodBeans(true)` to parallelize those too, or
  `evidenceBasedColocation(true)` to background them automatically when ASM bytecode
  analysis proves every configuration class free of invisible by-type lookups.
* Marks those beans for background initialization and installs a **bounded
  bootstrap thread pool** (sized by default at twice the available processor
  count) that the bean factory uses during `preInstantiateSingletons()`.
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
(co-located with their `@Configuration` class) so that accept-all bootstrapping is
safe — only component-scanned beans are parallelized. To also background `@Bean`
beans for maximum parallelism, opt in:

```java
@EnableParallelBootstrap(backgroundFactoryMethodBeans = true)
// or
ParallelBootstrapSettings.builder().backgroundFactoryMethodBeans(true).build();
```

This reintroduces the risk that a main-thread bean pulls a backgrounded `@Bean` bean
by type through a call the analysis cannot see, so pair it with a `candidateFilter`
scoped to beans you know are safe.

### Evidence-based co-location

`backgroundFactoryMethodBeans(true)` backgrounds *every* `@Bean` bean unconditionally,
which is unsafe if any configuration class performs an invisible by-type lookup.
Evidence-based co-location is the safe middle ground: it uses ASM bytecode analysis to
*prove* that every `@Configuration` class in the context is free of invisible lookup
channels before releasing `@Bean` beans into the background set.

```java
@EnableParallelBootstrap(evidenceBasedColocation = true)
// or
ParallelBootstrapSettings.builder().evidenceBasedColocation(true).build();
```

A configuration class is considered safe only when it never captures the
`ApplicationContext`/`BeanFactory`, holds an `ObjectProvider`/`ObjectFactory`,
implements a framework callback interface, extends a non-trivial superclass, or
self-invokes one of its own `@Bean` methods. Because an unsafe class could pull *any*
config's `@Bean` bean by type, the decision is context-wide and all-or-nothing: if even
one configuration class cannot be proven safe, all `@Bean` beans stay co-located.
This lets heavyweight framework `@Bean` beans parallelize automatically on applications
where the analysis can vouch for every configuration class, with no manual
`candidateFilter` required.

### Profiling startup

Spring Booster ships an opt-in **startup profiler** that records, for every singleton
created during context refresh, the thread it was created on and the inclusive
wall-clock time its creation took. It logs a summary of the slowest beans once the
context has refreshed, turning tuning from guesswork into data — the slowest entries
are a good starting point for deciding which heavyweight beans are worth backgrounding.

```java
@EnableParallelBootstrap(profileStartup = true)
// or
ParallelBootstrapSettings.builder().profileStartup(true).build();
```

Profiling never changes application semantics and is independent of the global
kill-switch, so you can profile a **sequential baseline** by combining
`profileStartup(true)` with `enabled(false)` and compare it against a parallel run. The
installed `BeanStartupProfiler` is also registered as a singleton, so you can inspect
the per-bean records programmatically after refresh:

```java
BeanStartupProfiler profiler = context.getBean(BeanStartupProfiler.class);
profiler.getRecords().forEach(record ->
        System.out.printf("%s %.2f ms [%s]%n",
                record.beanName(), record.durationMillis(), record.threadName()));
```

> ℹ️ Durations are **inclusive**: a bean's measurement spans from the start of its
> instantiation to the end of its initialization and therefore also covers the creation
> of any dependencies created in between (mirroring nested `ApplicationStartup` steps).

### Making sure parallelism never costs more than it saves

Installing a thread pool has a fixed cost, and a dependency-constrained candidate set
may not be able to use a large pool. Two opt-in settings (both defaulting to today's
behaviour) tune this:

```java
@EnableParallelBootstrap(minimumBackgroundCandidates = 8, adaptivePoolSize = true)
// or
ParallelBootstrapSettings.builder()
        .minimumBackgroundCandidates(8) // "don't bother" guard (default 1)
        .adaptivePoolSize(true)         // size the pool to the achievable width (default false)
        .build();
```

* **`minimumBackgroundCandidates`** — a "don't bother" guard. When fewer than this many
  beans would be backgrounded, Spring Booster skips parallel bootstrap entirely and the
  context starts sequentially, so you never pay the pool overhead for a negligible win.
* **`adaptivePoolSize`** — when `true`, the bootstrap pool is capped at the *achievable
  concurrency width* of the selected candidates (the most beans that can actually run at
  once given their dependencies), with a floor of `2` and never exceeding `poolSize`.
  This avoids idle threads when the candidates cannot all run concurrently.

### Ahead-of-time (AOT) and GraalVM native image

Spring Booster participates in Spring's AOT processing. When your application is built
with AOT (for example a GraalVM native image, or `spring.aot.enabled=true`), the
background-init plan and pool size are computed **once at build time** and emitted as
generated initializer code. At runtime the generated code marks the selected beans and
installs the bootstrap executor directly, so the dependency-graph construction and
bytecode analysis never run on the startup path. No configuration is required — the
behaviour is identical to the runtime planner, just precomputed.

## Requirements

| | Version |
|---|---|
| Java | 17 or later |
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

> **Build with a Java 17 JDK.** The Palantir Java Format engine used by Spotless
> runs under the JDK that runs the build, so the build is verified against JDK 17
> (the project's baseline). Point `JAVA_HOME` at a JDK 17 before building.

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
