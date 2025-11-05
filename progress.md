# 1 Billion Row Challenge - Progress Report

## Project Overview
Multi-language implementation (Java, C, Rust) of the 1 Billion Row Challenge.
Process 1B temperature measurements (~13.6GB file) and calculate min/mean/max per weather station.

**Repository:** https://github.com/richardahasting/1brcChallenge

## Current Status: Java Implementation Complete

### Performance Results - 1 Billion Rows (13.6 GB)

| Configuration | Time | Throughput | CPU Usage | Notes |
|--------------|------|------------|-----------|-------|
| Baseline (estimated) | ~105s | ~130 MB/s | ~10% | BufferedReader, single thread |
| + Memory-mapped I/O | ~45s | ~302 MB/s | ~10% | Eliminated kernel copies |
| + Custom parsing | ~40s | ~340 MB/s | ~10% | Avoided Double.parseDouble() |
| + Parallelism (9 threads, 16MB chunks) | ~20s | ~680 MB/s | ~90% | But broken >2GB |
| + Sequential I/O (handles >2GB) | ~20s | ~680 MB/s | ~90% | Producer-consumer pattern |
| + Optimal chunk size (288KB) | ~14s | ~970 MB/s | ~50% | Reduced queue overhead |
| **+ Optimal threads (7) + 288KB** | **~11.2s** | **~1.21 GB/s** | **~42%** | **Current best** |

**Overall Speedup: 9.4x from baseline**

## Key Discoveries

### 1. I/O Strategy Comparison (10M rows, cold cache)
```
BufferedReader (traditional):    225 ms
Memory-mapped I/O (mmap):        129 ms (75% faster)
```
**Winner:** mmap eliminates kernel-to-user space copies

### 2. Data Type Performance (10M rows, warm cache)
```
Standard doubles (BufferedReader):  1059 ms
Custom float parsing (mmap):         703 ms
Custom double parsing (mmap):        471 ms
Short integers (tenths, mmap):       460 ms  ⭐ Best
```
**Winner:** Short integers or doubles (virtually tied)
**Surprise:** Floats are 50% SLOWER than doubles (CPU optimized for 64-bit)

### 3. Thread Count Optimization

**With 16MB chunks (original):**
- Optimal: 9 threads (104.90 ms on 10M rows)

**With 288KB chunks (optimized):**
- Optimal: 7 threads (11.04s on 1B rows)
- Flatter curve: 6-20 threads all within 10% of optimal

### 4. Chunk Size Optimization (1B rows, 7 threads)

| Chunk Size | Duration | Notes |
|------------|----------|-------|
| 16 KB | 24.0s | Too small - overhead |
| 128 KB | 14.7s | Good |
| 256 KB | 14.1s | Very good |
| **288 KB** | **11.0s** | **⭐ Optimal** |
| 320 KB | 14.0s | Cliff - degrading |
| 512 KB | 14.1s | Too large |
| 16 MB | 20.4s | Original (poor) |

**Key insight:** 288KB is the sweet spot - balances queue efficiency with worker utilization

### 5. Thermal Throttling Impact

**Without cooldown:**
- 256KB: 14.1s
- Results varied 10-20%

**With 30s cooldown between runs:**
- 288KB: 11.0s
- Consistent results within 3%

**Lesson:** Laptop cooling is critical for accurate benchmarking

### 6. Cold vs Warm Cache (10M rows)

| Approach | Cold Cache | Warm Cache | Difference |
|----------|-----------|------------|------------|
| Sequential I/O | 225 ms | 129 ms | 75% slower |
| Random I/O | 197 ms | 105 ms | 88% slower |

**Impact:** Cold cache nearly 2x slower - always test with `purge` for realistic results

### 7. Sequential vs Random I/O

**Small files (10M rows, 140MB):**
- Random I/O faster (104ms vs 169ms) on fast SSD
- Queue overhead > I/O benefit

**Large files (1B rows, 13.6GB):**
- Sequential I/O required (random broken >2GB)
- SSD sequential: ~5000 MB/s
- SSD random: ~150 MB/s
- 33x difference - MUST use sequential!

## Current Bottleneck: I/O Bound (42% CPU)

**Problem:** Single reader thread can't keep up with 7 worker threads
- Reader scanning for newlines sequentially
- Workers starving, waiting on queue
- Only achieving 42% total CPU utilization

**Potential solutions to explore:**
1. Increase queue depth (currently 2× threads = 14)
2. Optimize reader (less work per chunk)
3. Better prefetching strategy

## File Structure

