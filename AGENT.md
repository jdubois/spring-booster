# AGENT.md

Guidance for AI coding agents (and humans) working in this repository.
Read this first, then consult [SPECIFICATION.md](SPECIFICATION.md) for the
authoritative design rationale and [README.md](README.md) for user-facing usage.

## What this project is

**Spring Booster** is a small, standalone library that speeds up Spring
application startup by instantiating independent non-lazy singleton beans
**concurrently** during context refresh. It drives the Spring Framework's
existing background-initialization machinery
(`AbstractBeanDefinition.setBackgroundInit(...)` +
`ConfigurableBeanFactory.setBootstrapExecutor(...)`) via a conservative,
static dependency analysis. The feature is **strictly opt-in** and always
degrades gracefully to the normal sequential bootstrap.

## Project layout

- Single Java package: `io.github.jdubois.springbooster`
  (`src/main/java/io/github/jdubois/springbooster/`).
- Tests mirror that package under `src/test/java/...`.
- Maven build: `pom.xml`. The Maven Wrapper (`./mvnw`, pinned to Maven 3.9.16) is
  committed, so no local Maven install is needed.
- `README.md` — user-facing usage and install instructions.
- `SPECIFICATION.md` — the authoritative "what/why/how" design document.

| Class | Responsibility |
|---|---|
| `EnableParallelBootstrap` | Public opt-in annotation; `@Import`s the registrar. |
| `ParallelBootstrapRegistrar` | Registers the post-processor from annotation attributes. |
| `ParallelBootstrapApplicationContextInitializer` | Programmatic / `spring.factories` entry point. |
| `ParallelBootstrapSettings` | Immutable config (pool size, prefix, kill-switch, `candidateFilter`); fluent `Builder`. |
| `BeanDependencyGraph` | Dependency graph over the bean definitions: declared **and** by-type autowiring edges, classified forced vs sync. Kahn layering + Tarjan cycle detection. Never instantiates beans. |
| `AutowiredEdgeResolver` | Reflectively resolves by-type / `@Autowired` / `ObjectProvider` edges (no bean instantiation). |
| `ParallelBootstrapBeanFactoryPostProcessor` | The engine: connectivity-safe candidate planning, marks them, installs/tears down the executor. |
| `package-info.java` | `@NullMarked` package declaration + overview. |

## Build, test, and publish

Build with **Maven** via the committed wrapper (`./mvnw`); no local Maven install
is required. **Use a Java 17 JDK to build** (Spring Framework 7 / Spring Boot 4
baseline): the Palantir Java Format engine used by Spotless runs under the build
JDK, so set `JAVA_HOME` to a JDK 17 before building.

```bash
./mvnw verify          # compile + test + assemble jars + spotless:check
./mvnw test            # compile + run the JUnit Jupiter test suite only
./mvnw spotless:apply  # reformat all code (Spotless + Palantir Java Format)
./mvnw install         # install jar/sources/javadoc/POM into ~/.m2
```

Expectation when your change is complete: `./mvnw verify` is GREEN — all 34 tests
pass (`BeanDependencyGraphTests`, `ParallelBootstrapBeanFactoryPostProcessorTests`,
`ParallelBootstrapIntegrationTests`) **and** `spotless:check` passes. If the
formatting check fails, run `./mvnw spotless:apply` and re-run.

## Dependency / version policy

- Do **not** hard-code the Spring Framework version. The build imports the
  `org.springframework.boot:spring-boot-dependencies` BOM (see the
  `spring-boot.version` property in `pom.xml`); all Spring/JSpecify/JUnit/AssertJ
  versions come from it.
- To move to a newer Spring baseline, bump the **single** BOM coordinate.
- The only runtime dependencies are `spring-context` and JSpecify annotations.

## Conventions (follow these in any change)

- Code is formatted with **Spotless + Palantir Java Format** (4-space indent).
  Run `./mvnw spotless:apply` before committing; never hand-format against it.
- **Apache License 2.0 header** on every `.java` file **except**
  `package-info.java` (which carries only package Javadoc + `@NullMarked`).
- Null-safety via **JSpecify**: `@NullMarked` at package level, `@Nullable` on
  nullable members.
- Keep the public surface minimal: `EnableParallelBootstrap`,
  `ParallelBootstrapApplicationContextInitializer`, `ParallelBootstrapSettings`,
  `ParallelBootstrapBeanFactoryPostProcessor`.
- Only comment code that genuinely needs clarification.

## Design rules that must not be broken

1. **Safety first** — enabling the feature must never change application
   semantics. When in doubt, a bean is *not* parallelized.
2. **Strictly opt-in** — nothing is parallelized unless the user enables it.
3. **Graceful degradation** — any planning failure falls back to the normal
   sequential bootstrap (global kill-switch + defensive `try/catch`).
4. `BeanDependencyGraph` performs **pure analysis** and must never trigger bean
   creation.

## Candidate selection — by-type autowiring is modelled and made safe

`BeanDependencyGraph` models **both** declared references (`depends-on`, factory-bean
refs, constructor/property `BeanReference`s) **and** by-type / `@Autowired` /
`ObjectProvider` autowiring edges (resolved reflectively by `AutowiredEdgeResolver`,
without instantiating beans). Edges are classified **forced** (`depends-on`,
factory-bean — eagerly created on the main thread) vs **sync** (everything else —
resolved on the bean's own thread).

`planCandidates` is **connectivity-safe**: it seeds the main-thread set with every
structurally-ineligible bean (infra, cyclic, factory beans, `depends-on` targets,
opted-out, filtered-out, lazy/non-singleton) and propagates that across **sync** edges
to a fixpoint. A bean is backgrounded only when no sync edge connects it — in either
direction — to a main-thread bean. This makes parallelization safe for every
dependency that is *visible* in the graph. See **§5 of SPECIFICATION.md** for the
correctness argument.

When touching selection: keep the forced-vs-sync edge classification intact and never
weaken the propagation. **Known residual blind spot:** dependencies that are not
visible at a definition/injection-point level — most importantly a **direct
`getBean(...)` call from inside bean code** (e.g. Spring Data's
`SpringDataWebConfiguration` lazily pulls `sortResolver` from its
`addArgumentResolvers` callback on the main thread). Because of this the default
accept-all `candidateFilter` is **not safe** on a fully auto-configured Spring Boot
web app; the `benchmark/` Petclinic harness therefore scopes the filter to the
application's own packages. Default behaviour is intentionally conservative;
`candidateFilter` and `OPT_OUT_ATTRIBUTE` are the levers to widen or
narrow the set.

## When in doubt

- Prefer correctness/safety over speed; never weaken the conservative candidate
  selection without a corresponding test proving safety.
- Add or update tests under `src/test/java/...` for any behavioral change and
  keep `./gradlew build` green.
