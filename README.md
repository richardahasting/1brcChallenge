# 1 Billion Row Challenge - Multi-Language Implementation

Implementation of the [1 Billion Row Challenge](https://www.morling.dev/blog/one-billion-row-challenge/) in Java, C, and Rust.

## Challenge Overview

Process a text file with 1 billion temperature measurements (~10GB) and calculate min, mean, and max temperature per weather station.

### Input Format
```
Hamburg;12.0
Bulawayo;8.9
Palembang;38.8
```

### Output Format
```
Bulawayo;8.9;22.1;35.2
Hamburg;12.0;23.1;34.2
Palembang;38.8;39.9;41.0
```

## Constraints

- **No external libraries** - Standard library only
- **Single source file** per implementation
- **Runtime computation** - No build-time processing
- **Station names**: 1-100 bytes UTF-8
- **Temperature range**: -99.9 to 99.9 (one decimal place)
- **Max unique stations**: 10,000

## Project Structure

```
1brcChallenge/
├── java/           # Java implementations
├── c/              # C implementations
├── rust/           # Rust implementations
├── scripts/        # Helper scripts (data generation, benchmarking)
├── data/           # Test data files (gitignored)
└── README.md
```

## Implementations

### Java
- **Baseline**: Simple BufferedReader approach
- **Optimized**: Memory-mapped files, parallel streams, custom parsing

### C
- **Baseline**: Standard fopen/fread approach
- **Optimized**: mmap, custom hash table, SIMD optimizations

### Rust
- **Baseline**: BufRead with HashMap
- **Optimized**: Memory mapping, rayon parallelism, zero-copy parsing

## Building and Running

### Generate Test Data
```bash
./scripts/generate_data.py <num_rows> <output_file>
# Example: ./scripts/generate_data.py 1000000 data/measurements_1m.txt
```

### Java
```bash
cd java
javac OneBRC.java
java OneBRC ../data/measurements.txt
```

### C
```bash
cd c
gcc -O3 -march=native -o onebrc onebrc.c
./onebrc ../data/measurements.txt
```

### Rust
```bash
cd rust
rustc -C opt-level=3 -C target-cpu=native onebrc.rs
./onebrc ../data/measurements.txt
```

## Benchmarking

```bash
./scripts/benchmark.sh
```

## Performance Results

| Implementation | Time (1B rows) | Memory Usage | Notes |
|---------------|----------------|--------------|-------|
| Java Baseline | TBD | TBD | Simple approach |
| Java Optimized | TBD | TBD | Memory-mapped, parallel |
| C Baseline | TBD | TBD | Standard I/O |
| C Optimized | TBD | TBD | mmap, SIMD |
| Rust Baseline | TBD | TBD | Idiomatic Rust |
| Rust Optimized | TBD | TBD | Zero-copy, parallel |

## GitHub Issues

Track progress at: https://github.com/richardahasting/1brcChallenge/issues

## License

MIT License - Educational/Challenge purposes
