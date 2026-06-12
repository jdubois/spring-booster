# Spring Petclinic startup benchmark

This directory measures how Spring Booster's opt-in **parallel bean instantiation**
affects the startup time of the canonical [Spring
Petclinic](https://github.com/spring-projects/spring-petclinic) application
(Spring Boot 4.0.3).

The benchmark enables Spring Booster with the **accept-all default** (no
`candidateFilter`). The library models by-type / `ObjectProvider` autowiring and
selects candidates in a connectivity-safe way, and — crucially — keeps every `@Bean`
factory-method bean on the main thread (co-located with its `@Configuration` class).
Those two rules make accept-all **safe** on this fully auto-configured Spring Boot app:
the context boots cleanly with 56 component beans backgrounded. (Earlier versions
needed a hand-picked or package-scoped filter; that is no longer required — see
[Why accept-all is safe here](#why-accept-all-is-safe-here).)

## TL;DR result

| Variant | Median startup | Mean startup |
|---|---:|---:|
| Baseline (sequential bootstrap) | **3.472 s** | 3.485 s |
| Boosted (parallel bootstrap) | **3.539 s** | 3.529 s |

The two variants are **indistinguishable within measurement noise**. Across
repeated benchmark sessions the sign of the difference flips — one session had the
boosted run faster, another had it ~1.9% slower — which is the clearest evidence that
there is no real effect on this application. See
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

It registers the post-processor with **default settings** (accept-all — no
`candidateFilter`).

## Why accept-all is safe here

A naive accept-all would background framework infrastructure beans and fail. The
classic failure on Petclinic is a dependency **no static graph can see** — a direct
`getBean(...)` from inside bean code: Spring Data's `SpringDataWebConfiguration`
captures the `ApplicationContext` and, from its
`WebMvcConfigurer.addArgumentResolvers(...)` callback, lazily resolves
`sortResolver` *and* `sortCustomizer` **by type, on the main thread**. If either were
backgrounded the framework would throw:

```
BeanCurrentlyInCreationException: Error creating bean with name 'sortResolver':
Bean marked for background initialization but requested in mainline thread …
```

Spring Booster avoids this by **never backgrounding `@Bean` factory-method beans by
default**: each is co-located with its (always main-thread) `@Configuration` class.
`sortResolver`, `sortCustomizer` and every other auto-configuration `@Bean` therefore
stay on the main thread, and the targets of those invisible by-type lookups are always
available. Only component-scanned beans (controllers, the validator, …) are
backgrounded. Petclinic boots reliably and the library reports:

```
Parallel bootstrap enabled for 56 bean(s) using a pool of M thread(s)
```

(To also background `@Bean` beans for maximum parallelism, the initializer could set
`backgroundFactoryMethodBeans(true)` — but on a fully auto-configured app that
reintroduces the invisible-lookup risk and would need a curated `candidateFilter`.)

## Why the difference is negligible

Even with 56 beans backgrounded, the win is negligible because the beans that
**dominate** Petclinic's startup cost — the `DataSource`, the
`EntityManagerFactory`/Hibernate, the embedded servlet container, SQL initialization —
are all framework `@Bean` factory-method beans, which the safe default keeps on the
main thread. The 56 beans that *are* parallelized are lightweight component beans
(controllers, the validator, and assorted infrastructure components) that are trivial
to instantiate, so creating them concurrently saves no meaningful time.

**Conclusion:** on an application like Petclinic, the expensive beans are exactly the
`@Bean` beans that cannot be safely backgrounded, so Spring Booster neither helps nor
hurts in any statistically meaningful way. The library is most useful for applications
with many *independent, heavyweight component beans* — or applications that knowingly
enable `backgroundFactoryMethodBeans` for their own safe `@Bean` beans — not for an app
whose startup is dominated by framework auto-configuration.
