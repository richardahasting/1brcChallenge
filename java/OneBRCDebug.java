import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 1 Billion Row Challenge - Simplified Debug Version
 *
 * This is a SINGLE-THREADED version for easy debugging and stepping through.
 * Contains the same optimizations as Ultimate but without parallel complexity:
 * - Memory-mapped I/O
 * - buffer.duplicate() for fast sequential reads
 * - Custom hash table (zero allocations)
 * - Custom temperature parsing
 *
 * Perfect for understanding the algorithm step-by-step!
 *
 * Usage:
 *   javac -g OneBRCDebug.java
 *   java OneBRCDebug <input_file>
 *
 * Debug with:
 *   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
 *        OneBRCDebug ../data/measurements_1k.txt
 *
 * Then attach debugger to localhost:5005
 */
public class OneBRCDebug {

    // ============================================================
    // Custom Hash Table (same as Ultimate version)
    // ============================================================

    /**
     * Custom hash table that stores raw byte arrays and stats.
     *
     * Key concepts for debugging:
     * - Parallel arrays (names, mins, maxs, etc.) instead of objects
     * - Linear probing for collision resolution
     * - Direct hash-based indexing (no HashMap overhead)
     */
    static class StationTable {
        private static final int TABLE_SIZE = 2048;
        private static final int MASK = TABLE_SIZE - 1;

        // Parallel arrays
        private final byte[][] names;
        private final int[] lengths;
        private final double[] mins;
        private final double[] maxs;
        private final double[] sums;
        private final long[] counts;
        private final boolean[] occupied;

        // Statistics for debugging
        private int uniqueStations = 0;
        private int collisions = 0;

        StationTable() {
            names = new byte[TABLE_SIZE][];
            lengths = new int[TABLE_SIZE];
            mins = new double[TABLE_SIZE];
            maxs = new double[TABLE_SIZE];
            sums = new double[TABLE_SIZE];
            counts = new long[TABLE_SIZE];
            occupied = new boolean[TABLE_SIZE];

            Arrays.fill(mins, Double.POSITIVE_INFINITY);
            Arrays.fill(maxs, Double.NEGATIVE_INFINITY);
        }

        /**
         * Update or insert a measurement.
         *
         * SET BREAKPOINT HERE to watch hash table in action!
         */
        void update(byte[] nameBytes, int nameLength, double temperature) {
            // Calculate hash from raw bytes
            int hash = hashBytes(nameBytes, nameLength);
            int index = hash & MASK;  // Fast modulo (power of 2)

            // Linear probing to find slot
            int probeCount = 0;
            while (occupied[index]) {
                // Check if this is the same station
                if (lengths[index] == nameLength &&
                    arrayEquals(names[index], nameBytes, nameLength)) {
                    // Found it - update stats
                    if (temperature < mins[index]) mins[index] = temperature;
                    if (temperature > maxs[index]) maxs[index] = temperature;
                    sums[index] += temperature;
                    counts[index]++;
                    return;
                }

                // Collision - try next slot
                probeCount++;
                collisions++;
                index = (index + 1) & MASK;
            }

            // Not found - insert new entry
            names[index] = Arrays.copyOf(nameBytes, nameLength);
            lengths[index] = nameLength;
            mins[index] = temperature;
            maxs[index] = temperature;
            sums[index] = temperature;
            counts[index] = 1;
            occupied[index] = true;
            uniqueStations++;

            // Debug output for first few insertions
            if (uniqueStations <= 5) {
                String name = new String(nameBytes, 0, nameLength, StandardCharsets.UTF_8);
                System.out.println("  [DEBUG] Inserted station #" + uniqueStations +
                                 ": '" + name + "' at index " + index +
                                 (probeCount > 0 ? " (after " + probeCount + " collisions)" : ""));
            }
        }

        private static int hashBytes(byte[] bytes, int length) {
            int hash = 1;
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + bytes[i];
            }
            return hash;
        }

        private static boolean arrayEquals(byte[] a, byte[] b, int length) {
            for (int i = 0; i < length; i++) {
                if (a[i] != b[i]) return false;
            }
            return true;
        }

        Map<String, Stats> toMap() {
            Map<String, Stats> result = new HashMap<>();
            for (int i = 0; i < TABLE_SIZE; i++) {
                if (occupied[i]) {
                    String name = new String(names[i], 0, lengths[i], StandardCharsets.UTF_8);
                    Stats stats = new Stats();
                    stats.min = mins[i];
                    stats.max = maxs[i];
                    stats.sum = sums[i];
                    stats.count = counts[i];
                    result.put(name, stats);
                }
            }
            return result;
        }

