# Spring Petclinic startup benchmark

This directory measures how Spring Booster's opt-in **parallel bean instantiation**
affects the startup time of the canonical [Spring
Petclinic](https://github.com/spring-projects/spring-petclinic) application
(Spring Boot 4.0.3).

## TL;DR result

| Variant | Median startup | Mean startup |
|---|---:|---:|
| Baseline (sequential bootstrap) | **3.318 s** | 3.308 s |
| Boosted (parallel bootstrap) | **3.354 s** | 3.333 s |

The two variants are **indistinguishable within measurement noise**. Across
repeated benchmark sessions the sign of the difference flips — one session had the
boosted run ~3.8% *faster*, the next had it ~1.1% *slower* — which is the clearest
evidence that there is no real effect on this application. See
[Why the difference is negligible](#why-the-difference-is-negligible).

(Hardware: Apple Silicon, JDK 26. Re-run `./run-benchmark.sh` to reproduce on your
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

## Why not the plain `@EnableParallelBootstrap` annotation?

The library's plain annotation uses an **accept-all** candidate filter, which is
unsafe on a typical Spring Boot app and is a documented limitation (see
`SPECIFICATION.md` §5 in the repo root). Enabling it wholesale on Petclinic fails
at startup:

```
BeanCurrentlyInCreationException: Error creating bean with name 'ownerRepository':
Requested bean is currently in creation …
```

The static dependency graph cannot see the **by-type / `ObjectProvider`**
autowiring that Spring Boot's auto-configuration uses (e.g. the Spring Data JPA
repositories are resolved by type on the main thread). The library therefore marks
beans for background initialization that are then needed synchronously on the main
thread.

Following the library's **recommended mitigation**, the benchmark instead
configures it programmatically with a `candidateFilter` that restricts
parallelization to Petclinic's own **dependency-free** beans
(`PetClinicParallelBootstrapInitializer`). With that filter the application boots
reliably and the library reports:

```
Parallel bootstrap enabled for 3 bean(s) using a pool of N thread(s)
```

## Why the difference is negligible

Petclinic has very few application beans, and almost all of them depend on the
Spring Data repositories — which **must** be created on the main thread (see
above). That leaves only three dependency-free "leaf" beans
(`petValidator`, `welcomeController`, `crashController`) that are safe to create
concurrently. These are trivial to instantiate, so parallelizing them saves no
meaningful time, while the bulk of startup cost (Hibernate/JPA, the embedded
servlet container, SQL initialization) lives in framework beans that cannot be
safely backgrounded.

**Conclusion:** on an application like Petclinic, Spring Booster cannot safely
parallelize the beans that actually dominate startup, so it neither helps nor
hurts in any statistically meaningful way. The library is most useful for
applications with many *independent, heavyweight application beans* — not for an
app whose startup is dominated by framework auto-configuration.
