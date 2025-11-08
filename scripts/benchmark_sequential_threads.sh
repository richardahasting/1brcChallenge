#!/bin/bash

# Benchmark script for OneBRCOptimized thread scaling on RAM drive
# Tests thread counts 7-15 with 30s cooldown between runs

# Change to java directory
cd "$(dirname "$0")/../java" || exit 1

DATA_FILE="../data/measurements_1b.txt"
CHUNK_SIZE=288  # KB, already optimal
COOLDOWN=30

echo "=== OneBRCOptimized Thread Scaling on RAM Drive ==="
echo "Data file: $DATA_FILE"
echo "Chunk size: ${CHUNK_SIZE}KB (fixed)"
echo "Cooldown between runs: ${COOLDOWN}s"
echo ""

for threads in 7 8 9 10 11 12 13 14 15; do
    echo "=========================================="
    echo "Testing with $threads threads"
    echo "=========================================="
    /usr/bin/time -p java OneBRCOptimized $DATA_FILE $threads $CHUNK_SIZE 2>&1

    if [ $threads -lt 15 ]; then
        echo ""
        echo "Cooling down for ${COOLDOWN}s..."
        sleep $COOLDOWN
        echo ""
    fi
done

echo ""
echo "=== Benchmark Complete ==="
