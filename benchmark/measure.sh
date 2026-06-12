#!/usr/bin/env bash
#
# measure.sh — run a Spring Petclinic jar N times and print the reported
# startup time (the "Started PetClinicApplication in X seconds" value) of each
# successful run, one per line, to stdout. Diagnostics go to stderr.
#
# Usage: measure.sh <jar> <runs> [extra spring/jvm args...]
#
# Each run uses a random HTTP port (--server.port=0) so consecutive runs never
# collide, and the application is stopped as soon as it reports a successful
# start so the next run begins from a clean slate.

set -u

JAR="${1:?usage: measure.sh <jar> <runs> [extra args...]}"
RUNS="${2:?usage: measure.sh <jar> <runs> [extra args...]}"
shift 2
EXTRA=("$@")

# Maximum seconds to wait for a single run to report startup before giving up.
MAX_WAIT="${MAX_WAIT:-120}"

for ((r = 1; r <= RUNS; r++)); do
    log="$(mktemp)"
    java -jar "$JAR" --server.port=0 ${EXTRA[@]+"${EXTRA[@]}"} >"$log" 2>&1 &
    pid=$!

    started=""
    waited=0
    while ((waited < MAX_WAIT * 2)); do
        line="$(grep -E 'Started PetClinicApplication in' "$log" | head -1 || true)"
        if [[ -n "$line" ]]; then
            started="$line"
            break
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.5
        ((waited++))
    done

    # Stop the application now that we have (or failed to get) a startup time.
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        wait "$pid" 2>/dev/null
    fi

    if [[ -z "$started" ]]; then
        echo "  run $r: FAILED to start" >&2
        grep -E 'APPLICATION FAILED TO START|Caused by|Exception|ERROR' "$log" | head -5 >&2
        rm -f "$log"
        continue
    fi

    secs="$(sed -E 's/.*Started PetClinicApplication in ([0-9.]+) seconds.*/\1/' <<<"$started")"
    echo "  run $r: ${secs}s" >&2
    echo "$secs"
    rm -f "$log"
done
