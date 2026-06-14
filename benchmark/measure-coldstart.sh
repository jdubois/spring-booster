#!/usr/bin/env bash
#
# measure-coldstart.sh — measure how the proven cold-start levers reduce Spring
# Petclinic's *first-run* startup time.
#
# The companion experiment (measure-in-jvm.sh) proved that Petclinic's cold start
# is dominated by JVM warm-up: bytecode runs interpreted, the JIT has not compiled
# the hot paths, and thousands of classes are loaded/verified for the first time.
# Parallel bean instantiation cannot move that number. The levers that *can* are
# the ones that attack class loading, linking and reflection directly:
#
#   * Plain                       — no optimization (baseline).
#   * Spring AOT                  — build-time bean-definition code generation
#                                   (-Dspring.aot.enabled=true), less runtime
#                                   reflection.
#   * CDS                         — Class Data Sharing: a memory-mapped archive of
#                                   parsed classes, so first run skips most class
#                                   loading/verification.
#   * JDK AOT cache               — JDK 24/25 Project Leyden cache (JEP 483/514):
#                                   CDS extended with linked classes.
#   * Spring AOT + JDK AOT cache  — the two stacked, the strongest non-native combo.
#
# Each variant is measured with the SAME fresh-JVM-per-run methodology as
# measure.sh (a fresh JVM IS a cold start), preceded by one discarded warm-up.
# The reported time is Spring Boot's own "Started PetClinicApplication in X
# seconds" value. Results are printed as a Markdown table.
#
# Usage: ./measure-coldstart.sh [runs]      # default 5 measured runs per variant
#
# Requires ./setup.sh to have created the Petclinic checkout. This script builds
# its own AOT-enabled jar (the AOT classes are inert unless spring.aot.enabled is
# set, so the very same jar serves as both the plain baseline and the Spring AOT
# variant — an apples-to-apples comparison). Set FORCE_AOT_BUILD=1 to rebuild it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PETCLINIC_DIR="$SCRIPT_DIR/spring-petclinic"
JAR="$PETCLINIC_DIR/target/spring-petclinic-4.0.0-SNAPSHOT.jar"
RUNS="${1:-5}"
MAX_WAIT="${MAX_WAIT:-150}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ ! -d "$PETCLINIC_DIR" ]]; then
    echo "ERROR: Petclinic checkout not found at $PETCLINIC_DIR" >&2
    echo "Run ./setup.sh first." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. Build an AOT-enabled jar (idempotent). The AOT-generated initializer is
#    only activated by -Dspring.aot.enabled=true, so this single jar is both the
#    plain baseline (flag off) and the Spring AOT variant (flag on).
# ---------------------------------------------------------------------------
AOT_MARKER="PetClinicApplication__ApplicationContextInitializer.class"
if [[ "${FORCE_AOT_BUILD:-0}" == "1" ]] \
    || [[ ! -f "$JAR" ]] \
    || ! unzip -l "$JAR" 2>/dev/null | grep -q "$AOT_MARKER"; then
    echo "==> Building AOT-enabled Petclinic jar (spring-boot:process-aot package)" >&2
    (cd "$PETCLINIC_DIR" && ./mvnw -q -DskipTests \
        -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Dnohttp.checkstyle.skip=true \
        spring-boot:process-aot package) >&2
else
    echo "==> Reusing existing AOT-enabled jar ($JAR)" >&2
fi

# ---------------------------------------------------------------------------
# 2. Extract the jar so the JVM sees individual class files (CDS/AOT caches work
#    best against an exploded layout) and prepare the shared archives.
# ---------------------------------------------------------------------------
echo "==> Extracting the application jar (jarmode=tools)" >&2
java -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" >&2
APPJAR="$WORK/app/$(basename "$JAR")"

# A training run drives the app to context refresh and exits, recording the
# archive/cache. --server.port=0 avoids port clashes.
echo "==> Training CDS archive" >&2
java -XX:ArchiveClassesAtExit="$WORK/app.jsa" -Dspring.context.exit=onRefresh \
    -jar "$APPJAR" --server.port=0 >"$WORK/train-cds.log" 2>&1 || true

echo "==> Training JDK AOT cache" >&2
java -XX:AOTCacheOutput="$WORK/app.aot" -Dspring.context.exit=onRefresh \
    -jar "$APPJAR" --server.port=0 >"$WORK/train-aot.log" 2>&1 || true

echo "==> Training JDK AOT cache with Spring AOT enabled" >&2
java -XX:AOTCacheOutput="$WORK/app-aoton.aot" -Dspring.aot.enabled=true \
    -Dspring.context.exit=onRefresh \
    -jar "$APPJAR" --server.port=0 >"$WORK/train-aot-springaot.log" 2>&1 || true

# ---------------------------------------------------------------------------
# 3. Measurement helpers.
# ---------------------------------------------------------------------------

