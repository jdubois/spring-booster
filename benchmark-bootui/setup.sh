#!/usr/bin/env bash
#
# setup.sh — prepare the BootUI sample-app startup benchmark.
#
# It (1) installs the spring-booster library into the local Maven repo, (2) clones
# the BootUI repository at a pinned commit, (3) applies the Spring Booster
# integration patch to the bootui-sample-app module, and (4) builds the runnable
# jar. After this you can run ./run-benchmark.sh.
#
# Only the bootui-sample-app module is built; its BootUI dependencies (parent,
# starter, …) are resolved from Maven Central (release 1.4.0), so the heavy
# frontend/reactor build is skipped. The sample app runs Docker-free under its
# default "dev" profile (in-memory H2 + simple cache), so no Docker engine is
# needed.
#
# Re-running is safe: the BootUI checkout is recreated from scratch each time.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BOOTUI_DIR="$SCRIPT_DIR/boot-ui"
PATCH="$SCRIPT_DIR/bootui-spring-booster.patch"

# Pinned BootUI commit (main as of this benchmark; bootui-parent 1.4.0, Spring Boot 4.1.0).
BOOTUI_REPO="https://github.com/jdubois/boot-ui.git"
BOOTUI_COMMIT="5d52020f63f1460bee45693e517af2be4860e033"

echo "==> 1/4 Installing spring-booster into the local Maven repository (~/.m2)"
# Spotless/Palantir formatting requires a JDK 17 build; skip the check here so the
# benchmark setup works on any JDK 17+ (we only need the installed artifact).
(cd "$REPO_ROOT" && ./mvnw -q -DskipTests -Dspotless.check.skip=true install)

echo "==> 2/4 Cloning BootUI at $BOOTUI_COMMIT"
rm -rf "$BOOTUI_DIR"
git init -q "$BOOTUI_DIR"
git -C "$BOOTUI_DIR" remote add origin "$BOOTUI_REPO"
git -C "$BOOTUI_DIR" fetch -q --depth 1 origin "$BOOTUI_COMMIT"
git -C "$BOOTUI_DIR" checkout -q FETCH_HEAD

echo "==> 3/4 Applying Spring Booster integration patch"
git -C "$BOOTUI_DIR" apply "$PATCH"

echo "==> 4/4 Building the bootui-sample-app jar (tests skipped, sample module only)"
(cd "$BOOTUI_DIR" && ./mvnw -q -pl bootui-sample-app -DskipTests -Dspring-javaformat.skip=true package)

echo
echo "Setup complete. Run the benchmark with:"
echo "    $SCRIPT_DIR/run-benchmark.sh 5"
