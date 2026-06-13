#!/usr/bin/env bash
#
# setup.sh — prepare the JHipster sample-app startup benchmark.
#
# It (1) installs the spring-booster library into the local Maven repo, (2) clones
# the JHipster sample application at a pinned commit, (3) applies the Spring Booster
# integration patch, and (4) builds the runnable jar. After this you can run
# ./run-benchmark.sh.
#
# The JHipster sample app targets Java 21 (Spring Boot 4.0.x), so a JDK 21 (or
# newer) toolchain is required to build and run it. The frontend (npm/webpack)
# build is skipped via -P!webapp because the startup benchmark only exercises the
# backend; the app boots Docker-free under its default "dev" profile (in-memory H2).
#
# Re-running is safe: the JHipster checkout is recreated from scratch each time.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JHIPSTER_DIR="$SCRIPT_DIR/jhipster-sample-app"
PATCH="$SCRIPT_DIR/jhipster-spring-booster.patch"

# Pinned JHipster sample-app commit (tag v9.1.0; Spring Boot 4.0.6, Java 21).
JHIPSTER_REPO="https://github.com/jhipster/jhipster-sample-app.git"
JHIPSTER_COMMIT="00e253dc850af3efe0c0494474df6ea9f9586b62"

echo "==> 1/4 Installing spring-booster into the local Maven repository (~/.m2)"
# Spotless/Palantir formatting requires a JDK 17 build; skip the check here so the
# benchmark setup works on any JDK 17+ (we only need the installed artifact).
(cd "$REPO_ROOT" && ./mvnw -q -DskipTests -Dspotless.check.skip=true install)

echo "==> 2/4 Cloning JHipster sample app at $JHIPSTER_COMMIT"
rm -rf "$JHIPSTER_DIR"
git init -q "$JHIPSTER_DIR"
git -C "$JHIPSTER_DIR" remote add origin "$JHIPSTER_REPO"
git -C "$JHIPSTER_DIR" fetch -q --depth 1 origin "$JHIPSTER_COMMIT"
git -C "$JHIPSTER_DIR" checkout -q FETCH_HEAD

echo "==> 3/4 Applying Spring Booster integration patch"
git -C "$JHIPSTER_DIR" apply "$PATCH"

echo "==> 4/4 Building the JHipster jar (backend only; tests, frontend and enforcer skipped)"
# -P!webapp skips the npm/webpack frontend build; the startup benchmark is backend-only.
(cd "$JHIPSTER_DIR" && ./mvnw -q -DskipTests -Dspotless.check.skip=true -Denforcer.skip=true -P'!webapp' package)

echo
echo "Setup complete. Run the benchmark with:"
echo "    $SCRIPT_DIR/run-benchmark.sh 5"
