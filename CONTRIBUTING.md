# Contributing to Spring Booster

Thanks for your interest in improving Spring Booster! This document explains how
to get a working development environment and submit changes. For the design
rationale and architecture, read [SPECIFICATION.md](SPECIFICATION.md); for a
quick orientation aimed at contributors and AI agents, see [AGENT.md](AGENT.md).

## Prerequisites

- **Java 17**. The build is pinned to the Java 17 baseline because the Palantir
  Java Format engine used by Spotless runs under the build JDK. Point
  `JAVA_HOME` at a JDK 17 before building.
- **Maven Wrapper**. Use the committed `./mvnw` script; no system Maven
  installation is required.
- The project targets **Spring Boot 4.1.0 / Spring Framework 7**. All dependency
  versions are inherited from the Spring Boot BOM imported in `pom.xml`.

## Project layout

All production code lives in a single package, `io.github.jdubois.springbooster`,
under `src/main/java`; tests mirror it under `src/test/java`. See the class table
in [AGENT.md](AGENT.md#project-layout) for the role of each type.

## Build

```bash
./mvnw clean install
```

This compiles the code, runs the test suite, assembles the `jar`, `-sources.jar`
and `-javadoc.jar`, verifies formatting, and installs the artifacts into your
local `~/.m2` repository.

Before opening or updating a pull request, run the CI-equivalent build:

```bash
./mvnw -B -ntp clean install
```

## Testing

```bash
./mvnw test
```

The suite (JUnit Jupiter + AssertJ) covers the dependency-graph analysis, the
candidate-planning logic of the post-processor, and integration tests that
refresh a real application context with parallel bootstrap enabled. Add or update
tests for any behavioural change — and never weaken the conservative candidate
selection without a test that proves the change is safe.

## Formatting

Java code is formatted with [Spotless](https://github.com/diffplug/spotless) using
[Palantir Java Format](https://github.com/palantir/palantir-java-format). Reformat
before committing, and verify:

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

`spotless:check` is bound to the `verify` phase, so `./mvnw verify` (and
`install`) will fail on unformatted code.

## Coding conventions

- Code style is enforced by Spotless/Palantir (4-space indent) — let the
  formatter decide; do not hand-format against it.
- Apache License 2.0 header on every `.java` file **except** `package-info.java`.
- Null-safety via JSpecify (`@NullMarked` at package level, `@Nullable` on
  nullable members).
- Keep the public surface minimal and only comment code that needs clarification.

## Design rules that must not be broken

1. **Safety first** — enabling the feature must never change application
   semantics; when in doubt, a bean is *not* parallelized.
2. **Strictly opt-in** — nothing is parallelized unless the user enables it.
3. **Graceful degradation** — any planning failure falls back to the normal
   sequential bootstrap.

See [§5 of SPECIFICATION.md](SPECIFICATION.md) for the critical by-type /
`ObjectProvider` autowiring limitation before changing candidate selection.

## Submitting a change

1. Open or claim an issue describing the change before you write code.
2. Create a topic branch off `main`. Branch names should start with your
   GitHub username (e.g. `jdubois/improve-candidate-selection`).
3. Keep PRs small and focused. Update the docs whenever public behaviour changes.
4. Run `./mvnw -B -ntp clean install` before pushing — it must be green.

## Reporting bugs and security issues

- **Bugs**: open a GitHub issue with a minimal reproduction.
- **Security vulnerabilities**: do **not** open a public issue. Use GitHub's
  private vulnerability reporting from the repository's **Security** tab.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
