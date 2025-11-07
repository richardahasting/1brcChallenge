#!/bin/bash

# Benchmark script for OneBRCParallel with thermal management
# Tests different thread counts and chunk sizes with 30s cooldown between runs

DATA_FILE="../data/measurements_1b.txt"
COOLDOWN=30

echo "=== Java Parallel Architecture Benchmark ==="
echo "Data file: $DATA_FILE"
echo "Cooldown between runs: ${COOLDOWN}s"
echo ""

# Test optimal configuration (20 threads, 64MB)
echo "=========================================="
echo "Test 1: 20 threads, 64MB chunks (optimal)"
echo "=========================================="
/usr/bin/time -p java OneBRCParallel $DATA_FILE 20 64 2>&1
echo ""
echo "Cooling down for ${COOLDOWN}s..."
sleep $COOLDOWN

# Test thread scaling with 64MB chunks
echo "=========================================="
echo "Test 2: 7 threads, 64MB chunks"
echo "=========================================="
/usr/bin/time -p java OneBRCParallel $DATA_FILE 7 64 2>&1
echo ""
echo "Cooling down for ${COOLDOWN}s..."
sleep $COOLDOWN

echo "=========================================="
echo "Test 3: 15 threads, 64MB chunks"
echo "=========================================="
/usr/bin/time -p java OneBRCParallel $DATA_FILE 15 64 2>&1
echo ""
echo "Cooling down for ${COOLDOWN}s..."
sleep $COOLDOWN

echo "=========================================="
echo "Test 4: 25 threads, 64MB chunks"
echo "=========================================="
/usr/bin/time -p java OneBRCParallel $DATA_FILE 25 64 2>&1
echo ""
echo "Cooling down for ${COOLDOWN}s..."
sleep $COOLDOWN

echo "=========================================="
echo "Test 5: 30 threads, 64MB chunks"
echo "=========================================="
/usr/bin/time -p java OneBRCParallel $DATA_FILE 30 64 2>&1
echo ""

echo ""
echo "=== Benchmark Complete ==="
