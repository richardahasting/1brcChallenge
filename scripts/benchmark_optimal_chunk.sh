#!/bin/bash

# Fine-tune optimal chunk size with thermal consideration
# Usage: ./benchmark_optimal_chunk.sh <data_file>

if [ $# -lt 1 ]; then
    echo "Usage: $0 <data_file>"
    echo "Example: $0 ../data/measurements_1b.txt"
    exit 1
fi

DATA_FILE=$1

echo "==================================================================="
echo "Fine-tuning chunk size around optimal range (192KB - 512KB)"
echo "File: $DATA_FILE"
echo "Threads: 9"
echo "Cooldown: 30 seconds between runs to prevent thermal throttling"
echo "==================================================================="
echo ""

# Test chunk sizes around the sweet spot
CHUNK_SIZES=(192 224 256 288 320 384 448 512)

for chunk_kb in "${CHUNK_SIZES[@]}"; do
    echo "=== Testing chunk size: ${chunk_kb} KB ==="

    # Clear cache
    echo "Clearing cache..."
    sudo purge

    # Wait for cache clear and CPU cooldown
    echo "Waiting 30 seconds for CPU cooldown..."
    sleep 30

    # Run benchmark
    java OneBRC -par $DATA_FILE 9 $chunk_kb 2>&1 | grep -E "(Chunk size|Duration)"

    echo ""
done

echo "==================================================================="
echo "Fine-tuning benchmark complete!"
echo "==================================================================="
