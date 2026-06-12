#!/usr/bin/env bash
#
# setup.sh — prepare the Spring Petclinic startup benchmark.
#
# It (1) installs the spring-booster library into the local Maven repo, (2) clones
# Spring Petclinic at a pinned commit, (3) applies the Spring Booster integration
# patch, and (4) builds the runnable jar. After this you can run ./run-benchmark.sh.
#
# Re-running is safe: the Petclinic checkout is recreated from scratch each time.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PETCLINIC_DIR="$SCRIPT_DIR/spring-petclinic"
PATCH="$SCRIPT_DIR/petclinic-spring-booster.patch"

# Pinned Spring Petclinic commit (main as of this benchmark; Spring Boot 4.0.3).
PETCLINIC_REPO="https://github.com/spring-projects/spring-petclinic.git"
PETCLINIC_COMMIT="a2c2ef994340d3970eb6db51247456a51bb161f8"

echo "==> 1/4 Installing spring-booster into the local Maven repository (~/.m2)"
# Spotless/Palantir formatting requires a JDK 17 build; skip the check here so the
# benchmark setup works on any JDK 17+ (we only need the installed artifact).
(cd "$REPO_ROOT" && ./mvnw -q -DskipTests -Dspotless.check.skip=true install)

echo "==> 2/4 Cloning Spring Petclinic at $PETCLINIC_COMMIT"
rm -rf "$PETCLINIC_DIR"
git init -q "$PETCLINIC_DIR"
git -C "$PETCLINIC_DIR" remote add origin "$PETCLINIC_REPO"
git -C "$PETCLINIC_DIR" fetch -q --depth 1 origin "$PETCLINIC_COMMIT"
git -C "$PETCLINIC_DIR" checkout -q FETCH_HEAD

echo "==> 3/4 Applying Spring Booster integration patch"
git -C "$PETCLINIC_DIR" apply "$PATCH"

echo "==> 4/4 Building the Petclinic jar (tests skipped)"
(cd "$PETCLINIC_DIR" && ./mvnw -q -DskipTests -Dcheckstyle.skip=true -Dspring-javaformat.skip=true package)

echo
echo "Setup complete. Run the benchmark with:"
echo "    $SCRIPT_DIR/run-benchmark.sh 5"
