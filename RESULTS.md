# 1 Billion Row Challenge - Final Results

**Repository:** https://github.com/richardahasting/1brcChallenge

A comprehensive exploration of performance optimization across Java, C, and Rust for processing 1 billion temperature measurements (~13.6 GB file).

---

## 🏆 Final Performance Rankings

**1 Billion Rows (13.6 GB file) - Cold Cache, 30s Cooldown:**

| Rank | Language | Implementation | Duration | Throughput | Speedup | Threads | CPU |
|------|----------|----------------|----------|------------|---------|---------|-----|
| 🥇 | **Rust** | **Optimized v2** | **7.3s** | **1,900 MB/s** | **14.4×** | 30 | 77% |
| 🥈 | Java | Optimized | 11.66s | 1,194 MB/s | 9.0× | 7 | 42% |
| 🥉 | C | Optimized | 12.16s | 1,145 MB/s | 8.7× | 7 | 50% |
| | Java | Baseline | ~105s | 130 MB/s | 1.0× | 1 | 10% |
| | C | Baseline | 42.18s | 323 MB/s | 2.5× | 1 | 10% |
| | Rust | Baseline* | ~125s | ~111 MB/s | 0.8× | 1 | - |

*Rust baseline extrapolated from 10M row test (1.25s)

### Key Finding

**Rust is 37% faster than Java and 40% faster than C!**

---

## 📊 Detailed Performance Analysis

### Rust Thread Scaling (288KB chunks)

| Threads | Duration | Throughput | CPU Usage | Notes |
|---------|----------|------------|-----------|-------|
| 7 | 14.23s | 978 MB/s | 32% | Same as C/Java default |
| 10 | 12.75s | 1,092 MB/s | - | Starting to scale |
| 12 | 11.26s | 1,236 MB/s | - | Faster than Java/C |
| 15 | 9.82s | 1,418 MB/s | - | Significant improvement |
| 20 | 8.71s | 1,598 MB/s | - | Continuing to scale |
| 25 | 7.76s | 1,795 MB/s | - | Strong scaling |
| **30** | **7.3s** | **1,900 MB/s** | **77%** | **Optimal** ⭐ |
| 35 | 7.38s | 1,888 MB/s | - | Diminishing returns |

**Key Insight:** Rust with rayon scales to 30 threads because it processes pre-created chunks in parallel with no single-reader bottleneck. C and Java use producer-consumer pattern with sequential reader, limiting them to ~40-50% CPU at 7 threads.

### Baseline Comparison (10M rows, ~140 MB)

| Language | Duration | Throughput | Method |
|----------|----------|------------|--------|
| **C** | **0.45s** | **311 MB/s** | fgets, custom parser |
| Java | 1.06s | 132 MB/s | BufferedReader, Double.parseDouble |
| Rust | 1.25s | 111 MB/s | BufRead, String allocations |

**Key Insight:** C baseline is fastest (2.4× faster than Java), but Rust wins when fully optimized!

---

## 🔧 Optimization Journey

### Java Optimizations

**Baseline → Optimized: 105s → 11.66s (9.0× speedup)**

1. Memory-mapped I/O: ~105s → ~45s (2.3× faster)
2. Custom parsing: ~45s → ~40s (1.1× faster)
3. Parallelism (9 threads): ~40s → ~20s (2.0× faster)
4. Sequential I/O: Maintained 20s (handles >2GB correctly)
5. Optimal chunk size (288KB): ~20s → ~14s (1.4× faster)
6. Optimal threads (7): ~14s → ~11.2s (1.25× faster)

**Final: 11.66s @ 1,194 MB/s with 42% CPU (I/O bound)**

**Key discoveries:**
- Float is 50% SLOWER than double (CPU optimized for 64-bit)
- Chunk size matters: 56× reduction (16MB → 288KB) = 45% speedup
- Thermal throttling is real: 30s cooldown between runs essential
- Sequential I/O 33× faster than random on large files (5000 MB/s vs 150 MB/s)
- Java has overcounting bug: finds 104 stations instead of 99

### C Optimizations

**Baseline → Optimized: 42.18s → 12.16s (3.5× speedup)**

1. Memory-mapped I/O with mmap
2. Multi-threading with pthreads (7 threads)
3. Producer-consumer pattern for sequential I/O
4. Lock-free per-thread hash tables
5. Optimal 288KB chunks

**Final: 12.16s @ 1,145 MB/s with ~50% CPU (I/O bound)**

**Key discoveries:**
- C baseline already 2.4× faster than Java baseline
- Same optimizations yield similar performance to Java
- Both hit I/O bottleneck at ~40-50% CPU
- Single reader thread limits throughput to ~1.2 GB/s

### Rust Optimizations

**Baseline → Optimized: ~125s → 7.3s (17× speedup)**

