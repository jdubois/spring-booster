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
* Perfectly reconstructing the framework's full autowiring model. Spring Booster
  now resolves by-type / `@Autowired` / `ObjectProvider` edges statically (see §4
  and §5), but lookups performed dynamically from inside bean code (e.g. a captured
  `ObjectProvider` resolved later, or a `BeanFactory.getBean(...)` call) remain
  invisible. The dominant source of such lookups is `@Configuration` classes, so when
  any *dynamic* configuration is present Spring Booster keeps **every** `@Bean`
  factory-method bean on the main thread (co-located with its configuration class); when
  no dynamic configuration exists, `@Bean` beans may background (see §5.2.1); this makes
  the default
  accept-all `candidateFilter` **safe on a fully auto-configured Spring Boot
  application**. The narrow residual blind spot — a component or plain-definition bean
  pulled by type through a direct `getBean(...)` from another bean's initialization code
  — is rare and app-specific (see §5.3).

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

Rather than hard-coding `7.0.8`, `pom.xml` imports the Boot platform BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>4.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

`spring-context` is then declared without a version and resolves to 7.0.8.

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
| `EnableParallelBootstrap` | Public opt-in annotation. `@Import`s the registrar. Carries tuning attributes (`enabled`, `poolSize`, `threadNamePrefix`, `backgroundFactoryMethodBeans`, `deferProviderEdges`, `backgroundSharedInfraConsumers`). |
| `ParallelBootstrapRegistrar` | `ImportBeanDefinitionRegistrar` activated by the annotation. Translates annotation attributes into `ParallelBootstrapSettings` and registers the post-processor as an infrastructure bean (idempotently). |
| `ParallelBootstrapApplicationContextInitializer` | `ApplicationContextInitializer` entry point for programmatic / Spring Boot (`spring.factories`) registration, with no need for the annotation. |
| `ParallelBootstrapSettings` | Immutable configuration (pool size, thread-name prefix, kill-switch, candidate `Predicate`, `backgroundFactoryMethodBeans`, `deferProviderEdges` and `backgroundSharedInfraConsumers` toggles). Built via a fluent `Builder`. Defines the per-bean opt-out attribute. |
| `BeanDependencyGraph` | Pure in-memory dependency graph of the bean definitions. Models both declared references **and** by-type autowiring edges (via `AutowiredEdgeResolver`), classifying each edge as *forced* or *sync* (and optionally *deferred*). Provides topological layering (Kahn) and cycle detection (Tarjan). Never triggers bean creation. |
| `DynamicConfigurationDetector` | Statically classifies each `@Configuration`/factory bean as *dynamic* (capable of invisible by-type lookups — full `@Configuration`, `Aware`/`*Configurer`/`*Customizer`, or captured context / provider / `@Lazy` members) or *pure*. If any configuration in the context is dynamic, co-location edges (§5.2.1) are added for **every** factory-method bean; if none is, they are dropped entirely. |
| `AutowiredEdgeResolver` | Reflectively resolves the by-type / `@Autowired` / `ObjectProvider` dependency edges that the declarations do not reveal (`@Bean` method params, autowired constructors, `@Autowired` fields/methods), unwrapping `ObjectProvider`/`ObjectFactory`/`Provider`/`Optional`/collections/maps/arrays. Resolves candidate names with eager init disabled, so it never instantiates a bean. |
| `ParallelBootstrapBeanFactoryPostProcessor` | The engine. Plans candidates (connectivity-safe selection), marks them for background init, installs the bounded executor, and registers a listener to shut it down after refresh. |
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
are safe to instantiate in the background. Selection has two stages: a per-bean
**structural** filter, followed by a graph-wide **connectivity-safe** pass.

A bean is **structurally eligible only if all** of the following hold:

1. It is a **non-abstract, non-lazy singleton** bean definition.
2. It is **not part of a dependency cycle** (cycles require the single-threaded
   early-singleton-reference handshake; detected via Tarjan's SCC algorithm over the
   full edge set, including by-type edges).
3. It is **neither a shared factory bean nor a `depends-on` target**. These are
   *forced-mainline* beans: the framework eagerly instantiates them on the main
   thread before backgrounding a dependent (a `@Configuration` class hosting `@Bean`
   methods is the canonical factory bean), so they must run on the main thread.
