#!/bin/bash

# Benchmark script that clears cache between runs
# Usage: ./benchmark_io.sh <data_file> <num_runs>

if [ $# -lt 2 ]; then
    echo "Usage: $0 <data_file> <num_runs>"
    echo "Example: $0 ../data/measurements_10m.txt 3"
    exit 1
fi

DATA_FILE=$1
NUM_RUNS=$2

echo "==================================================================="
echo "Benchmarking with cache clearing between runs"
echo "File: $DATA_FILE"
echo "Runs: $NUM_RUNS"
echo "==================================================================="
echo ""

# Test with 9 threads (found to be optimal)
echo "### Testing parallel (9 threads) - COLD CACHE ###"
echo ""

for i in $(seq 1 $NUM_RUNS); do
    echo "--- Run $i ---"

    # Clear filesystem cache
    echo "Clearing cache..."
    sudo purge

    # Wait a moment for purge to complete
    sleep 2

    # Run benchmark
    java OneBRC -par $DATA_FILE 9 2>&1 | grep -E "(Duration|Throughput)"

    echo ""
done

echo ""
echo "==================================================================="
echo "### Testing with WARM CACHE (3 consecutive runs) ###"
echo "==================================================================="
echo ""

for i in $(seq 1 3); do
    echo "--- Warm run $i ---"
    java OneBRC -par $DATA_FILE 9 2>&1 | grep -E "(Duration|Throughput)"
    echo ""
done