1. Memory-mapped I/O (memmap2 crate)
2. Rayon for parallel processing
3. Custom byte-slice parsing
4. AHashMap for faster hashing (v2)
5. Removed Arc wrapper overhead (v2)
6. Optimal 30 threads (v2 tuning)

**Final: 7.3s @ 1,900 MB/s with 77% CPU**

**Key discoveries:**
- Rust baseline slowest (String allocations hurt)
- Rayon scales much better than producer-consumer (no single reader bottleneck)
- Can effectively use 30 threads vs 7 for C/Java
- 7 threads: 14.23s (32% CPU) → 30 threads: 7.3s (77% CPU)
- AHashMap faster than standard HashMap
- Byte slices more efficient than Vec<u8> allocations

---

## 🎯 Why Rust Won

### Architectural Advantage

**C/Java: Producer-Consumer Pattern**
```
Single Reader Thread → Bounded Queue → Multiple Workers
     (Sequential)         (Limited)      (Parallel)

- Reader scans for newlines sequentially
- Workers starve waiting for chunks
- Bottleneck: Single reader at ~1.2 GB/s
- Result: 40-50% CPU utilization, ~12s
```

**Rust: Data Parallelism with Rayon**
```
Main Thread: Create all chunks upfront (49,507 chunks)
     ↓
Rayon: All 30 threads process chunks in parallel
     (No reader bottleneck)

- All chunks created at start (just slice references)
- All threads work simultaneously on chunks
- No single sequential bottleneck
- Result: 77% CPU utilization, ~7.3s
```

### Implementation Differences

| Aspect | C/Java | Rust |
|--------|--------|------|
| **Chunking** | On-demand by reader | All upfront |
| **Synchronization** | Queue locks/conditions | Work-stealing (rayon) |
| **Bottleneck** | Sequential reader | Merge phase |
| **Parallelism** | Limited by reader | Full parallel processing |
| **CPU Utilization** | 40-50% | 77% |
| **Optimal Threads** | 7 | 30 |

### Why More Threads Help Rust

1. **No reader bottleneck** - All threads can grab and process chunks
2. **Rayon work-stealing** - Efficient load balancing across threads
3. **Memory bandwidth** - Can saturate memory bandwidth with more parallel reads
4. **No lock contention** - Each thread has independent hash table
5. **Merge is fast** - Final merge is small relative to processing time

---

## 💡 Key Learnings

### 1. Architecture Matters More Than Language

- Same optimizations (mmap, parallel, chunks) yield similar results in C/Java
- Different architecture (rayon vs producer-consumer) yields 40% improvement in Rust
- The algorithm and parallelization strategy matter more than language speed

### 2. Memory-Mapped I/O is Essential

- All languages: 2-3× faster than standard I/O
- Eliminates kernel-to-user space copy
- Critical for performance on large files

### 3. Custom Parsing Beats Library Functions

- All languages benefited from custom temperature parsing
- Avoids overhead of general-purpose parsers
- Direct byte-to-number conversion much faster

### 4. Thread Count Optimization is Critical

- C/Java optimal at 7 threads (matches physical cores)
- Rust optimal at 30 threads (can saturate memory bandwidth)
- More threads don't always help (depends on architecture)

### 5. Chunk Size Sweet Spot

- All languages optimal at 288KB chunks
- Too small: Queue/merge overhead
- Too large: Poor load balancing, less parallelism
- 288KB balances efficiency with parallelism

### 6. I/O Patterns Matter

- Sequential I/O: 5000 MB/s
- Random I/O: 150 MB/s (33× slower!)
- Must align chunks on record boundaries
- Single sequential reader limits C/Java but not Rust

### 7. Thermal Management is Real

- Laptop thermal throttling caused 10-20% variance
- 30s cooldown between runs essential for consistency
- Cold cache vs warm cache: 75% performance difference

### 8. Correctness Matters

- C and Rust: 99 stations ✓
- Java: 104 stations (overcounting bug)
- Always validate results, not just performance

### 9. Baseline Performance Varies

- C baseline fastest (no runtime overhead)
- Java baseline moderate (JVM overhead)
- Rust baseline slowest (safe defaults, allocations)
- But Rust scales best when optimized!

### 10. Work-Stealing > Producer-Consumer (for this workload)

- Rayon's work-stealing adapts better to varying chunk sizes
- No single thread bottleneck
- Better CPU utilization
- More scalable to higher thread counts

---

## 🛠️ Technical Implementation Details

### Common Optimizations (All Languages)

1. **Memory-mapped I/O** - Zero-copy file access
2. **Custom parsing** - Avoid library overhead
3. **Chunk-based processing** - Align on newline boundaries
4. **Per-thread data structures** - No locking during processing
5. **Merge at end** - Combine thread-local results
6. **Optimal chunk size** - 288KB sweet spot

### Language-Specific Highlights

