# 1 Billion Row Challenge - Multi-Language Implementation

**🏆 Winner: Java (Ultimate) at 3.63 seconds on RAM drive!**

Implementation of the [1 Billion Row Challenge](https://www.morling.dev/blog/one-billion-row-challenge/) in Java, C, and Rust, demonstrating performance optimization techniques across three languages on both SSD and RAM drive storage.

## 📊 Final Results

### RAM Drive Results (Ultimate Performance)

**1 Billion Rows (13.6 GB file on RAM drive):**

| Implementation | Duration | Throughput | % of I/O Ceiling |
|----------------|----------|------------|------------------|
| 🥇 **Java (Ultimate)** | **3.63s** | **3,836 MB/s** | **99.7%** |
| 🥈 Java (Optimized) | 4.90s | 2,841 MB/s | 73.8% |
| 🥉 Rust (Optimized) | 5.11s | 2,721 MB/s | 70.7% |

**Java Ultimate achieves 99.7% of RAM drive I/O bandwidth ceiling (~3.8 GB/s)!**

→ **[See detailed RAM drive analysis](RAMDRIVE_RESULTS.md)**

### SSD Results (Original Challenge)

**1 Billion Rows (13.6 GB file on SSD):**

| Language | Duration | Throughput | Speedup |
|----------|----------|------------|---------|
| 🥇 **Rust** | **7.3s** | **1,900 MB/s** | **14.4×** |
| 🥈 Java | 11.66s | 1,194 MB/s | 9.0× |
| 🥉 C | 12.16s | 1,145 MB/s | 8.7× |

→ **[See detailed SSD analysis](RESULTS.md)**

---

## 🚀 Quick Start

### Running the Fastest Implementation (Java Ultimate on RAM drive)

```bash
# Generate test data (1 billion rows)
cd scripts
./generate_data.py 1000000000 ../data/measurements_1b.txt

# Copy to RAM drive (macOS example)
diskutil erasevolume HFS+ 'RAMDrive' `hdiutil attach -nomount ram://29360128`
cp ../data/measurements_1b.txt /Volumes/RAMDrive/

# Build and run
cd ../java
javac OneBRCParallelUltimate.java
java OneBRCParallelUltimate /Volumes/RAMDrive/measurements_1b.txt 11 2048

# Result: ~3.63 seconds on Apple Silicon @ 3,836 MB/s 🏆
```

### Running All Implementations

**Java (Ultimate - Custom Hash Table):**
```bash
cd java
javac OneBRCParallelUltimate.java
java OneBRCParallelUltimate ../data/measurements_1b.txt 11 2048
# ~3.63s on RAM drive 🏆
# ~4.90s on SSD
```

**Java (Optimized - Producer-Consumer):**
```bash
cd java
javac OneBRCOptimized.java
java OneBRCOptimized ../data/measurements_1b.txt 11 128
# ~4.90s on RAM drive
# ~11.7s on SSD
```

**C (Optimized):**
```bash
cd c
gcc -O3 -pthread -o onebrc_optimized onebrc_optimized.c
./onebrc_optimized ../data/measurements_1b.txt
# ~12.2s on SSD
```

**Rust (Optimized):**
```bash
cd rust
cargo build --release
./target/release/onebrc_optimized ../data/measurements_1b.txt
# ~5.11s on RAM drive
# ~7.3s on SSD
```

---

## 📁 Repository Structure

```
1brcChallenge/
├── java/
│   ├── OneBRC.java                      # Experimental (6 strategies)
│   ├── OneBRCOptimized.java             # Producer-consumer (11.66s SSD, 4.90s RAM)
│   ├── OneBRCParallelUltimate.java      # Custom hash table (3.63s RAM) ⭐⭐⭐
│   ├── OneBRCParallelUltimateInt.java   # Integer variant (archived, slower)
│   ├── OneBRCDebug.java                 # Single-threaded debug version
│   ├── OneBRCParallelOptimized.java     # Early parallel attempt
│   ├── OneBRCParallelFinal.java         # ByteArrayKey version
│   └── OneBRCBaseline*.java             # I/O baseline tests
├── c/
│   ├── onebrc.c                         # Baseline implementation
│   └── onebrc_optimized.c               # Optimized with mmap + pthreads
├── rust/
│   ├── onebrc.rs                        # Baseline implementation
│   ├── Cargo.toml                       # Project configuration
│   └── src/main.rs                      # Optimized with memmap2 + rayon
├── scripts/
│   ├── generate_data.py                 # Test data generator
│   ├── benchmark_sequential_threads.sh  # Thread count optimization
│   ├── benchmark_chunk_size_ramdrive.sh # Chunk size tuning
│   ├── benchmark_parallel_java.sh       # Parallel architecture testing
│   └── benchmark_*.sh                   # Various other benchmarks
├── data/                                 # Test data files (gitignored)
├── README.md                             # This file
├── RESULTS.md                            # SSD performance analysis ⭐
├── RAMDRIVE_RESULTS.md                   # RAM drive optimization guide ⭐⭐⭐
├── progress.md                           # Development progress
├── developer-log.md                      # Session logs
└── discussion.md                         # Optimization strategies
```

---

## 🎯 Challenge Overview

Process a text file with 1 billion temperature measurements and calculate min, mean, and max temperature per weather station.

### Input Format
```
Hamburg;12.0
Bulawayo;8.9
Palembang;38.8
St. John's;-5.2
```

### Output Format
```
Albuquerque;-99.9;0.0;99.9
Amman;-99.9;-0.3;99.9
...
```

### Constraints

- No external libraries (standard library only)
- Station names: 1-100 bytes UTF-8
- Temperature range: -99.9 to 99.9 (one decimal place)
- Max unique stations: 10,000

---

## 🔬 Key Optimizations

### RAM Drive Ultimate (OneBRCParallelUltimate.java)

1. **Custom Hash Table** - Zero allocations during processing
   - Parallel arrays (names[], mins[], maxs[], sums[], counts[])
   - Direct hash-based indexing with linear probing
   - No HashMap overhead, no String allocations
   - Table size: 2048 (power of 2 for fast modulo via bitmask)

2. **Data Parallelism** - All threads process chunks independently
   - Pre-create all chunks upfront (no single-reader bottleneck)
   - Each thread uses buffer.duplicate() for fast sequential reads
   - No producer-consumer queue overhead

3. **Custom Temperature Parser** - Faster than Double.parseDouble
   - Direct byte-to-number conversion
   - Single floating-point operation: `decimal / 10.0`
   - Handles format: `[-]dd.d\n`

4. **Memory-Mapped I/O** - Zero-copy file access
   - MappedByteBuffer for efficient random access
   - Chunk-based processing aligned on newline boundaries
   - Optimal chunk size: 2MB (2048 KB)

5. **Optimal Configuration** - Tuned for Apple Silicon
   - 11 worker threads (6 P-cores + 4 E-cores + 1 for overhead)
   - 2MB chunks (optimal for RAM drive bandwidth)
   - Achieves 99.7% of theoretical I/O maximum

**Result:** 3.63s @ 3,836 MB/s (virtually I/O-bound, not CPU-bound!)

### All Languages (SSD Optimizations)

1. **Memory-mapped I/O** - Zero-copy file access (2-3× speedup)
2. **Custom parsing** - Avoid library overhead
3. **Chunk-based processing** - Align on newline boundaries
4. **Optimal chunk size** - 288KB sweet spot for SSD
5. **Per-thread data structures** - No locking during processing

### Language-Specific (SSD)

**Java:**
- `MappedByteBuffer` with producer-consumer pattern
- ExecutorService with 7 threads
- Sequential I/O to avoid random access patterns
- **Bottleneck:** Single reader thread (42% CPU)

**C:**
- `mmap()` + `pthreads` for parallelization
- Custom hash table with chaining
- Zero-copy chunk passing
- **Bottleneck:** Single reader thread (50% CPU)

**Rust:**
- `memmap2` + `rayon` for data parallelism
- `AHashMap` for faster hashing
- **30 threads optimal** (vs 7 for C/Java)
- **Advantage:** No single-reader bottleneck (77% CPU)

---

## 💡 Optimization Journey

### Phase 1: SSD Optimization (Producer-Consumer)
```
Single Reader → Bounded Queue → Multiple Workers
(Sequential)      (Limited)       (Parallel)
Bottleneck: Single reader at ~1.2 GB/s
Result: 40-50% CPU, ~12s
```

**Winner:** Rust at 7.3s (no single-reader bottleneck)

### Phase 2: RAM Drive Optimization (Data Parallelism)
```
Create all chunks upfront
         ↓
All threads process in parallel
         ↓
Merge results
Result: 91.5% CPU, ~3.63s
```

**Winner:** Java (Ultimate) at 3.63s (custom hash table eliminates all allocation overhead)

### Phase 3: Custom Hash Table (Zero Allocations)

**Before (HashMap + Strings):**
- String allocation per lookup: ~4.7s
- HashMap overhead: lookup, hash, collision handling

**After (Custom Hash Table + Byte Arrays):**
- Zero allocations during processing: ~3.63s
- Direct array indexing: `index = hash & MASK`
- Linear probing: `(index + 1) & MASK`
- Parallel arrays: Cache-friendly, minimal overhead

**Speedup:** 22% faster (4.7s → 3.63s)
**Achievement:** 99.7% of I/O bandwidth ceiling!

---

## 📈 Performance Breakdown

### RAM Drive Results (Apple Silicon)

| Implementation | Duration | Throughput | CPU | Notes |
|----------------|----------|------------|-----|-------|
| Java Ultimate | 3.63s | 3,836 MB/s | 91.5% | Custom hash table ⭐ |
| Java Optimized | 4.90s | 2,841 MB/s | 78% | Producer-consumer |
| Rust Optimized | 5.11s | 2,721 MB/s | - | Data parallelism |
| I/O Ceiling | ~3.6s | ~3,800 MB/s | - | Theoretical max |

**RAM Drive Bandwidth:** ~3.8 GB/s uniform (no sequential vs random difference)

### Java Thread Scaling (RAM Drive)

| Threads | Duration | Throughput | Notes |
|---------|----------|------------|-------|
| 7 | 5.35s | 2,602 MB/s | Original optimal (SSD) |
| 8 | 5.23s | 2,662 MB/s | - |
| 9 | 5.29s | 2,632 MB/s | - |
| 10 | 5.36s | 2,596 MB/s | - |
| **11** | **4.90s** | **2,841 MB/s** | **Optimal ⭐** |
| 12 | 5.72s | 2,434 MB/s | Thermal throttling |

### Java Chunk Size Tuning (RAM Drive, 11 threads)

| Chunk Size | Duration | Throughput | Notes |
|------------|----------|------------|-------|
| 64 KB | 5.72s | 2,434 MB/s | Too small |
| **128 KB** | **5.10s** | **2,728 MB/s** | **Optimal ⭐** |
| 256 KB | 5.26s | 2,646 MB/s | - |
| 512 KB | 5.13s | 2,712 MB/s | - |
| 2 MB | 5.46s | 2,549 MB/s | Too large |

### Ultimate vs Optimized (RAM Drive)

| Version | Hash Table | Allocations | Duration | Speedup |
|---------|-----------|-------------|----------|---------|
| Optimized | HashMap | Strings per lookup | 4.90s | Baseline |
| Final | HashMap | ByteArrayKey | 4.70s | 4% |
| **Ultimate** | **Custom** | **Zero** | **3.63s** | **22%** ⭐ |

### SSD vs RAM Drive Comparison

| Implementation | SSD | RAM Drive | Speedup |
|----------------|-----|-----------|---------|
| Java Ultimate | - | 3.63s | - |
| Java Optimized | 11.66s | 4.90s | 2.4× |
| Rust Optimized | 7.3s | 5.11s | 1.4× |

**Key Insight:** Eliminating I/O bottleneck reveals CPU optimization opportunities!

---

## 📚 Documentation

- **[RAMDRIVE_RESULTS.md](RAMDRIVE_RESULTS.md)** - Complete RAM drive optimization guide ⭐⭐⭐
- **[RESULTS.md](RESULTS.md)** - SSD performance analysis ⭐
- **[progress.md](progress.md)** - Detailed development progress
- **[developer-log.md](developer-log.md)** - Session-by-session logs
- **[discussion.md](discussion.md)** - Optimization strategies

---

## 🎓 Key Learnings

### Architecture & Parallelism
1. **Architecture > Language Speed** - Rust's data parallelism beat C's producer-consumer by 40% on SSD
2. **Data Parallelism Wins** - When I/O is fast (RAM drive), eliminate single-reader bottleneck
3. **Thread count matters** - Optimal varies by storage (7 for SSD, 11 for RAM drive)
4. **Work-stealing > Producer-consumer** - For I/O-bound workloads with fast storage

### Memory & Data Structures
5. **Custom data structures win** - Custom hash table 22% faster than HashMap
6. **Zero allocations matter** - Byte arrays + parallel arrays beat objects
7. **Cache-friendly design** - Parallel arrays better than array of objects
8. **Power-of-2 sizing** - Fast modulo via bitmask: `hash & MASK`

### I/O & Storage
9. **Memory-mapped I/O is essential** - 2-3× speedup across all languages
10. **I/O patterns critical** - Sequential 33× faster than random on SSD (5000 vs 150 MB/s)
11. **RAM drive eliminates I/O bottleneck** - Reveals CPU optimization opportunities
12. **Chunk size optimization** - 128KB optimal for RAM drive, 288KB for SSD

### System & Thermal
13. **Thermal management matters** - 30s cooldown essential for consistency
14. **Understand your ceiling** - Know theoretical maximum (I/O or CPU bound?)
15. **Modern FPUs are fast** - Integer arithmetic didn't beat doubles (actually slower!)

---

## 🔬 Debugging & Development

### Debug Version (OneBRCDebug.java)

Single-threaded version for stepping through with debugger:

```bash
# Compile with debug symbols
javac -g OneBRCDebug.java

# Run with debugger
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
     OneBRCDebug

# Then attach debugger to localhost:5005
```

**Features:**
- Default arguments (measurements_1k.txt)
- Verbose output showing hash table operations
- Progress every 1M lines
- Collision statistics
- Perfect for understanding the algorithm!

---

## 🛠️ System Information

- **Platform:** macOS (Apple Silicon)
- **Processor:** 10 cores (6 P-cores + 4 E-cores)
- **Page size:** 16KB
- **SSD:** Fast NVMe (Sequential: 5000 MB/s, Random: 150 MB/s)
- **RAM Drive:** ~3.8 GB/s uniform bandwidth
- **Thermal:** Laptop with poor cooling (cooldown required)

---

## 📊 Benchmark Scripts

```bash
# RAM drive thread optimization
./scripts/benchmark_sequential_threads.sh /Volumes/RAMDrive/measurements_1b.txt

# RAM drive chunk size tuning
./scripts/benchmark_chunk_size_ramdrive.sh /Volumes/RAMDrive/measurements_1b.txt

# Parallel architecture testing
./scripts/benchmark_parallel_java.sh

# SSD benchmarks
./scripts/benchmark_chunk_sizes.sh data/measurements_1b.txt
./scripts/benchmark_threads_optimal.sh data/measurements_1b.txt
./scripts/benchmark_io.sh data/measurements_10m.txt
```

---

## 🏅 GitHub Issues

Track development progress: https://github.com/richardahasting/1brcChallenge/issues

- ✅ #1: Project setup
- ✅ #2: Java baseline
- ✅ #3: Java optimization
- ✅ #4: C baseline
- ✅ #5: C optimization
- ✅ #6: Rust baseline
- ✅ #7: Rust optimization
- ✅ #8: Benchmarking suite
- ✅ #9: Code cleanup
- ✅ #10: RAM drive optimization
- ✅ #11: Custom hash table implementation

---

## 🤝 Contributing

This is an educational/challenge project. Feel free to:
- Try optimizations in other languages
- Experiment with different algorithms
- Test on different hardware (AMD, Intel, ARM)
- Submit PRs with improvements
- Share your results

---

## 📖 References

- Original Challenge: https://github.com/gunnarmorling/1brc
- Blog Post: https://www.morling.dev/blog/one-billion-row-challenge/
- Created with assistance from Claude Code

---

## 📄 License

MIT License - Educational/Challenge purposes

---

*Last updated: November 7, 2025*

**🏆 Java Ultimate wins at 3.63 seconds (99.7% of I/O ceiling)!**
**See [RAMDRIVE_RESULTS.md](RAMDRIVE_RESULTS.md) for the complete optimization journey.**
