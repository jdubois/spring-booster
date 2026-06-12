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
- Gradle build: `build.gradle` (module config) + `settings.gradle`
  (project name + Foojay toolchain resolver).
- `README.md` — user-facing usage and install instructions.
- `SPECIFICATION.md` — the authoritative "what/why/how" design document.

| Class | Responsibility |
|---|---|
| `EnableParallelBootstrap` | Public opt-in annotation; `@Import`s the registrar. |
| `ParallelBootstrapRegistrar` | Registers the post-processor from annotation attributes. |
| `ParallelBootstrapApplicationContextInitializer` | Programmatic / `spring.factories` entry point. |
| `ParallelBootstrapSettings` | Immutable config (pool size, prefix, kill-switch, `candidateFilter`); fluent `Builder`. |
| `BeanDependencyGraph` | Pure static dependency graph: Kahn layering + Tarjan cycle detection. Never instantiates beans. |
| `ParallelBootstrapBeanFactoryPostProcessor` | The engine: plans candidates, marks them, installs/tears down the executor. |
| `package-info.java` | `@NullMarked` package declaration + overview. |

## Build, test, and publish

This project targets a **Java 17 toolchain** (Spring Framework 7 / Spring Boot 4
baseline). You do **not** need Java 17 as your default JDK — `settings.gradle`
applies the Foojay toolchain resolver, so Gradle auto-downloads a matching JDK 17
if none is detected locally. Always use the bundled wrapper (`./gradlew`).

```bash
./gradlew build              # compile + javadoc + assemble jars + run tests
./gradlew test               # run the JUnit Jupiter test suite only
./gradlew assemble           # build artifacts without running tests
./gradlew publishToMavenLocal # install jar/sources/javadoc/POM into ~/.m2
```

If a local JDK 17 exists but is not auto-detected, point Gradle at it:

```bash
./gradlew build -Dorg.gradle.java.installations.paths=/path/to/jdk-17
```

Expectation when your change is complete: `./gradlew build` is GREEN and all
tests pass (currently `BeanDependencyGraphTests`,
`ParallelBootstrapBeanFactoryPostProcessorTests`,
`ParallelBootstrapIntegrationTests`). The Javadoc step emits a few `no @param /
no @return` warnings on `Builder` methods — these are pre-existing and not
failures.

## Dependency / version policy

- Do **not** hard-code the Spring Framework version. The build imports the
  `org.springframework.boot:spring-boot-dependencies` BOM (see `springBootBomVersion`
  in `build.gradle`); all Spring/JSpecify/JUnit/AssertJ versions come from it.
- To move to a newer Spring baseline, bump the **single** BOM coordinate.
- The only runtime dependencies are `spring-context` and JSpecify annotations.

## Conventions (follow these in any change)

- **Apache License 2.0 header** on every `.java` file **except**
  `package-info.java` (which carries only package Javadoc + `@NullMarked`).
- Null-safety via **JSpecify**: `@NullMarked` at package level, `@Nullable` on
  nullable members.
- Indentation is **tabs** (match the surrounding files and `build.gradle`).
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

## Critical known limitation (read before touching candidate selection)

The static dependency graph only models **explicit** bean references
(`depends-on`, factory-bean refs, constructor/property `BeanReference`s). It is
**blind to by-type / `@Autowired` / `ObjectProvider` autowiring**. With the
default permissive `candidateFilter`, this makes the library **unsafe to enable
wholesale on a typical Spring Boot app** (e.g. it triggered a
`BeanCurrentlyInCreationException` in Spring Petclinic). Current mitigation is
operational: restrict candidates with `candidateFilter` or opt beans out via
`ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE`. See **§5 of SPECIFICATION.md**
for the full analysis and the open design space for a real fix — this is the
highest-value area for future work.

## When in doubt

- Prefer correctness/safety over speed; never weaken the conservative candidate
  selection without a corresponding test proving safety.
- Add or update tests under `src/test/java/...` for any behavioral change and
  keep `./gradlew build` green.