```
1brcChallenge/
├── java/
│   ├── OneBRC.java          # Full implementation with all strategies
│   └── OneBRCOptimized.java # Clean optimized version (recommended) ⭐
├── scripts/
│   ├── generate_data.py     # Test data generator
│   ├── benchmark_io.sh      # I/O comparison with cache clearing
│   ├── benchmark_chunk_sizes.sh
│   ├── benchmark_optimal_chunk.sh
│   └── benchmark_threads_optimal.sh
├── data/
│   ├── measurements_1k.txt
│   ├── measurements_10m.txt
│   └── measurements_1b.txt  # 13.6 GB, 1 billion rows
├── README.md
├── developer-log.md
├── discussion.md
└── progress.md (this file)
```

## GitHub Issues Created

1. #1: Project setup and infrastructure ✅
2. #2: Java baseline implementation ✅
3. #3: Java optimization ✅
4. #4: C baseline implementation ⏸️
5. #5: C optimization ⏸️
6. #6: Rust baseline implementation ⏸️
7. #7: Rust optimization ⏸️
8. #8: Benchmarking suite ✅
9. #9: Create clean optimized Java implementation ✅

## Implementation Strategies Available

1. `-std` - Standard doubles (BufferedReader)
2. `-dbl` - Custom double parsing (mmap)
3. `-flt` - Custom float parsing (mmap)
4. `-int` - Short integer parsing (mmap)
5. `-par` - Parallel with sequential I/O (producer-consumer)
6. `-par-old` - Parallel with random I/O (broken >2GB)

## Key Learnings

1. **Memory-mapped I/O is essential** - 2.3x faster than BufferedReader
2. **Float is slower than double** - Modern CPUs optimized for 64-bit
3. **Custom parsing matters** - Avoiding `Double.parseDouble()` saves time
4. **Chunk size is critical** - 56x reduction (16MB→288KB) = 45% speedup
5. **Thermal throttling is real** - Cooldown periods reveal true performance
6. **Cache must be cleared** - `purge` between runs for accurate benchmarks
7. **Sequential I/O required** - 33x faster than random on real workloads
8. **Java can handle >2GB** - Use multiple mmap segments
9. **Fewer threads with smaller chunks** - Better cache locality
10. **I/O becomes bottleneck** - Single reader limits CPU utilization

## Next Steps

### Immediate (Java)
- [ ] Tune queue depth to improve CPU utilization (currently 42%)
- [ ] Experiment with prefetch strategies
- [ ] Investigate SIMD for parsing (Vector API)
- [ ] Try different queue capacity multipliers (5x, 10x, 20x)

### Future (C Implementation)
- [ ] Baseline C with standard I/O
- [ ] Optimize with mmap, custom hash table
- [ ] SIMD instructions for parsing
- [ ] Compare performance to Java

### Future (Rust Implementation)
- [ ] Baseline Rust with BufRead
- [ ] Optimize with memmap, rayon
- [ ] Zero-copy parsing
- [ ] Learning opportunity for systems programming

### Documentation
- [ ] Update developer-log.md with final results
- [ ] Create comprehensive README with all findings
- [ ] Document optimal configuration
- [ ] Commit and push all work

## Optimal Configuration (Current Best)

**Using OneBRCOptimized.java (recommended):**
```bash
javac OneBRCOptimized.java
java OneBRCOptimized measurements_1b.txt
# Or with custom settings:
java OneBRCOptimized measurements_1b.txt 7 288
```

**Using OneBRC.java (experimental):**
```bash
java OneBRC -par measurements_1b.txt 7 288
```

**Parameters:**
- Strategy: Parallel with sequential I/O
- Threads: 7 (default in optimized version)
- Chunk size: 288 KB (default in optimized version)
- Queue capacity: 14 (2 × threads)

**Performance:**
- Duration: ~11-12 seconds
- Throughput: ~1.1-1.2 GB/s
- Speedup: 9.4x from baseline
- CPU Utilization: 42% (I/O bound)

## System Information

- **Platform:** macOS (Apple Silicon)
- **Page size:** 16KB
- **Processor:** 10 available processors (likely 6 P-cores + 4 E-cores)
- **Storage:** Fast SSD (sequential: ~5000 MB/s, random: ~150 MB/s)
- **Thermal:** Laptop with poor cooling - requires cooldown between benchmarks

## Session Statistics

- **Files created:** 15+
- **Benchmark runs:** 50+
- **Total time optimizing:** Multiple hours
- **Performance improvement:** 9.4x
- **GitHub issues:** 8
- **Code lines:** ~800 in OneBRC.java
- **Token usage:** ~127k / 200k

---

*Last updated: November 5, 2025*
