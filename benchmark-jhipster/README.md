# JHipster sample-app startup benchmark

This directory measures how Spring Booster's opt-in **parallel bean instantiation**
affects the startup time of the canonical
[JHipster sample application](https://github.com/jhipster/jhipster-sample-app)
(tag `v9.1.0`, Spring Boot 4.0.6, Java 21).

The benchmark enables Spring Booster with the **accept-all default** (no
`candidateFilter`). The library models by-type / `ObjectProvider` autowiring and
selects candidates in a connectivity-safe way, and — crucially — keeps every `@Bean`
factory-method bean on the main thread (co-located with its `@Configuration` class).
Those two rules make accept-all **safe** on this app: the context boots cleanly with
20 component beans backgrounded.

## TL;DR result

| Variant | Median startup | Mean startup |
|---|---:|---:|
| Baseline (sequential bootstrap) | **10.236 s** | 10.303 s |
| Boosted (parallel bootstrap) | **10.489 s** | 10.441 s |

The two variants are **indistinguishable within measurement noise** — here the boosted
run was ~2.5% slower on the median, well inside the run-to-run spread, and the sign of
the tiny delta flips between sessions. This mirrors the Spring Petclinic result: the
beans that dominate JHipster's startup cost are framework `@Bean` factory-method beans
(see [Why the difference is negligible](#why-the-difference-is-negligible)).

(Hardware: x86-64 Linux CI runner, JDK 21. Re-run `./run-benchmark.sh` to reproduce on
your own machine; absolute numbers and the sign of the tiny delta will vary.)

## Layout

| File | Purpose |
|---|---|
| `setup.sh` | Installs `spring-booster` to `~/.m2`, clones the JHipster sample app at a pinned commit, applies the patch, builds the jar (backend only). |
| `jhipster-spring-booster.patch` | The exact Spring Booster integration changes applied to the JHipster sample app. |
| `measure.sh` | Runs a jar N times and prints each reported "Started JhipsterSampleApplicationApp in X seconds" value. |
| `run-benchmark.sh` | Runs 5× baseline + 5× boosted (plus a warm-up each) and prints the comparison table. |
| `jhipster-sample-app/` | The cloned + patched JHipster checkout (git-ignored; created by `setup.sh`). |

## Requirements

* **JDK 21+** on the `PATH` — the JHipster sample app targets Java 21. (The
  `spring-booster` artifact itself targets Java 17 bytecode and runs fine on 21.)
* No Docker engine is needed: the app runs under its default **`dev`** profile with an
  in-memory H2 database.

## How to run

```bash
./setup.sh            # one-time: install lib, clone + patch JHipster, build jar
./run-benchmark.sh 5  # 5 measured runs per variant (default 5)
```

## How the two variants work

The **same jar** is used for both variants; only the active Spring profiles differ, so
the comparison is apples-to-apples (identical classpath and artifact).

* **Baseline** — run with `--spring.profiles.active=dev`. Spring Booster is on the
  classpath but inactive.
* **Boosted** — run with `--spring.profiles.active=dev,boost`. A
  `JhipsterParallelBootstrapInitializer` registers the library's
  `ParallelBootstrapBeanFactoryPostProcessor` when the `boost` profile is active.
* **Boosted + Web profile** — run with `--spring.profiles.active=dev,boost,boost-web`
  (or add `--spring-boot-web-profile=true` to the boosted run). This additionally
  enables the opinionated **Spring Boot Web profile** (`springBootWebProfile=true`),
  which consults a curated registry of well-known web/security/cache auto-config beans
  and frees them from `@Bean` co-location so they may overlap with the main-thread
  JPA/migration work. It targets the canonical Boot Web architecture directly instead
  of relying solely on the generic connectivity graph.

Startup time is taken from Spring Boot's own
`Started JhipsterSampleApplicationApp in X seconds` log line. Each variant runs one
discarded warm-up first to stabilise the OS file cache, then the measured runs.

## Why a programmatic initializer instead of `@EnableParallelBootstrap`?

The benchmark drives the library through a `JhipsterParallelBootstrapInitializer`
rather than the `@EnableParallelBootstrap` annotation **purely so the same jar can
serve both variants**: the initializer only registers the post-processor when the
`boost` profile is active (or `parallel-bootstrap=true`), so the baseline and boosted
runs share an identical artifact and classpath. The annotation, by contrast, is always
on and cannot be toggled at runtime.

> Note: JHipster binds the `jhipster.*` configuration prefix with
> `ignoreUnknownFields=false`, so the toggle property is the un-prefixed
> `parallel-bootstrap` (not `jhipster.parallel-bootstrap`), which would otherwise fail
> JHipster's strict property binding.

It registers the post-processor with **default settings** (accept-all — no
`candidateFilter`).

## Why accept-all is safe here

A naive accept-all would background framework infrastructure beans and fail (for
example Spring Data's `SpringDataWebConfiguration`, which captures the
`ApplicationContext` and lazily resolves `sortResolver`/`sortCustomizer` by type on the
main thread). Spring Booster avoids this by **never backgrounding `@Bean`
factory-method beans by default**: each is co-located with its (always main-thread)
`@Configuration` class. Only component-scanned beans (controllers, services,
repositories wrappers, mappers, …) are backgrounded. The app boots reliably and the
library reports:

```
Parallel bootstrap enabled for 20 bean(s) using a pool of M thread(s)
```

## Why the difference is negligible

Even with 20 beans backgrounded, the win is negligible because the beans that
**dominate** JHipster's startup cost are framework `@Bean` factory-method beans, which
the safe default keeps on the main thread:

* the `DataSource` and Hibernate `EntityManagerFactory` (JPA),
* Liquibase database migration,
* the embedded servlet container,
* the cache manager, security, and Jackson/web infrastructure.

The 20 beans that *are* parallelized are lightweight component beans (controllers,
services, MapStruct mappers, and assorted infrastructure components) that are trivial
to instantiate, so creating them concurrently saves no meaningful time — and the small
overhead of scheduling them onto a thread pool can even make the boosted run a touch
slower.

**Conclusion:** on a fully auto-configured application like the JHipster sample app, the
expensive beans are exactly the `@Bean` beans that cannot be safely backgrounded, so
Spring Booster neither helps nor hurts in any statistically meaningful way. The library
is most useful for applications with many *independent, heavyweight component beans* —
or applications that knowingly enable `backgroundFactoryMethodBeans` for their own safe
`@Bean` beans — not for an app whose startup is dominated by framework
auto-configuration and JPA/Liquibase.
