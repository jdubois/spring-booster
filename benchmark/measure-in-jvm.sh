#!/usr/bin/env bash
#
# measure-in-jvm.sh — start the Petclinic Spring context N times inside ONE JVM
# and print each iteration's wall-clock startup time.
#
# Why: the normal benchmark (run-benchmark.sh) launches a *fresh* JVM per run, so
# every measurement pays the full cold-start tax — bytecode is interpreted, the
# JIT has not compiled the hot paths, and classes are loaded for the first time.
# This harness instead keeps the JVM alive and rebuilds the context repeatedly.
# The per-iteration *work is identical* (same beans, same wiring every time), so
# any drop in time across iterations is pure JVM warm-up (JIT + class loading +
# caches). If the first iteration is dramatically slower than the later ones,
# startup is dominated by cold-JVM effects — not by how beans are instantiated —
# which is exactly why sequential vs. parallel bootstrap is indistinguishable on
# this app.
#
# Usage: ./measure-in-jvm.sh [iterations]            # default 10
#
# It measures two variants, each in its own JVM:
#   * baseline — sequential bootstrap (Spring Booster inactive)
#   * boosted  — parallel bootstrap (--spring.profiles.active=boost)
#
# Requires the jar built by ./setup.sh first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar"
ITERS="${1:-10}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: application jar not found at $JAR" >&2
    echo "Build it first:  ./setup.sh" >&2
    exit 1
fi

echo "==> Extracting runtime classpath from the Petclinic jar" >&2
unzip -q "$JAR" -d "$WORK/extract"
CP="$WORK/extract/BOOT-INF/classes:$(ls "$WORK"/extract/BOOT-INF/lib/*.jar | paste -sd:)"

echo "==> Compiling the in-JVM repeated-startup harness" >&2
cat >"$WORK/RepeatInJvm.java" <<'JAVA'
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.samples.petclinic.PetClinicApplication;
import org.springframework.samples.petclinic.PetClinicParallelBootstrapInitializer;

/**
 * Starts the Petclinic Spring context N times inside ONE JVM, printing the
 * wall-clock time of each iteration (one number per line, to stdout).
 * Iteration 1 is the cold/interpreted run; later iterations reuse the
 * warmed-up, JIT-compiled code. The bean-creation work is identical every
 * iteration, so any speed-up is pure JVM warm-up.
 */
public class RepeatInJvm {
    public static void main(String[] args) {
        int runs = Integer.parseInt(args[0]);
        String[] appArgs = new String[args.length - 1];
        System.arraycopy(args, 1, appArgs, 0, appArgs.length);

        for (int i = 1; i <= runs; i++) {
            SpringApplication application = new SpringApplication(PetClinicApplication.class);
            application.addInitializers(new PetClinicParallelBootstrapInitializer());
            long start = System.nanoTime();
            ConfigurableApplicationContext ctx = application.run(appArgs);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            ctx.close();
            // Spring logs to stdout, so emit the machine-readable timing with a
            // sentinel prefix the caller can grep for unambiguously.
            System.err.printf("  iter %d: %d ms%n", i, elapsedMs);
            System.out.printf("RESULT_MS %d%n", elapsedMs);
        }
    }
}
JAVA
javac -cp "$CP" -d "$WORK/out" "$WORK/RepeatInJvm.java"
RUNCP="$WORK/out:$CP"

# Print a Markdown table row per variant: per-iteration times + cold/warm ratio.
run_variant() {
    local label="$1"
    shift
    echo ">>> $label — $ITERS starts in ONE JVM" >&2
    # Timings arrive on stdout via the RESULT_MS sentinel; let the harness's own
    # stderr (per-iteration progress and any real errors) pass through so a failed
    # run is debuggable.
    local times
    times="$(java -cp "$RUNCP" RepeatInJvm "$ITERS" \
        --server.port=0 --spring.main.banner-mode=off "$@" \
        | sed -n 's/^RESULT_MS //p')"
    # shellcheck disable=SC2086
    read -r first last <<<"$(printf '%s\n' $times | awk 'NR==1{f=$1} {l=$1} END{print f, l}')"
    local ratio
    ratio="$(awk -v a="$first" -v b="$last" 'BEGIN { if (b > 0) printf "%.1f", a / b; else printf "n/a" }')"
    # shellcheck disable=SC2086
    local list
    list="$(printf '%s ' $times | sed 's/ $//; s/ /, /g')"
    echo "| $label | $list | ${first} | ${last} | ${ratio}× |"
}

echo "Petclinic in-JVM repeated-startup experiment — $ITERS iterations per variant"
echo "JAR: $JAR"
echo

base_row="$(run_variant 'Baseline (sequential)')"
boost_row="$(run_variant 'Boosted (parallel bootstrap)' --spring.profiles.active=boost)"

echo
echo "## In-JVM repeated startup (proving JIT-dominated cold start)"
echo
echo "All per-iteration values in milliseconds (wall clock around \`application.run()\`)."
echo
echo "| Variant | Iterations (ms) | Iter 1 (cold) | Last (warm) | Cold/warm |"
echo "|---|---|---:|---:|---:|"
echo "$base_row"
echo "$boost_row"
echo
echo "Identical work every iteration, yet the warm iterations are several times"
echo "faster than the cold first one: startup is dominated by JVM warm-up (JIT +"
echo "class loading), not by how the beans are instantiated."