        void printStats() {
            System.out.println("\n[Hash Table Stats]");
            System.out.println("  Unique stations: " + uniqueStations);
            System.out.println("  Total collisions: " + collisions);
            System.out.println("  Load factor: " + String.format("%.1f%%",
                             (uniqueStations * 100.0 / TABLE_SIZE)));
        }
    }

    // ============================================================
    // Statistics
    // ============================================================

    static class Stats {
        double min, max, sum;
        long count;

        double getMean() {
            return sum / count;
        }

        @Override
        public String toString() {
            return String.format("%.1f;%.1f;%.1f", min, getMean(), max);
        }
    }

    // ============================================================
    // Main Entry Point
    // ============================================================

    public static void main(String[] args) {
        // Default to small test file for easy debugging
        String inputFile = "../data/measurements_1k.txt";

        if (args.length >= 1) {
            inputFile = args[0];
        }

        System.out.println("Usage: java OneBRCDebug [input_file]");
        System.out.println("  (defaults to: ../data/measurements_1k.txt)");

        System.out.println("=== 1 Billion Row Challenge - Debug Version ===");
        System.out.println("Input file: " + inputFile);
        System.out.println("Mode: Single-threaded (easy to debug)");
        System.out.println();

        try {
            long startTime = System.nanoTime();

            // Process file
            Map<String, Stats> results = processFile(inputFile);

            long endTime = System.nanoTime();
            double durationSeconds = (endTime - startTime) / 1_000_000_000.0;

            File file = new File(inputFile);
            long fileSizeBytes = file.length();
            double fileSizeMB = fileSizeBytes / (1024.0 * 1024.0);
            double throughputMBps = fileSizeMB / durationSeconds;

            System.out.println("\n=== Results ===");
            System.out.println("Stations found: " + results.size());
            System.out.println("Duration: " + String.format("%.2f seconds", durationSeconds));
            System.out.println("Throughput: " + String.format("%.2f MB/s", throughputMBps));
            System.out.println();

            // Output first 5 stations
            System.out.println("First 5 stations (alphabetically):");
            results.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(5)
                    .forEach(entry -> System.out.println("  " + entry.getKey() + ";" + entry.getValue()));

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ============================================================
    // Core Processing Logic (Single-threaded)
    // ============================================================

    /**
     * Process the entire file sequentially.
     *
     * SET BREAKPOINT HERE to start stepping through the algorithm!
     */
    private static Map<String, Stats> processFile(String filename) throws IOException {
        System.out.println("[Step 1] Opening file and creating memory mapping...");

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            System.out.println("  File size: " + String.format("%.2f MB", fileSize / (1024.0 * 1024.0)));

            // Create hash table
            System.out.println("\n[Step 2] Creating custom hash table...");
            StationTable table = new StationTable();

            // Map entire file
            System.out.println("\n[Step 3] Memory mapping file...");
            long offset = 0;
            long linesProcessed = 0;

            // Process each segment (for files > 2GB)
            while (offset < fileSize) {
                long remainingInFile = fileSize - offset;
                long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);

                System.out.println("  Mapping segment at offset " + offset +
                                 " (size: " + (segmentSize / 1024 / 1024) + " MB)");

                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize);

                // Process this segment
                linesProcessed += processSegment(buffer, table);

                offset += segmentSize;
            }

            System.out.println("\n[Step 4] Processed " + linesProcessed + " lines");

            // Print hash table stats
            table.printStats();

            // Convert to Map
            System.out.println("\n[Step 5] Converting to final result map...");
            return table.toMap();
        }
    }

    /**
     * Process a single memory-mapped segment.
     *
     * This is where the main work happens!
     * SET BREAKPOINT HERE to watch line-by-line processing.
     */
    private static long processSegment(MappedByteBuffer buffer, StationTable table) {
        byte[] stationBytes = new byte[100];  // Reused buffer
        long linesProcessed = 0;

        System.out.println("\n  [Processing segment...]");

        // Process all records in this segment
        while (buffer.hasRemaining()) {
            // Read station name until semicolon
            int stationLen = 0;
            byte b;
            while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                stationBytes[stationLen++] = b;
            }

            if (!buffer.hasRemaining()) break;

            // Parse temperature
            double temperature = parseTemperature(buffer);

            // Update hash table (SET BREAKPOINT HERE!)
            table.update(stationBytes, stationLen, temperature);

            linesProcessed++;

            // Progress output for debugging
            if (linesProcessed % 1000000 == 0) {
                System.out.println("    Processed " + (linesProcessed / 1000000) + "M lines...");
            }
        }

        return linesProcessed;
    }

    /**
     * Parse temperature from buffer.
     *
     * Format: [-]dd.d\n
     * SET BREAKPOINT HERE to see custom parsing in action!
     */
    private static double parseTemperature(MappedByteBuffer buffer) {
        boolean negative = false;
        int value = 0;

        byte b = buffer.get();

        // Check for negative sign
        if (b == '-') {
            negative = true;
            b = buffer.get();
        }

        // Read digits before decimal point
        while (b != '.') {
            value = value * 10 + (b - '0');
            b = buffer.get();
        }

        // Read decimal digit
        b = buffer.get();
        int decimal = b - '0';

        // Skip newline
        buffer.get();

        double result = value + decimal / 10.0;
        return negative ? -result : result;
    }
}
