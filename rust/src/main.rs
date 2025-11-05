/**
 * 1 Billion Row Challenge - Rust Optimized Implementation (v2)
 *
 * Optimizations applied:
 * - Memory-mapped I/O (memmap2) for zero-copy file access
 * - Multi-threading with rayon for parallel processing
 * - AHashMap for faster hashing
 * - Minimal allocations (use byte slices as keys during processing)
 * - Chunk-based processing with newline boundaries
 * - Per-thread hash maps (no locking during processing)
 * - Custom temperature parsing
 *
 * Usage:
 *   cargo build --release
 *   cargo run --release <input_file> [num_threads] [chunk_size_kb]
 *
 * Author: Richard Hastings with Claude Code
 */

use ahash::AHashMap;
use memmap2::Mmap;
use rayon::prelude::*;
use std::env;
use std::fs::File;
use std::time::Instant;

const DEFAULT_NUM_THREADS: usize = 30;  // Optimal for rayon (scales better than producer-consumer)
const DEFAULT_CHUNK_SIZE_KB: usize = 288;

/// Station statistics
#[derive(Debug, Clone)]
struct Stats {
    min: f64,
    max: f64,
    sum: f64,
    count: u64,
}

impl Stats {
    fn new(temp: f64) -> Self {
        Stats {
            min: temp,
            max: temp,
            sum: temp,
            count: 1,
        }
    }

    fn update(&mut self, temp: f64) {
        if temp < self.min {
            self.min = temp;
        }
        if temp > self.max {
            self.max = temp;
        }
        self.sum += temp;
        self.count += 1;
    }

    fn merge(&mut self, other: &Stats) {
        if other.min < self.min {
            self.min = other.min;
        }
        if other.max > self.max {
            self.max = other.max;
        }
        self.sum += other.sum;
        self.count += other.count;
    }

    fn mean(&self) -> f64 {
        self.sum / self.count as f64
    }
}

/// Parse temperature from byte slice
/// Format: [-]dd.d
#[inline]
fn parse_temperature(bytes: &[u8]) -> f64 {
    let mut idx = 0;
    let negative = if bytes[idx] == b'-' {
        idx += 1;
        true
    } else {
        false
    };

    let mut value = 0.0;

    // Integer part
    while idx < bytes.len() && bytes[idx] >= b'0' && bytes[idx] <= b'9' {
        value = value * 10.0 + (bytes[idx] - b'0') as f64;
        idx += 1;
    }

    // Skip decimal point
    if idx < bytes.len() && bytes[idx] == b'.' {
        idx += 1;
    }

    // Decimal digit
    if idx < bytes.len() && bytes[idx] >= b'0' && bytes[idx] <= b'9' {
        value += (bytes[idx] - b'0') as f64 / 10.0;
    }

    if negative { -value } else { value }
}

/// Process a chunk of data using byte slices as keys
fn process_chunk(data: &[u8]) -> AHashMap<Vec<u8>, Stats> {
    let mut stations: AHashMap<Vec<u8>, Stats> = AHashMap::new();
    let mut pos = 0;

    while pos < data.len() {
        // Find semicolon
        let mut semicolon_pos = pos;
        while semicolon_pos < data.len() && data[semicolon_pos] != b';' {
            semicolon_pos += 1;
        }

        if semicolon_pos >= data.len() {
            break;
        }

        // Extract station name
        let station_name = &data[pos..semicolon_pos];

        // Find newline
        let mut newline_pos = semicolon_pos + 1;
        while newline_pos < data.len() && data[newline_pos] != b'\n' {
            newline_pos += 1;
        }

        // Parse temperature
        let temp_bytes = &data[semicolon_pos + 1..newline_pos];
        let temperature = parse_temperature(temp_bytes);

        // Update or insert
        stations
            .entry(station_name.to_vec())
            .and_modify(|stats| stats.update(temperature))
            .or_insert_with(|| Stats::new(temperature));

        pos = newline_pos + 1;
    }

    stations
}

