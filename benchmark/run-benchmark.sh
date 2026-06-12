#!/usr/bin/env bash
#
# run-benchmark.sh — compare Spring Petclinic startup time WITHOUT and WITH
# Spring Booster's parallel bean instantiation.
#
# It runs the very same application jar twice:
#   * baseline — sequential bootstrap (Spring Booster inactive)
#   * boosted  — parallel bootstrap (--spring.profiles.active=boost), which
#                activates io.github.jdubois:spring-booster restricted to
#                Petclinic's own dependency-free beans.
#
# Each variant is measured RUNS times (default 5), preceded by one discarded
# warm-up run per variant to stabilise the OS file cache. The reported startup
# time is the value Spring Boot logs as "Started PetClinicApplication in X
# seconds". Results are printed as a Markdown table.
#
# Usage: ./run-benchmark.sh [runs]

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar"
MEASURE="$SCRIPT_DIR/measure.sh"
RUNS="${1:-5}"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: application jar not found at $JAR" >&2
    echo "Build it first:  (cd $SCRIPT_DIR/spring-petclinic && ./mvnw -DskipTests package)" >&2
    exit 1
fi

# stats <space-separated numbers> -> prints "min median mean max" (3 decimals).
stats() {
    printf '%s\n' "$@" | sort -n | awk '
        { v[NR] = $1; sum += $1 }
        END {
            n = NR
            min = v[1]; max = v[n]
            mean = sum / n
            if (n % 2) median = v[(n + 1) / 2]
            else median = (v[n / 2] + v[n / 2 + 1]) / 2
            printf "%.3f %.3f %.3f %.3f", min, median, mean, max
        }'
}

run_variant() {
    # $1 = human label, rest = extra args to the app
    local label="$1"
    shift
    echo ">>> $label — 1 warm-up run (discarded)" >&2
    "$MEASURE" "$JAR" 1 "$@" >/dev/null
    echo ">>> $label — $RUNS measured runs" >&2
    "$MEASURE" "$JAR" "$RUNS" "$@"
}

echo "Spring Petclinic startup benchmark — $RUNS runs per variant"
echo "JAR: $JAR"
echo

baseline_times="$(run_variant 'BASELINE (sequential)')"
boosted_times="$(run_variant 'BOOSTED (parallel bootstrap)' --spring.profiles.active=boost)"

# shellcheck disable=SC2086
read -r b_min b_med b_mean b_max <<<"$(stats $baseline_times)"
# shellcheck disable=SC2086
read -r x_min x_med x_mean x_max <<<"$(stats $boosted_times)"

# Improvement on the median (positive = boosted is faster).
delta="$(awk -v a="$b_med" -v b="$x_med" 'BEGIN { printf "%+.3f", a - b }')"
pct="$(awk -v a="$b_med" -v b="$x_med" 'BEGIN { if (a > 0) printf "%+.1f", (a - b) / a * 100; else printf "n/a" }')"

# shellcheck disable=SC2086
baseline_list="$(printf '%s ' $baseline_times | sed 's/ $//; s/ /, /g')"
# shellcheck disable=SC2086
boosted_list="$(printf '%s ' $boosted_times | sed 's/ $//; s/ /, /g')"

echo
echo "## Startup time: without vs with Spring Booster"
echo
echo "All values in seconds (lower is better)."
echo
echo "| Variant | Runs (s) | Min | Median | Mean | Max |"
echo "|---|---|---:|---:|---:|---:|"
echo "| Baseline (sequential) | $baseline_list | $b_min | $b_med | $b_mean | $b_max |"
echo "| Boosted (parallel bootstrap) | $boosted_list | $x_min | $x_med | $x_mean | $x_max |"
echo
echo "**Median difference (baseline − boosted): ${delta}s (${pct}%).**"
