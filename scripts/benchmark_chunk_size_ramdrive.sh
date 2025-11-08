#!/bin/bash

# Benchmark script for OneBRCOptimized chunk size tuning on RAM drive
# Tests different chunk sizes with optimal thread count (11) and 30s cooldown

# Change to java directory
cd "$(dirname "$0")/../java" || exit 1

DATA_FILE="../data/measurements_1b.txt"
THREADS=11  # Optimal thread count from previous test
COOLDOWN=30

echo "=== OneBRCOptimized Chunk Size Tuning on RAM Drive ==="
echo "Data file: $DATA_FILE"
echo "Worker threads: $THREADS (fixed, optimal)"
echo "Cooldown between runs: ${COOLDOWN}s"
echo ""

# Test chunk sizes from 64KB to 8MB
for chunk_kb in 64 128 256 288 512 1024 2048 4096 8192; do
    echo "=========================================="
    echo "Testing with ${chunk_kb}KB chunks"
    echo "=========================================="
    /usr/bin/time -p java OneBRCOptimized $DATA_FILE $THREADS $chunk_kb 2>&1

    # Calculate number of chunks for this size
    file_size_kb=$((13600 * 1024))  # 13.6 GB in KB
    num_chunks=$((file_size_kb / chunk_kb))
    echo "  → Approximately $num_chunks chunks"

    if [ $chunk_kb -lt 8192 ]; then
        echo ""
        echo "Cooling down for ${COOLDOWN}s..."
        sleep $COOLDOWN
        echo ""
    fi
done

echo ""
echo "=== Benchmark Complete ==="
