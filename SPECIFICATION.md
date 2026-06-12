# Spring Booster — Specification

This document describes **what** Spring Booster is, **why** it is designed the way
it is, and **how** it is implemented. It is intended as the authoritative starting
point for anyone — human or agent — continuing work on this repository.

---

## 1. Purpose and scope

### 1.1 What we are building

Spring Booster is a small, standalone library that reduces Spring application
**startup time** by instantiating independent non-lazy singleton beans
**concurrently** during context refresh.

It does **not** invent a new container or a new bootstrap path. It builds directly
on a capability that already exists in the Spring Framework since 6.2:

* `AbstractBeanDefinition.setBackgroundInit(true)` — marks a bean definition so
  that `DefaultListableBeanFactory.preInstantiateSingletons()` instantiates it on a
  background thread instead of the main thread.
* `ConfigurableBeanFactory.setBootstrapExecutor(Executor)` — supplies the executor
  used for those background instantiations.

What the framework does **not** provide out of the box is an automatic decision of
*which* beans are safe to mark for background initialization. Spring Booster fills
exactly that gap: it analyses the bean definitions, decides on a conservative set
of safe candidates, marks them, installs a bounded executor, and tears the
executor down after refresh.

### 1.2 What is explicitly out of scope

* Reordering or rewriting application logic.
* Parallelizing bean *post-processing*, lifecycle callbacks, or the
  `SmartInitializingSingleton` phase (these remain on the main thread as the
  framework dictates).
* Resolving by-type / annotation autowiring statically (see §5 — this is the key
  known limitation).

### 1.3 Design goals (in priority order)

1. **Safety first.** Enabling the feature must never change application semantics.
   When in doubt, a bean is *not* parallelized.
2. **Strictly opt-in.** Nothing happens unless the user asks for it.
3. **Graceful degradation.** Any failure during planning falls back to the normal
   sequential bootstrap; the application still starts.
4. **Zero hard coupling beyond Spring.** The only runtime dependency is
   `spring-context` (plus JSpecify annotations).
5. **Self-contained and easy to evolve** — a clean, single-package library.

---

## 2. How it depends on Spring

The project tracks **Spring Boot 4.1.0**, which manages **Spring Framework 7.0.8**
(the stable Spring release for that Boot line).

Rather than hard-coding `7.0.8`, `build.gradle` imports the Boot platform BOM:

```groovy
api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
api('org.springframework:spring-context')   // -> resolves to 7.0.8
```

**Why this way:** importing the Boot BOM guarantees that Spring Booster is always
binary- and version-compatible with the exact Spring (and JSpecify, JUnit, AssertJ)
versions that a Spring Boot 4.1.0 application uses. Upgrading the baseline is a
one-line change (bump the BOM coordinate), which keeps the library trivially
maintainable as Boot evolves.

The minimum Java level is **17**, the Spring Framework 7 / Spring Boot 4 baseline.

---

## 3. Architecture

All code lives in a single package: `io.github.jdubois.springbooster`.

| Class | Responsibility |
|---|---|
| `EnableParallelBootstrap` | Public opt-in annotation. `@Import`s the registrar. Carries tuning attributes (`enabled`, `poolSize`, `threadNamePrefix`). |
| `ParallelBootstrapRegistrar` | `ImportBeanDefinitionRegistrar` activated by the annotation. Translates annotation attributes into `ParallelBootstrapSettings` and registers the post-processor as an infrastructure bean (idempotently). |
| `ParallelBootstrapApplicationContextInitializer` | `ApplicationContextInitializer` entry point for programmatic / Spring Boot (`spring.factories`) registration, with no need for the annotation. |
| `ParallelBootstrapSettings` | Immutable configuration (pool size, thread-name prefix, kill-switch, candidate `Predicate`). Built via a fluent `Builder`. Defines the per-bean opt-out attribute. |
| `BeanDependencyGraph` | Pure in-memory, conservative dependency graph of singleton bean definitions. Provides topological layering (Kahn) and cycle detection (Tarjan). Never triggers bean creation. |
| `ParallelBootstrapBeanFactoryPostProcessor` | The engine. Plans candidates, marks them for background init, installs the bounded executor, and registers a listener to shut it down after refresh. |
| `package-info.java` | `@NullMarked` package declaration and overview. |

