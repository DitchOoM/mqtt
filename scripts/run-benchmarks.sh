#!/bin/bash
set -e

# End-to-end MQTT benchmarks against a local Mosquitto broker.
# Starts a Docker container, runs tests on JVM/JS/Linux, then cleans up.
#
# Usage:
#   ./scripts/run-benchmarks.sh          # Run all platforms
#   ./scripts/run-benchmarks.sh jvm      # Run JVM only
#   ./scripts/run-benchmarks.sh js       # Run JS (Node) only
#   ./scripts/run-benchmarks.sh linux    # Run Linux native only

CONTAINER_NAME="mqtt-bench"
BROKER_PORT=1883
TEST_PATTERN="*EndToEndBrokerBenchmarkTest*"

cleanup() {
    echo "Stopping Mosquitto container..."
    docker stop "$CONTAINER_NAME" 2>/dev/null && docker rm "$CONTAINER_NAME" 2>/dev/null || true
}

trap cleanup EXIT

# Start Mosquitto broker
echo "Starting Mosquitto broker on port $BROKER_PORT..."
docker run -d --name "$CONTAINER_NAME" -p "$BROKER_PORT:1883" eclipse-mosquitto:2 \
    sh -c 'printf "listener 1883\nallow_anonymous true\n" > /mosquitto/config/mosquitto.conf && \
    exec mosquitto -c /mosquitto/config/mosquitto.conf'
sleep 2

# Verify broker is up
if ! docker ps --filter "name=$CONTAINER_NAME" --format '{{.Names}}' | grep -q "$CONTAINER_NAME"; then
    echo "ERROR: Mosquitto container failed to start"
    exit 1
fi
echo "Mosquitto broker is running."

PLATFORM="${1:-all}"

run_jvm() {
    echo ""
    echo "=== JVM Benchmarks ==="
    ./gradlew :mqtt-client:jvmTest --tests "$TEST_PATTERN" --rerun 2>&1 | tail -20
}

run_js() {
    echo ""
    echo "=== JS (Node) Benchmarks ==="
    ./gradlew :mqtt-client:jsNodeTest --tests "$TEST_PATTERN" --rerun 2>&1 | tail -20
}

run_linux() {
    echo ""
    echo "=== Linux Native Benchmarks ==="
    ./gradlew :mqtt-client:linuxX64Test --tests "$TEST_PATTERN" --rerun 2>&1 | tail -20
}

case "$PLATFORM" in
    jvm)    run_jvm ;;
    js)     run_js ;;
    linux)  run_linux ;;
    all)
        run_jvm
        run_js
        run_linux
        ;;
    *)
        echo "Unknown platform: $PLATFORM"
        echo "Usage: $0 [jvm|js|linux|all]"
        exit 1
        ;;
esac

echo ""
echo "=== Benchmarks complete ==="
