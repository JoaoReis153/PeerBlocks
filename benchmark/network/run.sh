#!/usr/bin/env bash
# Runs every node in its own container, with each seeder's upload limited by tc netem,
# so the network is the bottleneck instead of the machine.
#
# Usage: benchmark/network/run.sh [maxSeeds] [iterations] [fileSize] [rate] [delay] [outputCsv]
# Example: benchmark/network/run.sh 10 3 100MB 100mbit 20ms
set -euo pipefail

MAX_SEEDS=${1:-10}
ITERATIONS=${2:-3}
FILE_SIZE=${3:-100MB}
RATE=${4:-100mbit}
DELAY=${5:-20ms}
CSV=${6:-benchmark/network_results.csv}

IMAGE=peerblocks-node
NETWORK=peerblocks-bench
LABEL=peerblocks-bench

cd "$(dirname "$0")/../.."

cleanup() {
    docker rm -f $(docker ps -aq --filter "label=$LABEL") >/dev/null 2>&1 || true
}
trap 'cleanup; docker network rm $NETWORK >/dev/null 2>&1 || true' EXIT

echo "Building the node image..."
./gradlew -q networkNodeJar
cp benchmark/network/Dockerfile build/network-benchmark/
docker build -q -t $IMAGE build/network-benchmark >/dev/null

cleanup
docker network rm $NETWORK >/dev/null 2>&1 || true
docker network create $NETWORK >/dev/null

echo "seeds,iteration,file_size_bytes,transfer_ms,verify_ms,total_ms,transfer_mb_per_sec,total_mb_per_sec" >"$CSV"
echo "Each seeder: $RATE upload, $DELAY delay. File: $FILE_SIZE."

wait_until_ready() {
    local name=$1
    for _ in $(seq 1 600); do
        if docker logs "$name" 2>&1 | grep -q READY; then return 0; fi
        if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ]; then break; fi
        sleep 0.1
    done
    echo "Seeder $name didn't start:" >&2
    docker logs "$name" >&2
    return 1
}

for seeds in $(seq 1 "$MAX_SEEDS"); do
    for iteration in $(seq 1 "$ITERATIONS"); do
        seeders=()
        for id in $(seq 1 "$seeds"); do
            docker run -d --name "pb-seed-$id" --label $LABEL --network $NETWORK \
                --cap-add NET_ADMIN $IMAGE seed "$id" "$FILE_SIZE" >/dev/null
            docker exec "pb-seed-$id" tc qdisc add dev eth0 root netem rate "$RATE" delay "$DELAY" limit 100000
            seeders+=("pb-seed-$id:$((8080 + id))")
        done
        for id in $(seq 1 "$seeds"); do wait_until_ready "pb-seed-$id"; done

        output=$(docker run --rm --name pb-download --label $LABEL --network $NETWORK \
            $IMAGE download 100 "$FILE_SIZE" "${seeders[@]}" 2>&1 || true)
        result=$(echo "$output" | grep -E '^(RESULT|FAILED)' | tail -1)

        if [[ $result == RESULT* ]]; then
            row=${result#RESULT }
            echo "$seeds,$iteration,$row" >>"$CSV"
            IFS=, read -r _ transfer verify _ speed _ <<<"$row"
            echo "$seeds seed(s) [$iteration/$ITERATIONS]: transfer $transfer ms ($speed MB/s), verify $verify ms"
        else
            echo "$seeds,$iteration,ERROR,ERROR,ERROR,ERROR,ERROR,ERROR" >>"$CSV"
            echo "$seeds seed(s) [$iteration/$ITERATIONS]: ${result:-no result}" >&2
            echo "$output" | tail -20 >&2
        fi
        cleanup
    done
done

echo "Results written to $CSV"