# measure_one <jvm-and-app-args...> -> prints the reported startup seconds, or
# nothing on failure. Starts a fresh JVM (a cold start), waits for the "Started
# PetClinicApplication in X seconds" line, then stops the app.
measure_one() {
    local log
    log="$(mktemp)"
    java "$@" --server.port=0 >"$log" 2>&1 &
    local pid=$!
    local waited=0
    local started=""
    while ((waited < MAX_WAIT * 5)); do
        local line
        line="$(grep -E 'Started PetClinicApplication in' "$log" | head -1 || true)"
        if [[ -n "$line" ]]; then
            started="$line"
            break
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.2
        ((waited++))
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    fi
    if [[ -n "$started" ]]; then
        sed -E 's/.*Started PetClinicApplication in ([0-9.]+) seconds.*/\1/' <<<"$started"
    fi
    rm -f "$log"
}

# measure_variant <label> <jvm-and-app-args...> -> prints one pipe-delimited
# record "label|min|median|mean|comma-separated-times" (no percentage; the
# baseline-relative % is computed later, once the plain median is known). One
# discarded warm-up, then RUNS measured runs.
measure_variant() {
    local label="$1"
    shift
    echo ">>> $label — 1 warm-up + $RUNS measured runs" >&2
    measure_one "$@" >/dev/null
    local times=()
    local r secs
    for ((r = 1; r <= RUNS; r++)); do
        secs="$(measure_one "$@")"
        if [[ -z "$secs" ]]; then
            echo "  run $r: FAILED" >&2
            continue
        fi
        echo "  run $r: ${secs}s" >&2
        times+=("$secs")
    done
    if ((${#times[@]} == 0)); then
        echo "$label|||| _failed_"
        return
    fi
    local stat
    stat="$(printf '%s\n' "${times[@]}" | sort -n | awk '
        { v[NR] = $1; sum += $1 }
        END {
            n = NR
            min = v[1]
            mean = sum / n
            if (n % 2) median = v[(n + 1) / 2]
            else median = (v[n / 2] + v[n / 2 + 1]) / 2
            printf "%.3f|%.3f|%.3f", min, median, mean
        }')"
    local list
    list="$(printf '%s, ' "${times[@]}" | sed 's/, $//')"
    echo "$label|$stat|$list"
}

# row <baseline-median> <record> -> prints a Markdown table row, adding the
# "vs plain" column computed from the baseline median.
row() {
    local base="$1" rec="$2"
    local label min med mean list
    IFS='|' read -r label min med mean list <<<"$rec"
    local pct
    if [[ -z "$med" ]]; then
        pct="n/a"
    elif [[ "$med" == "$base" ]]; then
        pct="(baseline)"
    else
        pct="$(awk -v base="$base" -v m="$med" 'BEGIN {
            if (base > 0) printf "%+.0f%%", (base - m) / base * 100; else printf "—" }')"
    fi
    echo "| $label | $list | $min | $med | $mean | $pct |"
}

# ---------------------------------------------------------------------------
# 4. Run the variants. The plain baseline runs first to anchor the % column.
# ---------------------------------------------------------------------------
echo "Petclinic cold-start benchmark — $RUNS runs per variant (fresh JVM each)"
echo "JAR: $JAR"
echo "JDK: $(java -version 2>&1 | head -1)"
echo

rec_plain="$(measure_variant 'Plain (no optimization)' -jar "$APPJAR")"
rec_springaot="$(measure_variant 'Spring AOT' -Dspring.aot.enabled=true -jar "$APPJAR")"
rec_cds="$(measure_variant 'CDS (class data sharing)' -XX:SharedArchiveFile="$WORK/app.jsa" -jar "$APPJAR")"
rec_aot="$(measure_variant 'JDK AOT cache' -XX:AOTCache="$WORK/app.aot" -jar "$APPJAR")"
rec_stack="$(measure_variant 'Spring AOT + JDK AOT cache' \
    -XX:AOTCache="$WORK/app-aoton.aot" -Dspring.aot.enabled=true -jar "$APPJAR")"

# Plain median = field 3 of its record (label|min|median|mean|list).
BASELINE_MED="$(cut -d'|' -f3 <<<"$rec_plain")"

echo
echo "## Cold-start time: cumulative effect of the first-run levers"
echo
echo "All values in seconds (lower is better). The \"vs plain\" column is the median"
echo "improvement over the plain baseline."
echo
echo "| Variant | Runs (s) | Min | Median | Mean | vs plain |"
echo "|---|---|---:|---:|---:|---:|"
row "$BASELINE_MED" "$rec_plain"
row "$BASELINE_MED" "$rec_springaot"
row "$BASELINE_MED" "$rec_cds"
row "$BASELINE_MED" "$rec_aot"
row "$BASELINE_MED" "$rec_stack"
echo
echo "Each lever removes a slice of the cold-JVM tax (reflection, class loading,"
echo "class linking). They stack: Spring AOT + the JDK AOT cache is the strongest"
echo "non-native combination and the recommended default for fast first-run startup."