### 3.1 Control flow

```
@EnableParallelBootstrap
        │  (@Import)
        ▼
ParallelBootstrapRegistrar.registerBeanDefinitions(...)
        │  registers infrastructure bean
        ▼
ParallelBootstrapBeanFactoryPostProcessor.postProcessBeanFactory(beanFactory)
        │
        ├─ if disabled / executor already set / no candidates → return (sequential)
        │
        ├─ planCandidates(beanFactory)               ── via BeanDependencyGraph
        ├─ markForBackgroundInit(each candidate)      ── setBackgroundInit(true)
        ├─ beanFactory.setBootstrapExecutor(pool)
        └─ register ContextRefreshedEvent listener → executor.shutdown()
        ▼
DefaultListableBeanFactory.preInstantiateSingletons()
        creates background-marked beans on the pool, the rest on the main thread
```

The post-processor implements both `BeanFactoryPostProcessor` and
`BeanFactoryInitializer` so it works whether it is invoked as a normal
post-processor or as an early bean-factory initializer. It is `PriorityOrdered`
with **lowest precedence**, so it runs *after* every other post-processor and sees
the final, complete set of bean definitions.

---

## 4. Candidate selection — the heart of the design

`ParallelBootstrapBeanFactoryPostProcessor.planCandidates(...)` selects beans that
are safe to instantiate in the background. A bean is a candidate **only if all** of
the following hold:

