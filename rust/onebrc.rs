/**
 * 1 Billion Row Challenge - Rust Baseline Implementation
 *
 * Simple, correct implementation focusing on clarity over performance.
 * This serves as the baseline for optimization experiments.
 *
 * Features:
 * - Standard BufRead for I/O
 * - HashMap for station storage
 * - Custom temperature parsing
 * - Single-threaded processing
 *
 * Usage:
 *   rustc -O onebrc.rs
 *   ./onebrc <input_file>
 *
 * Author: Richard Hastings with Claude Code
 */

use std::collections::HashMap;
use std::env;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::time::Instant;

/// Station statistics
#[derive(Debug, Clone)]
struct Stats {
    min: f64,
    max: f64,
    sum: f64,
    count: u64,
}

impl Stats {
    /// Create new statistics with initial value
    fn new(temp: f64) -> Self {
        Stats {
            min: temp,
            max: temp,
            sum: temp,
            count: 1,
        }
    }

    /// Update statistics with a new temperature
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

    /// Calculate mean temperature
    fn mean(&self) -> f64 {
        self.sum / self.count as f64
    }
}

/// Parse temperature from string slice
/// Format: [-]dd.d
fn parse_temperature(s: &str) -> Option<f64> {
    let s = s.trim();

    // Handle negative sign
    let (negative, s) = if s.starts_with('-') {
        (true, &s[1..])
    } else {
        (false, s)
    };

    // Split on decimal point
    let parts: Vec<&str> = s.split('.').collect();
    if parts.len() != 2 {
        return None;
    }

    // Parse integer and decimal parts
    let integer: i32 = parts[0].parse().ok()?;
    let decimal: i32 = parts[1].parse().ok()?;

    // Combine
    let mut value = integer as f64 + decimal as f64 / 10.0;
    if negative {
        value = -value;
    }

    Some(value)
}

/// Process a single line
fn process_line(line: &str, stations: &mut HashMap<String, Stats>) {
    // Find semicolon separator
    let parts: Vec<&str> = line.split(';').collect();
    if parts.len() != 2 {
        return; // Invalid line
    }

    let station_name = parts[0];
    let temp_str = parts[1];

    // Parse temperature
    if let Some(temperature) = parse_temperature(temp_str) {
        // Update or insert statistics
        stations
            .entry(station_name.to_string())
            .and_modify(|stats| stats.update(temperature))
            .or_insert_with(|| Stats::new(temperature));
    }
}

/// Print results sorted by station name
fn print_results(stations: &HashMap<String, Stats>) {
    // Collect and sort stations by name
    let mut station_vec: Vec<(&String, &Stats)> = stations.iter().collect();
    station_vec.sort_by(|a, b| a.0.cmp(b.0));

    eprintln!("\nFirst 5 stations (alphabetically):");
    for (name, stats) in station_vec.iter().take(5) {
        eprintln!(
            "  {};{:.1};{:.1};{:.1}",
            name,
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
        eprintln!("Usage: {} <input_file>", args[0]);
        std::process::exit(1);
    }

    let filename = &args[1];

    eprintln!("=== 1 Billion Row Challenge - Rust Baseline ===");
    eprintln!("Input file: {}", filename);
    eprintln!("Strategy: Standard BufRead, single-threaded\n");

    // Open file
    let file = match File::open(filename) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("Error: Cannot open file '{}': {}", filename, e);
            std::process::exit(1);
        }
    };

    let reader = BufReader::new(file);
    let mut stations: HashMap<String, Stats> = HashMap::new();

    // Start timing
    let start = Instant::now();

    // Process file line by line
    for line in reader.lines() {
        match line {
            Ok(line) => process_line(&line, &mut stations),
            Err(e) => {
                eprintln!("Error reading line: {}", e);
                continue;
            }
        }
    }

    let duration = start.elapsed();

    // Print results
    eprintln!("\n=== Results ===");
    eprintln!("Stations found: {}", stations.len());
    eprintln!("Duration: {:.2} seconds", duration.as_secs_f64());

    print_results(&stations);
}
