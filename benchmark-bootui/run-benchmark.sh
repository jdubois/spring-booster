#!/usr/bin/env bash
#
# run-benchmark.sh — compare BootUI sample-app startup time WITHOUT and WITH
# Spring Booster's parallel bean instantiation.
#
# It runs the very same application jar three times:
#   * baseline — sequential bootstrap (Spring Booster inactive)
#   * boosted  — parallel bootstrap (--sample.parallel-bootstrap=true), which
#                activates io.github.jdubois:spring-booster with the accept-all
#                default (factory-method @Bean beans are kept on the main thread,
#                so the analysis backgrounds the sample app's component beans).
#   * boosted+shared-infra — parallel bootstrap plus the completed-leaf barrier
#                relaxation (--sample.shared-infra-consumers=true), which lets two
#                independent migration engines (Flyway + Liquibase) over the shared
#                DataSource run concurrently on background threads when the shared leaf
#                qualifies as a forced-mainline barrier.
#   * boosted+allowlist — parallel bootstrap plus an explicit per-bean allowlist
#                (--sample.background-bean-names=...), which names specific heavyweight
#                @Bean beans (Spring Security's filter chain and the migrators) so they
#                may background even though dynamic auto-configurations keep every other
#                @Bean on the main thread. Forced-mainline names safely stay sequential.
#
# The toggle is a PROPERTY, not a Spring profile, on purpose: the sample app's
# default profile is "dev" (spring.profiles.default=dev → in-memory H2 + simple
# cache). Activating a "boost" profile would *replace* "dev" and break the
# Docker-free setup, so the boosted variant keeps "dev" active and flips
# sample.parallel-bootstrap instead. Same jar, same classpath, same profile —
# only the parallel-bootstrap flag differs.
#
# Each variant is measured RUNS times (default 5), preceded by one discarded
# warm-up run per variant to stabilise the OS file cache. The reported startup
# time is the value Spring Boot logs as "Started BootUiSampleApplication in X
# seconds". Results are printed as a Markdown table.
#
# Usage: ./run-benchmark.sh [runs]

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/boot-ui/bootui-sample-app/target/bootui-sample-app-1.4.0.jar"
MEASURE="$SCRIPT_DIR/measure.sh"
RUNS="${1:-5}"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: application jar not found at $JAR" >&2
    echo "Build it first by running:  $SCRIPT_DIR/setup.sh" >&2
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

echo "BootUI sample-app startup benchmark — $RUNS runs per variant"
echo "JAR: $JAR"
echo

baseline_times="$(run_variant 'BASELINE (sequential)')"
boosted_times="$(run_variant 'BOOSTED (parallel bootstrap)' --sample.parallel-bootstrap=true)"
# Third variant: parallel bootstrap PLUS the shared-infrastructure-leaf relaxation
# (backgroundFactoryMethodBeans + backgroundSharedInfraConsumers), which lets the two
# independent migration engines (Flyway + Liquibase) over the shared DataSource run
# concurrently on background threads.
sharedinfra_times="$(run_variant 'BOOSTED+SHARED-INFRA (parallel bootstrap + shared-infra leaf)' \
    --sample.parallel-bootstrap=true --sample.shared-infra-consumers=true)"
# Fourth variant: parallel bootstrap PLUS an explicit per-bean allowlist. It names
# specific heavyweight @Bean beans (here Spring Security's filter chain and the two
# migration engines) so they may background even though dynamic auto-configurations keep
# every other @Bean on the main thread. Names that are forced-mainline (e.g. the
# Flyway/Liquibase depends-on targets that JPA pins ahead of itself) safely stay on the
# main thread, so this is a targeted, safe alternative to backgroundFactoryMethodBeans.
allowlist_times="$(run_variant 'BOOSTED+ALLOWLIST (parallel bootstrap + per-bean allowlist)' \
    --sample.parallel-bootstrap=true \
    --sample.background-bean-names=springSecurityFilterChain,flyway,liquibase)"

# shellcheck disable=SC2086
read -r b_min b_med b_mean b_max <<<"$(stats $baseline_times)"
# shellcheck disable=SC2086
read -r x_min x_med x_mean x_max <<<"$(stats $boosted_times)"
# shellcheck disable=SC2086
read -r s_min s_med s_mean s_max <<<"$(stats $sharedinfra_times)"
# shellcheck disable=SC2086
read -r a_min a_med a_mean a_max <<<"$(stats $allowlist_times)"

# Improvement on the median (positive = boosted is faster).
delta="$(awk -v a="$b_med" -v b="$x_med" 'BEGIN { printf "%+.3f", a - b }')"
pct="$(awk -v a="$b_med" -v b="$x_med" 'BEGIN { if (a > 0) printf "%+.1f", (a - b) / a * 100; else printf "n/a" }')"
# Improvement of the shared-infra variant on the median (positive = faster than baseline).
sdelta="$(awk -v a="$b_med" -v b="$s_med" 'BEGIN { printf "%+.3f", a - b }')"
spct="$(awk -v a="$b_med" -v b="$s_med" 'BEGIN { if (a > 0) printf "%+.1f", (a - b) / a * 100; else printf "n/a" }')"
# Improvement of the allowlist variant on the median (positive = faster than baseline).
adelta="$(awk -v a="$b_med" -v b="$a_med" 'BEGIN { printf "%+.3f", a - b }')"
apct="$(awk -v a="$b_med" -v b="$a_med" 'BEGIN { if (a > 0) printf "%+.1f", (a - b) / a * 100; else printf "n/a" }')"

# shellcheck disable=SC2086
baseline_list="$(printf '%s ' $baseline_times | sed 's/ $//; s/ /, /g')"
# shellcheck disable=SC2086
boosted_list="$(printf '%s ' $boosted_times | sed 's/ $//; s/ /, /g')"
# shellcheck disable=SC2086
sharedinfra_list="$(printf '%s ' $sharedinfra_times | sed 's/ $//; s/ /, /g')"
# shellcheck disable=SC2086
allowlist_list="$(printf '%s ' $allowlist_times | sed 's/ $//; s/ /, /g')"

echo
echo "## Startup time: without vs with Spring Booster"
echo
echo "All values in seconds (lower is better)."
echo
echo "| Variant | Runs (s) | Min | Median | Mean | Max |"
echo "|---|---|---:|---:|---:|---:|"
echo "| Baseline (sequential) | $baseline_list | $b_min | $b_med | $b_mean | $b_max |"
echo "| Boosted (parallel bootstrap) | $boosted_list | $x_min | $x_med | $x_mean | $x_max |"
echo "| Boosted + shared-infra leaf | $sharedinfra_list | $s_min | $s_med | $s_mean | $s_max |"
echo "| Boosted + per-bean allowlist | $allowlist_list | $a_min | $a_med | $a_mean | $a_max |"
echo
echo "**Median difference (baseline − boosted): ${delta}s (${pct}%).**"
echo "**Median difference (baseline − boosted+shared-infra): ${sdelta}s (${spct}%).**"
echo "**Median difference (baseline − boosted+allowlist): ${adelta}s (${apct}%).**"
