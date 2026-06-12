# Spring Petclinic startup benchmark

This directory measures how Spring Booster's opt-in **parallel bean instantiation**
affects the startup time of the canonical [Spring
Petclinic](https://github.com/spring-projects/spring-petclinic) application
(Spring Boot 4.0.3).

The benchmark enables Spring Booster with a `candidateFilter` scoped to Petclinic's
own packages (`org.springframework.samples.petclinic.*`). Earlier versions of the
library were blind to by-type / `ObjectProvider` autowiring and required hand-picking
individual dependency-free beans; the connectivity-safe selection added in
SPECIFICATION.md §5 means a simple package scope is now enough — any of your beans
that is connected to a main-thread bean is pulled back to the main thread
automatically. (A *fully* accept-all filter is still not safe here — see
[Why a package scope and not accept-all](#why-a-programmatic-initializer-instead-of-enableparallelbootstrap).)

## TL;DR result

| Variant | Median startup | Mean startup |
|---|---:|---:|
| Baseline (sequential bootstrap) | **3.460 s** | 3.463 s |
| Boosted (parallel bootstrap) | **3.547 s** | 3.554 s |

The two variants are **indistinguishable within measurement noise**. Across
repeated benchmark sessions the sign of the difference flips — one session had the
boosted run ~3.8% *faster*, this one had it ~2.5% *slower* — which is the clearest
evidence that there is no real effect on this application. See
[Why the difference is negligible](#why-the-difference-is-negligible).

(Hardware: Apple Silicon, JDK 17. Re-run `./run-benchmark.sh` to reproduce on your
own machine; absolute numbers and the sign of the tiny delta will vary.)

## Layout

| File | Purpose |
|---|---|
| `setup.sh` | Installs `spring-booster` to `~/.m2`, clones Petclinic at a pinned commit, applies the patch, builds the jar. |
| `petclinic-spring-booster.patch` | The exact Spring Booster integration changes applied to Petclinic. |
| `measure.sh` | Runs a jar N times and prints each reported "Started … in X seconds" value. |
| `run-benchmark.sh` | Runs 5× baseline + 5× boosted (plus a warm-up each) and prints the comparison table. |
| `spring-petclinic/` | The cloned + patched Petclinic checkout (git-ignored; created by `setup.sh`). |

## How to run

```bash
./setup.sh            # one-time: install lib, clone + patch Petclinic, build jar
./run-benchmark.sh 5  # 5 measured runs per variant (default 5)
```

## How the two variants work

The **same jar** is used for both variants; only a runtime flag differs, so the
comparison is apples-to-apples (identical classpath and artifact).

* **Baseline** — run normally. Spring Booster is on the classpath but inactive.
* **Boosted** — run with `--spring.profiles.active=boost` (or
  `--petclinic.parallel-bootstrap=true`). A
  `PetClinicParallelBootstrapInitializer` registers the library's
  `ParallelBootstrapBeanFactoryPostProcessor`.

Startup time is taken from Spring Boot's own
`Started PetClinicApplication in X seconds` log line. Each variant runs one
discarded warm-up first to stabilise the OS file cache, then the measured runs.

## Why a programmatic initializer instead of `@EnableParallelBootstrap`?

The benchmark drives the library through a `PetClinicParallelBootstrapInitializer`
rather than the `@EnableParallelBootstrap` annotation **purely so the same jar can
serve both variants**: the initializer only registers the post-processor when the
`boost` profile is active (or `petclinic.parallel-bootstrap=true`), so the baseline
and boosted runs share an identical artifact and classpath. The annotation, by
contrast, is always on and cannot be toggled at runtime.

It registers the post-processor with a `candidateFilter` scoped to Petclinic's own
packages (`org.springframework.samples.petclinic.*`). This is deliberately **not**
the accept-all default. The library's dependency analysis now models by-type /
`@Autowired` / `ObjectProvider` autowiring and selects candidates in a
**connectivity-safe** way (see `SPECIFICATION.md` §5 in the repo root): any bean
connected — directly or transitively — to a bean that must run on the main thread is
itself kept on the main thread. That is enough to make the *package scope* safe and
ergonomic — unlike earlier versions, the benchmark no longer has to hand-pick
individual dependency-free beans.

A *fully accept-all* filter, however, still fails on Petclinic. The remaining gap is
a dependency that no static graph can see — a **direct `getBean(...)` from inside bean
code**: Spring Data's `SpringDataWebConfiguration` captures the `ApplicationContext`
and, from its `WebMvcConfigurer.addArgumentResolvers(...)` callback, lazily calls
`getBean(SortHandlerMethodArgumentResolver.class)` on the **main** thread. With
accept-all that resolver gets backgrounded, and the framework then throws:

```
BeanCurrentlyInCreationException: Error creating bean with name 'sortResolver':
Bean marked for background initialization but requested in mainline thread …
```

Scoping the filter to the application's own packages excludes that framework bean, so
Petclinic boots reliably and the library reports:

```
Parallel bootstrap enabled for 3 bean(s) using a pool of M thread(s)
```

## Why the difference is negligible

Petclinic has very few application beans, and almost all of them depend (by type) on
the Spring Data repositories — which **must** be created on the main thread. The
connectivity-safe selection follows those edges and pulls the dependent beans onto the
main thread automatically, leaving only three dependency-free "leaf" beans
(`petValidator`, `welcomeController`, `crashController`) eligible for concurrent
creation. These are trivial to instantiate, so parallelizing them saves no meaningful
time, while the bulk of startup cost (Hibernate/JPA, the embedded servlet container,
SQL initialization) lives in framework beans that cannot be safely backgrounded.

**Conclusion:** on an application like Petclinic, Spring Booster cannot safely
parallelize the beans that actually dominate startup, so it neither helps nor
hurts in any statistically meaningful way. The library is most useful for
applications with many *independent, heavyweight application beans* — not for an
app whose startup is dominated by framework auto-configuration.