/// Create chunks aligned on newline boundaries
fn create_chunks(data: &[u8], chunk_size: usize) -> Vec<&[u8]> {
    let mut chunks = Vec::new();
    let mut pos = 0;

    while pos < data.len() {
        let mut end = (pos + chunk_size).min(data.len());

        // Find next newline
        if end < data.len() {
            while end < data.len() && data[end] != b'\n' {
                end += 1;
            }
            if end < data.len() {
                end += 1; // Include newline
            }
        }

        chunks.push(&data[pos..end]);
        pos = end;
    }

    chunks
}

/// Merge multiple hashmaps
fn merge_results(results: Vec<AHashMap<Vec<u8>, Stats>>) -> AHashMap<Vec<u8>, Stats> {
    let mut merged: AHashMap<Vec<u8>, Stats> = AHashMap::new();

    for result in results {
        for (station, stats) in result {
            merged
                .entry(station)
                .and_modify(|existing| existing.merge(&stats))
                .or_insert(stats);
        }
    }

    merged
}

/// Print results sorted by station name
fn print_results(stations: &AHashMap<Vec<u8>, Stats>) {
    let mut station_vec: Vec<(&Vec<u8>, &Stats)> = stations.iter().collect();
    station_vec.sort_by(|a, b| a.0.cmp(b.0));

    eprintln!("\nFirst 5 stations (alphabetically):");
    for (name, stats) in station_vec.iter().take(5) {
        let name_str = String::from_utf8_lossy(name);
        eprintln!(
            "  {};{:.1};{:.1};{:.1}",
            name_str,
            stats.min,
            stats.mean(),
            stats.max
        );
    }
}

fn main() {
    // Parse command line arguments
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: {} <input_file> [num_threads] [chunk_size_kb]", args[0]);
        eprintln!();
        eprintln!("Arguments:");
        eprintln!("  input_file      Path to measurements file");
        eprintln!("  num_threads     (optional) Number of worker threads (default: 7)");
        eprintln!("  chunk_size_kb   (optional) Chunk size in KB (default: 288)");
        std::process::exit(1);
    }

    let filename = &args[1];
    let num_threads = args
        .get(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(DEFAULT_NUM_THREADS);
    let chunk_size_kb = args
        .get(3)
        .and_then(|s| s.parse().ok())
        .unwrap_or(DEFAULT_CHUNK_SIZE_KB);
    let chunk_size = chunk_size_kb * 1024;

    eprintln!("=== 1 Billion Row Challenge - Rust Optimized v2 ===");
    eprintln!("Input file: {}", filename);
    eprintln!("Worker threads: {}", num_threads);
    eprintln!("Chunk size: {} KB\n", chunk_size_kb);

    // Configure rayon thread pool
    rayon::ThreadPoolBuilder::new()
        .num_threads(num_threads)
        .build_global()
        .unwrap();

    // Open and map file
    let file = match File::open(filename) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("Error: Cannot open file '{}': {}", filename, e);
            std::process::exit(1);
        }
    };

    let mmap = unsafe {
        match Mmap::map(&file) {
            Ok(m) => m,
            Err(e) => {
                eprintln!("Error: Cannot mmap file: {}", e);
                std::process::exit(1);
            }
        }
    };

    let file_size = mmap.len();
    eprintln!(
        "File size: {:.2} GB",
        file_size as f64 / (1024.0 * 1024.0 * 1024.0)
    );

    // Create chunks (just slices, no Arc needed)
    let chunks = create_chunks(&mmap, chunk_size);
    eprintln!("Created {} chunks\n", chunks.len());

    // Start timing
    let start = Instant::now();

    // Process chunks in parallel
    let results: Vec<AHashMap<Vec<u8>, Stats>> = chunks
        .par_iter()
        .map(|chunk_data| process_chunk(chunk_data))
        .collect();

    // Merge results
    let merged = merge_results(results);

    let duration = start.elapsed();

    // Calculate throughput
    let throughput_mb = (file_size as f64 / (1024.0 * 1024.0)) / duration.as_secs_f64();

    // Print results
    eprintln!("\n=== Results ===");
    eprintln!("Stations found: {}", merged.len());
    eprintln!("Duration: {:.2} seconds", duration.as_secs_f64());
    eprintln!("Throughput: {:.2} MB/s", throughput_mb);

    print_results(&merged);
}