**Java:**
- `MappedByteBuffer` with `FileChannel`
- `ExecutorService` with fixed thread pool
- `ArrayBlockingQueue` for backpressure
- Custom double parsing (2.2× faster than `Double.parseDouble`)
- Multiple 2GB segments to handle large files

**C:**
- `mmap()` system call for file mapping
- `pthreads` for parallelization
- `pthread_mutex` and `pthread_cond` for queue
- Custom hash table with chaining
- Zero-copy chunk passing (pointers, not data)

**Rust:**
- `memmap2` crate for safe memory mapping
- `rayon` crate for data parallelism
- `AHashMap` for faster hashing
- Byte slices (`&[u8]`) for zero-copy processing
- No unsafe code needed (except mmap which is inherently unsafe)

---

## 📁 Repository Structure

```
1brcChallenge/
├── java/
│   ├── OneBRC.java              # Experimental (6 strategies)
│   └── OneBRCOptimized.java     # Clean optimized version ⭐
├── c/
│   ├── onebrc.c                 # Baseline
│   └── onebrc_optimized.c       # Optimized with mmap + pthreads ⭐
├── rust/
│   ├── onebrc.rs                # Baseline
│   ├── Cargo.toml               # Dependencies
│   └── src/main.rs              # Optimized with memmap2 + rayon ⭐
├── scripts/
│   ├── generate_data.py         # Test data generator
│   ├── benchmark_io.sh          # I/O comparison
│   ├── benchmark_chunk_sizes.sh # Chunk size tuning
│   ├── benchmark_optimal_chunk.sh
│   └── benchmark_threads_optimal.sh
├── data/
│   ├── measurements_1k.txt
│   ├── measurements_10m.txt
│   └── measurements_1b.txt      # 13.6 GB, 1 billion rows
├── README.md                     # Project overview
├── RESULTS.md                    # This file
├── progress.md                   # Detailed progress report
├── developer-log.md              # Session-by-session log
└── discussion.md                 # Optimization strategies
```

---

## 🚀 Running the Implementations

### Java (Optimized)

```bash
cd java
javac OneBRCOptimized.java
java OneBRCOptimized ../data/measurements_1b.txt

# With custom settings
java OneBRCOptimized ../data/measurements_1b.txt 7 288
```

### C (Optimized)

```bash
cd c
gcc -O3 -pthread -o onebrc_optimized onebrc_optimized.c
./onebrc_optimized ../data/measurements_1b.txt

# With custom settings
./onebrc_optimized ../data/measurements_1b.txt 7 288
```

### Rust (Optimized) ⭐ Best Performance

```bash
cd rust
cargo build --release
./target/release/onebrc_optimized ../data/measurements_1b.txt

# With custom settings (30 threads optimal)
./target/release/onebrc_optimized ../data/measurements_1b.txt 30 288
```

### Generate Test Data

```bash
cd scripts
./generate_data.py 1000000000 ../data/measurements_1b.txt
```

---

## 📈 System Information

- **Platform:** macOS (Apple Silicon)
- **Processor:** 10 cores (likely 6 P-cores + 4 E-cores)
- **Page size:** 16KB
- **Storage:** Fast SSD
  - Sequential read: ~5000 MB/s
  - Random read: ~150 MB/s (33× slower!)
- **Thermal:** Laptop with poor cooling (30s cooldown required)

---

## 🎓 What We Learned

This project demonstrates that:

1. **The right algorithm beats language speed** - Rust's data parallelism architecture beat C's producer-consumer by 40%

2. **Performance optimization is iterative** - Multiple rounds of profiling and tuning required

3. **System limits matter** - All implementations eventually hit I/O or memory bandwidth limits

4. **Modern hardware is complex** - Thermal throttling, cache effects, and CPU architecture all impact results

5. **Each language has strengths:**
   - **C:** Fastest baseline, direct control, minimal overhead
   - **Java:** Excellent libraries, good JIT optimization, easy parallelization
   - **Rust:** Best final performance, memory safety, scales best with rayon

6. **Measurement is critical:**
   - Cold vs warm cache: 75% difference
   - Thermal throttling: 10-20% variance
   - Proper benchmarking essential

---

## 🏅 Final Thoughts

**Winner: Rust** 🦀

Rust's combination of:
- Zero-cost abstractions
- Memory safety without garbage collection
- Excellent parallel processing with rayon
- Ability to scale to 30+ threads effectively

...made it the clear winner at **7.3 seconds** for processing 1 billion rows.

However, this challenge also shows that **architectural choices matter more than language**. C and Java with producer-consumer hit ~12s, while Rust with data parallelism hit ~7s. The same optimization yielded similar results in C/Java, but a different approach in Rust yielded much better results.

**All three languages are capable of excellent performance when properly optimized.**

---

## 📚 References

- Challenge inspired by: https://github.com/gunnarmorling/1brc
- Repository: https://github.com/richardahasting/1brcChallenge
- Created with assistance from Claude Code

---

*Last updated: November 5, 2025*