4. It is **not itself a configuration class** (a `@Configuration` class, *full* or
   *lite*, as marked by Spring's `ConfigurationClassPostProcessor`). Configuration
   classes are *forced-mainline*: they are routinely resolved dynamically — by type
   or **by annotation**, e.g. through `getBeansWithAnnotation(...)` — on the main
   thread, in ways no static analysis can see. A configuration class that hosts a
   `@Bean` method is already forced-mainline as that bean's factory (criterion 3),
   but one that registers **no** eligible `@Bean` singleton would otherwise slip
   through; this criterion keeps it on the main thread regardless. (Spring Security's
   `EnableWebSecurityConfiguration`, fetched via
   `getBeansWithAnnotation(EnableWebSecurity.class)`, is a concrete example.)
5. Its definition is an `AbstractBeanDefinition` (required to call
   `setBackgroundInit`).
6. It has **not opted out** via `ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE`.
7. Its type is **not framework infrastructure** — `BeanPostProcessor`,
   `BeanFactoryPostProcessor`, `BeanFactoryInitializer`, or
   `SmartInitializingSingleton`.
8. It passes the user-supplied **`candidateFilter`** predicate (default: accept
   all).
9. It is **not a `@Bean` factory-method bean co-located by §5.2.1**, *unless* the
   `backgroundFactoryMethodBeans` setting is enabled. By default, when any *dynamic*
   configuration is present in the context, every factory-method bean is co-located with
   its configuration class (§5.2.1), because dynamic configurations perform invisible
   by-type lookups that may target any `@Bean`; when no configuration is dynamic, all
   `@Bean` beans stay eligible.

Every bean that is *not* structurally eligible is treated as a **main-thread**
(mainline) bean. The connectivity-safe pass (§5) then removes any otherwise-eligible
bean that is joined to a main-thread bean by a *sync* edge.

### 4.1 The dependency graph

`BeanDependencyGraph` extracts edges from each merged `BeanDefinition` and from its
injection points, then classifies every edge as **forced** or **sync**:

* **Forced** (target eagerly created on the main thread before the source is
  backgrounded, so it never crosses the boundary unsafely):
  * `depends-on` declarations,
  * the factory-bean reference.
* **Sync** (resolved on the source bean's *own* thread, so it constrains which beans
  may share the background set):
  * constructor-argument `BeanReference`s (including nested in collections/maps/arrays),
  * property `BeanReference`s (same nesting rules),
  * **by-type / `@Autowired` / `ObjectProvider` autowiring edges** discovered by
    `AutowiredEdgeResolver` — `@Bean` factory-method parameters, autowired
    constructor parameters, and `@Autowired` fields/methods, unwrapping
    `ObjectProvider` / `ObjectFactory` / `Provider` / `Optional` / collections / maps
    / arrays to the target element type.

Edges pointing outside the analysed node set are dropped; declared self-references
are kept so cycle detection can flag them (autowiring self-matches are excluded, as
the framework excludes a bean from its own by-type collections). The graph exposes:

* `getSyncDependencies(name)` — the sync out-edges used by the connectivity-safe pass,
* `computeLayers()` — Kahn topological layering (each layer depends only on earlier
  layers and can run concurrently),
* `beansInCycles()` — Tarjan SCCs of size > 1, plus self-references.

Candidate names for a by-type injection point are resolved through
`getBeanNamesForType(type, includeNonSingletons=true, allowEagerInit=false)`, so the
graph still performs **pure analysis and never instantiates a bean.**

---

## 5. By-type / `ObjectProvider` autowiring — modelled, and made safe within the visible graph

Earlier versions of Spring Booster modelled only *explicit* references and were
blind to by-type autowiring, `@Autowired` injection points, and `ObjectProvider`
lookups. That gap produced a concrete, verified failure:

> In a Spring Boot application, `WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter`
> pulls `resourceHandlerRegistrationCustomizer` **by type via `ObjectProvider`** on
> the main thread. Because no explicit `BeanReference` existed, the graph treated that
> customizer as an independent leaf, marked it for background initialization, and the
> framework then threw:
>
> ```
> BeanCurrentlyInCreationException: Bean marked for background initialization but
> requested in mainline thread - declare ObjectProvider or lazy injection point in
> dependent mainline beans
> ```
>
> This was reproduced end-to-end with Spring Petclinic on Spring Boot 4.0.3.

Spring Booster now closes this gap with two cooperating mechanisms.

### 5.1 All edges are visible

`AutowiredEdgeResolver` (see §4.1) makes the previously-invisible by-type
relationships first-class **sync** edges in `BeanDependencyGraph`. The graph is built
over **every** registered bean definition, so all dependency relationships between
beans are represented — the customizer above now carries an incoming edge from the
adapter that requires it.

### 5.2 Connectivity-safe selection (why it is correct)

Seeing the edges is necessary but not sufficient; selection must also use them. The
framework contract (from `DefaultListableBeanFactory`) is:

* a **background** bean requested while the pre-instantiation thread is **MAIN**
  throws (the failure above), and
* a **mainline** bean requested from a **BACKGROUND** thread throws as well;
* only a background bean's `depends-on` and factory-bean references are
  force-instantiated on the main thread first (the *forced* edges).

From this, a background set `S` is safe **iff**:

1. every *forced*-edge target is mainline (handled structurally — factory beans and
   `depends-on` targets are never eligible), and
2. no *sync* edge crosses the boundary of `S` in **either** direction.

`planCandidates` therefore seeds the mainline set with every structurally-ineligible
bean and then **propagates mainline membership across sync edges to a fixpoint**: any
eligible bean joined to a mainline bean by a sync edge (incoming *or* outgoing) is
reclassified as mainline. Whatever remains eligible forms a set whose sync edges are
fully internal — safe to background. In the Petclinic case the adapter is a
factory-bean (mainline), so the customizer is pulled mainline and is never
backgrounded.

#### 5.2.1 Factory-method co-location (the accept-all safety boundary)

The propagation above is sound for every relationship that appears at a
definition or injection point, but a `@Configuration` class can reach its own — and
other configurations' — `@Bean` beans **by type, on the main thread, through calls no
static analysis can see**: a CGLIB self-invocation of another `@Bean` method, a
captured `ApplicationContext`/`BeanFactory` used for a by-type lookup, or an
`ObjectProvider`/`Lazy` resolved from a framework callback the class implements
(`WebMvcConfigurer.addArgumentResolvers`, …).

Because configuration classes are *always* created on the main thread (criterion 4
of §4 forces every configuration class mainline, and a configuration that hosts
`@Bean` methods is additionally forced-mainline as their factory),
`BeanDependencyGraph.build` adds a **factory→bean co-location sync edge** from a
configuration to each of its `@Bean` beans. Mainline propagation then keeps those
factory-method beans on the main thread. The remaining background candidates —
component-scanned beans and beans registered as plain definitions — are reached only
through the framework's ordinary singleton path, which honours background
initialization. This is what makes the default accept-all `candidateFilter` safe on a
fully auto-configured Spring Boot application (verified on Spring Petclinic: context
starts cleanly; and on the BootUI sample app: 19 beans backgrounded, context starts
cleanly).

**Per-configuration precision (default).** Co-location is only needed when some
configuration in the context *actually* performs such invisible by-type lookups. Most
configurations don't — a *pure* configuration just does `return new X(injectedParams)`,
where every dependency is a visible method parameter already modelled as a sync edge.
`DynamicConfigurationDetector` statically classifies each configuration:

* A configuration is treated as **dynamic** when it shows a known
  dynamic-lookup signature: it is a *full* `@Configuration` (CGLIB-proxied, so cross-`@Bean`
  self-invocation is possible), implements an `Aware` callback or a framework
  `*Configurer`/`*Customizer` interface, or captures an `ApplicationContext`/`BeanFactory`/
  `Environment`/`ApplicationEventPublisher`/`ResourceLoader`, an `ObjectProvider`/
  `ObjectFactory`/`Provider`, or a `@Lazy` injection point (field or constructor
  parameter). The detector errs toward *dynamic* on any uncertainty.
* A configuration is treated as **pure** otherwise.

The catch is that a dynamic configuration's invisible lookups are *by type* and are **not
restricted to its own `@Bean` beans** — it can pull a `@Bean` declared by a *different*,
even *pure*, configuration. Spring Data's dynamic `SpringDataWebConfiguration`, for
example, dereferences an `ObjectProvider`/`Lazy` of `SortHandlerMethodArgumentResolverCustomizer`
from its `WebMvcConfigurer.addArgumentResolvers` callback, pulling the `sortCustomizer`
`@Bean` declared by the *pure* `DataWebAutoConfiguration` by type on the main thread. So
co-locating only a dynamic configuration's *own* beans is **not** enough. The boundary is
therefore drawn per *context*, not per configuration:

* If **at least one** configuration in the context is dynamic, **every** `@Bean`
  factory-method bean is co-located (the invisible-lookup channel could target any of
  them). This is the case for any fully auto-configured Spring Boot application.
* If **no** configuration is dynamic, no invisible by-type lookup can occur, so co-location
  edges are dropped entirely and every `@Bean` bean — and the subtrees hanging off them —
  becomes eligible for the background again.

This keeps accept-all safe (the invisible-lookup channel stays closed whenever it could
exist) while still letting `@Bean` beans background in contexts that are wholly free of
dynamic configurations.

Setting `backgroundFactoryMethodBeans(true)` (builder) or
`@EnableParallelBootstrap(backgroundFactoryMethodBeans = true)` disables co-location
entirely — even when dynamic configurations are present — making every `@Bean` bean
eligible for maximum parallelism, at the cost of reintroducing the invisible by-type pull
risk, so it should be paired with a `candidateFilter` scoped to beans known to be safe.

#### 5.2.2 Deferred provider edges (opt-in)

`ObjectProvider`/`ObjectFactory`/`Provider`/`@Lazy` are the framework's documented
escape hatch for crossing the mainline→background boundary: the dependency is *not*
resolved while the dependent is constructed, so depending on a background subtree only
through a provider need not drag that subtree mainline. By default these edges are still
modelled as hard **sync** edges (conservative, because a provider can be dereferenced
*eagerly* inside an init callback or constructor body, which static analysis cannot
see).

Setting `deferProviderEdges(true)` (builder) or
`@EnableParallelBootstrap(deferProviderEdges = true)` reclassifies every by-type
provider/`@Lazy` edge as **deferred**: it is still kept in the full dependency graph
(so cycle detection and layering are unchanged) but excluded from the *sync* component,
so it is allowed to cross the mainline→background boundary. A mainline bean that reaches
a background subtree *only* through a provider no longer pulls it mainline. This is
opt-in precisely because of the eager-dereference caveat (e.g.
`WebMvcConfigurer.addArgumentResolvers` calling `provider.getObject()` during init): if
any deferred provider is read eagerly, the bootstrap fails fast with
`BeanCurrentlyInCreationException` rather than producing wrong results.

#### 5.2.3 Completed-leaf barrier relaxation (`backgroundSharedInfraConsumers`)

Mainline propagation (§5.2) is normally **symmetric**: a bean joined by a sync edge —
in *either* direction — to a main-thread bean is itself forced onto the main thread.
That conflates two cases:

* **In-flight entanglement (unsafe).** The neighbour is still being created on the main
  thread when this bean runs → real `BeanCurrentlyInCreationException` risk. Must stay
  mainline.
* **Completed-leaf dependency (safe).** The neighbour is a *terminal* infrastructure
  singleton (e.g. the `DataSource`) that the framework fully finishes on the main thread
  **before** this bean is touched, and this bean only *reads* it. Two such independent
  consumers can run concurrently on background threads.

The opt-in `backgroundSharedInfraConsumers` flag carves out the second case as a
narrowly-defined exception. A main-thread bean `B` is a **completed-leaf barrier** —
across which mainline-ness is *not* propagated to its dependents — only when both hold:

1. **Force-instantiated on the main thread & acyclic.** `B` is in the forced-mainline
   set (factory bean, `depends-on` target, or configuration class) and is not in any
   cycle, so the framework is guaranteed to create it on the main thread.
2. **Leaf with respect to background candidates.** `B` has no sync dependency on any
   currently-eligible bean, so its own construction cannot pull a background bean
   mid-creation.

Only the **B → dependent** direction is exempted; the reverse (a main-thread bean that
*depends on* a candidate) still propagates, because that is the genuine in-flight pull.
Because a dependent `D` keeps `B` as a construction dependency, `B` is ordered strictly
earlier in the topological layering (§ `computeLayers`), so "completed before fan-out"
holds structurally rather than by assumption. The barrier set only ever *removes*
propagation — it never adds edges — so it cannot create spurious cycles or perturb
layering. The relaxation is **off by default**; if the predicate is even slightly
violated the bean stays mainline, and the `BeanCurrentlyInCreationException` fast-fail
remains the backstop (design goal #1). For `@Bean` consumers such as Flyway/Liquibase,
enable `backgroundFactoryMethodBeans` as well — the two flags are orthogonal.

### 5.3 Consequences

* All **declaration-level** relationships are now modelled and made safe: explicit
  references plus by-type / `@Autowired` / `ObjectProvider` / collection autowiring.
  Within that scope, selection is provably safe — a bean is backgrounded only when its
  *visible* sync component is entirely backgroundable — and the bootstrap falls back to
  sequential when in doubt (design goal #1).
* **Accept-all is safe by default.** Co-locating `@Bean` factory-method beans whenever
  any *dynamic* configuration is present (§5.2.1) closes the dominant invisible-lookup
  channel, so the default accept-all `candidateFilter` boots a fully auto-configured
  Spring Boot application reliably. In a context with no dynamic configuration at all,
  `@Bean` beans — and the subtrees below them — background by default too. Applications
  also benefit from backgrounding their component-scanned beans, and may enable
  `backgroundFactoryMethodBeans`, `deferProviderEdges`, and/or
  `backgroundSharedInfraConsumers` for more parallelism where they know it is safe.
* **Narrow residual blind spot.** A bean that is *not* a factory-method bean (a
  component or plain definition) could still be pulled by type through a **direct
  `getBean(...)` from inside another bean's initialization code**. This is rare and
  application-specific; when it happens the bootstrap fails fast with
  `BeanCurrentlyInCreationException` rather than producing wrong results, and the
  affected bean can be excluded with a `candidateFilter` or `OPT_OUT_ATTRIBUTE`.

### 5.4 Possible future work

* **Opt-in `getBean`-from-bean-code scanning** to also cover the residual component
  blind spot (above), letting `backgroundFactoryMethodBeans(true)` be safe on more
  applications.
* **Refining `deferProviderEdges` (§5.2.2) to be safe by default** by distinguishing a
  field/setter `@Lazy`/`ObjectProvider` that is never read in a constructor (genuinely
  deferred) from an eager constructor-parameter dereference (must stay a hard sync
  edge), so the deferred-edge benefit could be enabled without the opt-in caveat.
* **Library-driven subtree instantiation** — grouping an eligible connected component
  and instantiating it as a unit on a dedicated worker (e.g. eagerly `getBean`-ing the
  component root on a bootstrap thread) instead of relying on Spring's per-bean executor
  submission. This was *assessed and intentionally deferred*: it duplicates the
  framework's creation-lock / lenient-handshake coordination, risks ordering and
  `SmartInitializingSingleton` interactions, and conflicts with design goal "build on
  framework primitives, don't invent a new bootstrap path." It is only worth pursuing if
  benchmarks show the per-bean submission path is the bottleneck for deep chains — and
  note that deep dependency *chains* do not parallelize even when backgrounded (each
  level waits on the next), so the real win is widening across independent siblings,
  which §5.2.1 (A) and §5.2.2 (B) already target. The
  `backgroundSharedInfraConsumers` relaxation (§5.2.3) is a complementary widening for
  the shared-infrastructure-leaf shape (independent consumers of one completed
  singleton).
* The `benchmark/` directory provides a reproducible Spring Petclinic startup
  harness; extending it to apps with many independent heavyweight beans would better
  quantify the win.

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

* **Build system:** Maven (Maven Wrapper pinned to 3.9.16). Standard `jar`
  packaging.
* **Coordinates:** `io.github.jdubois:spring-booster` (version in `pom.xml`).
* **Artifacts:** main jar, `-sources.jar`, `-javadoc.jar` and POM (the source and
  javadoc jars are attached during `package`). `./mvnw install` installs them to
  `~/.m2`.
* **Build JDK:** Java 17. The build must run on a JDK 17 because the Palantir Java
  Format engine used by Spotless runs under the build JDK.
* **Code formatting:** Spotless with Palantir Java Format
  (`./mvnw spotless:apply` to reformat; `spotless:check` is bound to the `verify`
  phase and fails the build on unformatted code).
* **Tests:** JUnit Jupiter + AssertJ (versions from the Boot BOM). Maven Surefire
  provides the JUnit Platform launcher automatically, so no extra launcher
  dependency is needed.
* **Test coverage today:** `BeanDependencyGraphTests` (graph/layers/cycles plus
  by-type, `@Autowired`, `ObjectProvider`, collection edges and the forced-vs-sync
  distinction), `ParallelBootstrapBeanFactoryPostProcessorTests` (candidate planning,
  infra exclusion, opt-out, and connectivity-safe exclusion of beans pulled by type
  from a main-thread bean), and `ParallelBootstrapIntegrationTests` (real context
  refresh, bean wiring, bootstrap-thread usage, executor shutdown, programmatic
  initializer, and an end-to-end reproduction proving a main-thread by-type consumer
  no longer triggers `BeanCurrentlyInCreationException`).

### 7.1 Conventions

* Code is formatted with **Spotless + Palantir Java Format** (4-space indent);
  run `./mvnw spotless:apply` before committing.
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
