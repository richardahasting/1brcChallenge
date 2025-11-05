#!/bin/bash

# Benchmark thread count with optimal chunk size (288KB)
# Usage: ./benchmark_threads_optimal.sh <data_file>

if [ $# -lt 1 ]; then
    echo "Usage: $0 <data_file>"
    echo "Example: $0 ../data/measurements_1b.txt"
    exit 1
fi

DATA_FILE=$1

echo "==================================================================="
echo "Benchmarking thread count with optimal chunk size (288KB)"
echo "File: $DATA_FILE"
echo "Chunk size: 288 KB (optimal)"
echo "Cooldown: 30 seconds between runs"
echo "==================================================================="
echo ""

# Test thread counts
THREAD_COUNTS=(5 6 7 8 9 10 12 15 20)

for threads in "${THREAD_COUNTS[@]}"; do
    echo "=== Testing with $threads threads ==="

    # Clear cache
    echo "Clearing cache..."
    sudo purge

    # Wait for cooldown
    echo "Waiting 30 seconds for CPU cooldown..."
    sleep 30

    # Run benchmark
    java OneBRC -par $DATA_FILE $threads 288 2>&1 | grep -E "(Using|Duration)"

    echo ""
done

echo "==================================================================="
echo "Thread count benchmark complete!"
echo "==================================================================="