1. It is a **non-abstract, non-lazy singleton** bean definition.
2. It is **not part of a dependency cycle** (cycles require the single-threaded
   early-singleton-reference handshake; detected via Tarjan's SCC algorithm).
3. It is **not a shared factory bean** (a bean that acts as the factory for at
   least one other bean, e.g. a `@Configuration` class hosting `@Bean` methods).
   Such beans must be available synchronously on the main thread.
4. Its definition is an `AbstractBeanDefinition` (required to call
   `setBackgroundInit`).
5. It has **not opted out** via `ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE`.
6. Its type is **not framework infrastructure** — `BeanPostProcessor`,
   `BeanFactoryPostProcessor`, `BeanFactoryInitializer`, or
   `SmartInitializingSingleton`.
7. It passes the user-supplied **`candidateFilter`** predicate (default: accept
   all).

### 4.1 The dependency graph

`BeanDependencyGraph` extracts edges **statically** from each merged
`BeanDefinition`:

* `depends-on` declarations,
* the factory-bean reference,
* constructor-argument `BeanReference`s (including nested in collections/maps/arrays),
* property `BeanReference`s (same nesting rules).

Edges pointing outside the analysed node set are dropped; self-references are kept
so cycle detection can flag them. The graph exposes:

* `computeLayers()` — Kahn topological layering (each layer depends only on earlier
  layers and can run concurrently),
* `beansInCycles()` — Tarjan SCCs of size > 1, plus self-references.

It performs **pure analysis and never instantiates a bean.**

---

## 5. Known limitation (critical) — by-type / `ObjectProvider` autowiring

**The static graph only models *explicit* references. It is blind to by-type
autowiring, `@Autowired` injection points, and `ObjectProvider` lookups.**

This is a fundamental consequence of analysing bean *definitions* rather than
resolving the full autowiring model, and it has a concrete, verified failure mode:

> In a Spring Boot application, `WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter`
> pulls `resourceHandlerRegistrationCustomizer` **by type via `ObjectProvider`** on
> the main thread. Because no explicit `BeanReference` exists, the graph treats that
> customizer as an independent leaf, marks it for background initialization, and the
> framework then throws:
>
> ```
> BeanCurrentlyInCreationException: Bean marked for background initialization but
> requested in mainline thread - declare ObjectProvider or lazy injection point in
> dependent mainline beans
> ```
>
> This was reproduced end-to-end with Spring Petclinic on Spring Boot 4.0.3.

### 5.1 Consequences and current mitigation

* With the **default permissive `candidateFilter` (accept-all)**, Spring Booster is
  **not safe to enable wholesale on a typical Spring Boot application.**
* The current mitigation is operational, not algorithmic: restrict candidates with
  a `candidateFilter` (e.g. only your own `com.example.*` beans), or opt specific
  beans out via `OPT_OUT_ATTRIBUTE`.

### 5.2 Directions for a real fix (open design space for future agents)

Ordered roughly from least to most invasive:

1. **Safe-by-default candidate selection.** Default to *excluding* beans unless they
   are demonstrably safe — e.g. only application beans, never auto-configuration /
   framework-package beans; never beans that are `autowireCandidate` for a type that
   is injected via `ObjectProvider`.
2. **Model autowiring edges.** Inspect `@Autowired` constructors/fields/methods and
   `ObjectProvider`/`Provider` parameters to add by-type edges to the graph
   (resolving candidate bean names by type through the factory). This directly
   closes the gap that caused the Petclinic failure.
3. **Add `@Lazy` / `ObjectProvider` guidance or auto-rewriting** for mainline
   dependents of background beans, mirroring the framework's own recommendation in
   the exception message.
4. **Whole-application benchmark harness** to quantify the startup win and guard
   against regressions on representative apps.

A reproduction harness for the failure already exists in the sibling
`spring-context-bootstrap` work: a script that downloads Petclinic, wires in the
module, and asserts startup. Porting an equivalent integration test here would be a
high-value next step.

---

## 6. Lifecycle and resource management

* The bootstrap executor is a `ThreadPoolExecutor` with a **fixed, bounded** size
  (`max(2, availableProcessors() * 2)` by default) and **daemon** threads named with the
  configured prefix (default `parallel-bootstrap-`).
* A `ContextRefreshedEvent` listener (registered as a manual singleton so the event
  multicaster detects it) clears the factory's bootstrap executor and shuts the pool
  down **immediately after refresh**, so threads do not outlive bootstrap.
* The **global kill-switch** (`enabled = false`) registers the post-processor but
  makes it a no-op, allowing the feature to be disabled without code removal.

---

## 7. Build, test, and release

* **Build system:** Gradle (wrapper pinned; `java-library` + `maven-publish`).
* **Coordinates:** `io.github.jdubois:spring-booster` (version in `build.gradle`).
* **Artifacts:** main jar, `-sources.jar`, `-javadoc.jar`, Gradle module metadata
  and POM. `./gradlew publishToMavenLocal` installs them to `~/.m2`.
* **Tests:** JUnit Jupiter + AssertJ (versions from the Boot BOM). JUnit 6.x
  requires `junit-platform-launcher` on the test runtime classpath — it is declared
  explicitly in `build.gradle` because Gradle 9 does not auto-provision a matching
  launcher for JUnit Platform 6.
* **Test coverage today:** `BeanDependencyGraphTests` (graph/layers/cycles),
  `ParallelBootstrapBeanFactoryPostProcessorTests` (candidate planning, infra
  exclusion, opt-out), and `ParallelBootstrapIntegrationTests` (real context refresh,
  bean wiring, bootstrap-thread usage, executor shutdown, programmatic initializer).

### 7.1 Conventions

* Apache License 2.0 header on every `.java` file **except** `package-info.java`
  (which carries only the package Javadoc and `@NullMarked`).
* Null-safety via JSpecify (`@NullMarked` at package level, `@Nullable` on members).
* Single package; keep public surface minimal (`EnableParallelBootstrap`,
  `ParallelBootstrapApplicationContextInitializer`, `ParallelBootstrapSettings`,
  `ParallelBootstrapBeanFactoryPostProcessor`).

---

## 8. Provenance

Spring Booster was extracted from an experimental `spring-context-bootstrap`
module developed inside a Spring Framework fork. The code is unchanged in behaviour;
only the package was renamed (`org.springframework.context.bootstrap.parallel` →
`io.github.jdubois.springbooster`) and the build was made standalone against the
Spring Boot 4.1.0 BOM. The `@since 7.1` Javadoc tags reflect that origin and can be
reset to this project's own versioning at the maintainer's discretion.
