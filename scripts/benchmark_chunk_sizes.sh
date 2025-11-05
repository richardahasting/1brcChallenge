#!/bin/bash

# Benchmark different chunk sizes
# Usage: ./benchmark_chunk_sizes.sh <data_file>

if [ $# -lt 1 ]; then
    echo "Usage: $0 <data_file>"
    echo "Example: $0 ../data/measurements_1b.txt"
    exit 1
fi

DATA_FILE=$1

echo "==================================================================="
echo "Benchmarking different chunk sizes"
echo "File: $DATA_FILE"
echo "Threads: 9 (optimal)"
echo "==================================================================="
echo ""

# Test chunk sizes: 16KB to 64MB
CHUNK_SIZES=(16 32 64 128 256 512 1024 2048 4096 8192 16384 32768 65536)

for chunk_kb in "${CHUNK_SIZES[@]}"; do
    echo "=== Testing chunk size: ${chunk_kb} KB ==="

    # Clear cache
    sudo purge
    sleep 2

    # Run benchmark
    java OneBRC -par $DATA_FILE 9 $chunk_kb 2>&1 | grep -E "(Chunk size|Duration)"

    echo ""
done

echo "==================================================================="
echo "Benchmark complete!"
echo "==================================================================="
