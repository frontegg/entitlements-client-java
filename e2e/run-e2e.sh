#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cleanup() {
    echo "Stopping SpiceDB infrastructure..."
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" down -v 2>/dev/null || true
}

trap cleanup EXIT

echo "Starting SpiceDB infrastructure..."
docker compose -f "$SCRIPT_DIR/docker-compose.yml" up -d --wait

echo "Waiting for SpiceDB to be ready..."
for i in $(seq 1 30); do
    if docker compose -f "$SCRIPT_DIR/docker-compose.yml" exec -T spicedb pgrep -f spicedb > /dev/null 2>&1; then
        echo "SpiceDB is ready."
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: SpiceDB did not become ready in time."
        exit 1
    fi
    sleep 2
done

# Seed schema and relationships if zed CLI is available
if command -v zed &> /dev/null; then
    echo "Seeding schema and relationships via zed..."
    zed import "$SCRIPT_DIR/schema-relationships.yaml"
else
    echo "zed CLI not found — schema will be seeded by the Java test @BeforeAll setup."
    echo "Install zed: https://authzed.com/docs/spicedb/getting-started/installing-zed"
fi

echo "Running E2E tests..."
cd "$PROJECT_DIR"
mvn verify -P e2e --batch-mode \
    -Dspicedb.endpoint=localhost:50051 \
    -Dspicedb.token=spicedb

echo "E2E tests completed successfully."
