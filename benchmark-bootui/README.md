# BootUI sample-app startup benchmark

This directory measures how Spring Booster's opt-in **parallel bean instantiation**
affects the startup time of the [BootUI](https://github.com/jdubois/boot-ui)
**sample application** (`bootui-sample-app`, Spring Boot 4.1.0), run under its
default Docker-free `dev` profile (in-memory H2 + simple cache). It mirrors the
sibling [Spring Petclinic benchmark](../benchmark/README.md).

The benchmark enables Spring Booster with the **accept-all default** (no
`candidateFilter`). As on Petclinic, the library models by-type / `ObjectProvider`
autowiring, selects candidates in a connectivity-safe way, and keeps every `@Bean`
factory-method bean on the main thread (co-located with its `@Configuration`
class). The sample app boots cleanly with **19 component beans backgrounded**.

## TL;DR result

Two independent sessions of 8 measured runs each (16 runs per variant). All
values in seconds, lower is better.

| Variant | Median | Mean | Min | Max |
|---|---:|---:|---:|---:|
| Baseline (sequential bootstrap) | **9.539 s** | 9.549 s | 9.149 s | 9.928 s |
| Boosted (parallel bootstrap) | **10.357 s** | 10.294 s | 9.523 s | 10.820 s |

**On this application Spring Booster is a small but consistent regression:
about +0.8 s on the median (~+8%).** Unlike Petclinic — where the two variants
are indistinguishable and the sign of the tiny delta flips between sessions —
here the boosted runs were slower in **both** sessions, and in the second session
the two distributions did not even overlap (baseline max 9.928 s < boosted min
10.125 s). See [Why it is slower here](#why-it-is-slower-here).

(Hardware: 4-core x86-64 Linux runner, JDK 17. Re-run `./run-benchmark.sh` to
reproduce on your own machine; absolute numbers and the size of the delta will
vary.)

## Layout

| File | Purpose |
|---|---|
| `setup.sh` | Installs `spring-booster` to `~/.m2`, clones BootUI at a pinned commit, applies the patch, builds the `bootui-sample-app` jar (sample module only; BootUI deps come from Maven Central). |
| `bootui-spring-booster.patch` | The exact Spring Booster integration changes applied to the sample app. |
| `measure.sh` | Runs a jar N times and prints each reported "Started … in X seconds" value. |
| `run-benchmark.sh` | Runs 5× baseline + 5× boosted (plus a warm-up each) and prints the comparison table. |
| `boot-ui/` | The cloned + patched BootUI checkout (git-ignored; created by `setup.sh`). |

## How to run

```bash
./setup.sh            # one-time: install lib, clone + patch BootUI, build sample-app jar
./run-benchmark.sh 5  # 5 measured runs per variant (default 5)
```

No Docker is needed: the sample app's default `dev` profile uses an in-memory H2
database and a simple in-memory cache.

## How the two variants work

The **same jar** is used for both variants; only a runtime flag differs, so the
comparison is apples-to-apples (identical classpath, profile, and artifact).

* **Baseline** — run normally. Spring Booster is on the classpath but inactive.
* **Boosted** — run with `--sample.parallel-bootstrap=true`. A
  `SampleParallelBootstrapInitializer` registers the library's
  `ParallelBootstrapBeanFactoryPostProcessor`.

Startup time is taken from Spring Boot's own
`Started BootUiSampleApplication in X seconds` log line. Each variant runs one
discarded warm-up first to stabilise the OS file cache, then the measured runs.

## Why a property toggle instead of a `boost` profile?

Petclinic toggles the boosted variant with `--spring.profiles.active=boost`. That
does not work here: the sample app sets `spring.profiles.default=dev`, and `dev`
is what selects the Docker-free in-memory H2 database and simple cache. Activating
a `boost` profile would *replace* the default `dev` profile and break the
self-contained setup. So the boosted variant keeps `dev` active and flips a
plain property (`sample.parallel-bootstrap=true`) instead. The initializer also
honours a `boost` profile if you prefer to compose profiles (`dev,boost`).

## Why it is slower here

The mechanics are the same as on Petclinic, but the balance tips the other way.

Spring Booster's safe default backgrounds only **component-scanned beans** and
keeps every `@Bean` factory-method bean on the main thread. On the sample app the
**19 beans it backgrounds are all lightweight** — controllers (`HelloController`,
`AdminController`, `SampleController`, `ChatController`, …), the `EchoScheduler`,
`SampleCatalog`, Spring Data repository proxies, and assorted infrastructure
components. They are trivial to instantiate, so creating them concurrently saves
essentially no time.

Meanwhile the beans that **dominate** this app's startup are exactly the ones the
safe default leaves on the main thread:

* the `DataSource` / HikariCP pool,
* the JPA `EntityManagerFactory` and Hibernate bootstrap over ~15 `@Entity`
  types,
* **two** schema-migration engines running back to back — Flyway (the
  `catalog_*` tables) and Liquibase (the `inventory_*` tables),
* the embedded Tomcat container and Spring Security filter chain,
* the BootUI developer-console auto-configuration itself (it scans beans,
  conditions, mappings, configuration metadata, …).

All of that runs sequentially on the main thread in both variants. The boosted
variant therefore pays the **cost** of parallel bootstrap — building the
dependency graph, starting an 8-thread bootstrap pool, marking beans for
background init, and the thread scheduling/synchronisation around
`preInstantiateSingletons()` — while the 19 trivial beans it parallelises give
nothing back. On a 4-core runner those background threads also compete for CPU
with the heavy, CPU-bound main-thread work (Hibernate, Flyway, Liquibase),
adding a small but repeatable overhead.

**Conclusion:** the BootUI sample app is, if anything, a slightly *worse* fit for
parallel bootstrap than Petclinic. Its startup is even more thoroughly dominated
by un-parallelisable framework `@Bean` beans (JPA + Flyway + Liquibase + the
BootUI console), while only a handful of trivial component beans are eligible for
backgrounding — so Spring Booster cannot help and adds a touch of overhead.
As the main project README notes, the library is most useful for applications
with many *independent, heavyweight component beans* (or apps that knowingly
enable `backgroundFactoryMethodBeans` for their own safe `@Bean` beans), which
this sample is not.
