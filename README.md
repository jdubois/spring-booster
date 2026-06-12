# Spring Booster

**Opt-in parallel bean instantiation for the Spring application context bootstrap.**

Spring Booster speeds up application startup by instantiating independent
non-lazy singleton beans **concurrently** during context refresh, instead of one
after another on a single thread. It is a thin, self-contained add-on that builds
on the *background bean initialization* machinery already present in the Spring
Framework (`AbstractBeanDefinition.setBackgroundInit(...)` and the bean factory
*bootstrap executor*), and drives it automatically through a conservative
dependency analysis.

The feature is **strictly opt-in**: nothing is parallelized unless you enable it
explicitly, and it always degrades gracefully to the normal sequential bootstrap
if anything goes wrong.

> ⚠️ **Important — read the [SPECIFICATION.md](SPECIFICATION.md) before enabling
> this in a Spring Boot application.** The dependency analysis only sees
> *explicit* bean references and cannot see by-type / `ObjectProvider`
> autowiring. With the permissive default candidate selection this can mark beans
> for background initialization that Spring Boot auto-configuration then requests
> on the main thread, causing a `BeanCurrentlyInCreationException` at startup. Use
> a `candidateFilter` to restrict parallelization to beans you know are safe.

---

## What it does

* Builds an **approximate, conservative dependency graph** of the registered
  non-lazy singleton bean definitions, using only statically introspectable
  references (`depends-on`, factory-bean references, and constructor/property
  `BeanReference`s).
* Identifies **independent "leaf" beans** that are safe to create concurrently
  (excluding beans in dependency cycles, shared factory beans, framework
  infrastructure beans such as `BeanPostProcessor`s, and anything you opt out).
* Marks those beans for background initialization and installs a **bounded
  bootstrap thread pool** (sized by default at twice the available processor
  count) that the bean factory uses during `preInstantiateSingletons()`.
* **Shuts the pool down** automatically once the context has refreshed, so it does
  not linger for the lifetime of the application.
* Falls back to the **normal sequential bootstrap** whenever the feature is
  disabled, when no beans are eligible, or when planning fails for any reason
  (global kill-switch + defensive try/catch).

## How to use it

### Annotation-based (most common)

```java
@Configuration
@EnableParallelBootstrap
public class AppConfig {
}
```

Tuning attributes are available:

```java
@EnableParallelBootstrap(poolSize = 8, threadNamePrefix = "boot-", enabled = true)
```

### Programmatic (e.g. Spring Boot, or any code that builds the context)

```java
var context = new AnnotationConfigApplicationContext();
new ParallelBootstrapApplicationContextInitializer().initialize(context);
context.register(AppConfig.class);
context.refresh();
```

In Spring Boot 4 the initializer can be registered via `META-INF/spring.factories`:

```
org.springframework.context.ApplicationContextInitializer=\
io.github.jdubois.springbooster.ParallelBootstrapApplicationContextInitializer
```

### Full control with custom settings

```java
ParallelBootstrapSettings settings = ParallelBootstrapSettings.builder()
        .poolSize(8)
        .threadNamePrefix("boot-")
        .candidateFilter(beanName -> beanName.startsWith("com.example."))
        .build();

context.addBeanFactoryPostProcessor(
        new ParallelBootstrapBeanFactoryPostProcessor(settings));
```

You can also opt a single bean definition out of background initialization:

```java
beanDefinition.setAttribute(ParallelBootstrapSettings.OPT_OUT_ATTRIBUTE, Boolean.TRUE);
```

## Requirements

| | Version |
|---|---|
| Java | 17 or later |
| Spring Framework | 7.0.8 (the stable release used by **Spring Boot 4.1.0**) |

Spring Booster does not pin the Spring Framework version directly. Instead it
imports the `org.springframework.boot:spring-boot-dependencies:4.1.0` platform
BOM, so the Spring Framework version (and the versions of all other dependencies)
always match exactly what Spring Boot 4.1.0 ships. Bumping the Boot line in
`build.gradle` is the supported way to move to a newer Spring baseline.

## How to build

```bash
./gradlew build
```

This compiles the code, runs Checkstyle-free JavaDoc, assembles the `jar`,
`-sources.jar` and `-javadoc.jar`, and runs the test suite.

To assemble the artifacts without running the tests:

```bash
./gradlew assemble
```

## How to test

```bash
./gradlew test
```

The test suite (JUnit Jupiter + AssertJ) covers the dependency-graph analysis,
the candidate-planning logic of the post-processor, and full integration tests
that refresh a real application context with parallel bootstrap enabled and
assert that beans are wired correctly and created on bootstrap threads.

## How to install

### Install into the local Maven repository (`~/.m2`)

```bash
./gradlew publishToMavenLocal
```

This publishes `io.github.jdubois:spring-booster:0.1.0-SNAPSHOT` (jar, sources,
javadoc and POM) so other local projects can depend on it.

### Consume it

**Gradle**

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.jdubois:spring-booster:0.1.0-SNAPSHOT'
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.jdubois</groupId>
    <artifactId>spring-booster</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
