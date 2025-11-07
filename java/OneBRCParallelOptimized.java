import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * 1 Billion Row Challenge - Optimized Parallel Java Implementation
 *
 * Combines the best of both worlds:
 * - Parallel processing (no sequential reader bottleneck)
 * - Fast buffer.duplicate() access (no per-byte division/modulo overhead)
 *
 * Key optimizations:
 * - Memory-mapped I/O for zero-copy access
 * - Parallel chunk processing (all threads work simultaneously)
 * - Direct buffer references in chunks (not absolute positions)
 * - buffer.duplicate() for fast sequential reads (no math per byte!)
 * - Custom temperature parsing
 * - Chunk-based processing with newline boundaries
 *
 * Usage:
 *   javac OneBRCParallelOptimized.java
 *   java OneBRCParallelOptimized <input_file> [num_threads] [chunk_size_kb]
 */
public class OneBRCParallelOptimized {

    // ============================================================
    // Configuration defaults
    // ============================================================

    private static final int DEFAULT_NUM_THREADS = 11;      // Optimal for this hardware + RAM drive
    private static final int DEFAULT_CHUNK_SIZE_KB = 128;   // Optimal for RAM drive

    // ============================================================
    // Statistics tracking
    // ============================================================

    static class Stats {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        long count = 0;

        void update(double temperature) {
            if (temperature < min) min = temperature;
            if (temperature > max) max = temperature;
            sum += temperature;
            count++;
        }

        double getMean() {
            return sum / count;
        }

        void merge(Stats other) {
            if (other.min < this.min) this.min = other.min;
            if (other.max > this.max) this.max = other.max;
            this.sum += other.sum;
            this.count += other.count;
        }

        @Override
        public String toString() {
            return String.format("%.1f;%.1f;%.1f", min, getMean(), max);
        }
    }

    // ============================================================
    // Work distribution (zero-copy design)
    // ============================================================

    static class WorkChunk {
        final MappedByteBuffer buffer;  // Direct buffer reference (shared, zero-copy)
        final int start;                // Start position in THIS buffer
        final int end;                  // End position (exclusive) in THIS buffer

        WorkChunk(MappedByteBuffer buffer, int start, int end) {
            this.buffer = buffer;
            this.start = start;
            this.end = end;
        }
    }

    // ============================================================
    // Main entry point
    // ============================================================

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java OneBRCParallelOptimized <input_file> [num_threads] [chunk_size_kb]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  input_file      Path to measurements file");
            System.err.println("  num_threads     (optional) Number of worker threads (default: 11)");
            System.err.println("  chunk_size_kb   (optional) Chunk size in KB (default: 128)");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_THREADS;
        int chunkSizeKB = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE_KB;

        System.err.println("=== 1 Billion Row Challenge - Parallel Optimized ===");
        System.err.println("Input file: " + inputFile);
        System.err.println("Worker threads: " + numThreads);
        System.err.println("Chunk size: " + chunkSizeKB + " KB");
        System.err.println();

        try {
            // Get CPU time tracking
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpuTimeStart = osBean.getProcessCpuTime();
            long startTime = System.nanoTime();

            Map<String, Stats> results = processFile(inputFile, numThreads, chunkSizeKB);

            long endTime = System.nanoTime();
            long cpuTimeEnd = osBean.getProcessCpuTime();

            double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
            double cpuTimeSeconds = (cpuTimeEnd - cpuTimeStart) / 1_000_000_000.0;

            // Calculate CPU utilization
            int numCores = Runtime.getRuntime().availableProcessors();
            double cpuUtilization = (cpuTimeSeconds / durationSeconds) * 100.0;
            double cpuUtilizationPerCore = cpuUtilization / numCores;

            File file = new File(inputFile);
            long fileSizeBytes = file.length();
            double fileSizeMB = fileSizeBytes / (1024.0 * 1024.0);
            double throughputMBps = fileSizeMB / durationSeconds;

            System.err.println();
            System.err.println("=== Results ===");
            System.err.println("Stations found: " + results.size());
            System.err.println("Duration: " + String.format("%.2f seconds", durationSeconds));
            System.err.println("CPU time: " + String.format("%.2f seconds", cpuTimeSeconds));
            System.err.println("CPU utilization: " + String.format("%.1f%%", cpuUtilization) +
                             " (of " + numCores + " cores = " + String.format("%.1f%%", cpuUtilizationPerCore) + " per core)");
            System.err.println("Throughput: " + String.format("%.2f MB/s", throughputMBps));
            System.err.println();

            // Output first 5 stations for verification
            System.err.println("First 5 stations (alphabetically):");
            results.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(5)
                    .forEach(entry -> System.err.println("  " + entry.getKey() + ";" + entry.getValue()));

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ============================================================
    // Core processing logic
    // ============================================================

    private static Map<String, Stats> processFile(String filename, int numThreads, int chunkSizeKB)
            throws IOException, InterruptedException, ExecutionException {

        int chunkSize = chunkSizeKB * 1024;

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();

            System.err.println("File size: " + String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0)));

            // Create all chunks upfront
            long chunkCreationStart = System.nanoTime();
            List<WorkChunk> chunks = createChunks(channel, fileSize, chunkSize);
            long chunkCreationEnd = System.nanoTime();

            System.err.println("Created " + chunks.size() + " chunks in " +
                String.format("%.3f", (chunkCreationEnd - chunkCreationStart) / 1_000_000.0) + " ms");
            System.err.println();

            // Process all chunks in parallel
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            List<Callable<Map<String, Stats>>> tasks = new ArrayList<>();
            for (WorkChunk chunk : chunks) {
                tasks.add(() -> processChunk(chunk));
            }

            // Execute all tasks in parallel and wait for completion
            List<Future<Map<String, Stats>>> futures = executor.invokeAll(tasks);

            // Merge results from all workers
            Map<String, Stats> merged = new HashMap<>(16384);
            for (Future<Map<String, Stats>> future : futures) {
                Map<String, Stats> workerResult = future.get();

                for (Map.Entry<String, Stats> entry : workerResult.entrySet()) {
                    merged.merge(entry.getKey(), entry.getValue(), (existing, newStats) -> {
                        existing.merge(newStats);
                        return existing;
                    });
                }
            }

            executor.shutdown();
            return merged;
        }
    }

    // ============================================================
    // Chunk creation
    // ============================================================

    /**
     * Creates chunks with buffer references and newline-aligned boundaries.
     *
     * Handles files > 2GB by mapping in segments.
     */
    private static List<WorkChunk> createChunks(FileChannel channel, long fileSize, int chunkSize)
            throws IOException {

        List<WorkChunk> chunks = new ArrayList<>();
        long offset = 0;

        // Handle files > 2GB by mapping in segments
        while (offset < fileSize) {
            long remainingInFile = fileSize - offset;
            long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);

            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize);

            // Create chunks within this segment
            int position = 0;
            while (position < buffer.limit()) {
                int chunkStart = position;
                int available = Math.min(chunkSize, buffer.limit() - position);
                int chunkEnd = chunkStart + available;

                // Find next newline boundary (scan forward to ensure complete lines)
                if (chunkEnd < buffer.limit()) {
                    // Scan forward to find newline
                    while (chunkEnd < buffer.limit() && buffer.get(chunkEnd) != '\n') {
                        chunkEnd++;
                    }
                    chunkEnd++;  // Include the newline
                } else {
                    // Last chunk - goes to end of buffer
                    chunkEnd = buffer.limit();
                }

                // Create chunk with buffer reference (zero-copy)
                chunks.add(new WorkChunk(buffer, chunkStart, chunkEnd));

                position = chunkEnd;
            }

            offset += segmentSize;
        }

        return chunks;
    }

    // ============================================================
    // Worker processing (parallel)
    // ============================================================

    /**
     * Processes a single chunk using buffer.duplicate() for fast access.
     *
     * Key optimization: buffer.duplicate() allows sequential reads without
     * per-byte division/modulo overhead!
     */
    private static Map<String, Stats> processChunk(WorkChunk chunk) {
        Map<String, Stats> localStats = new HashMap<>(4096);
        byte[] stationBytes = new byte[100];  // Max station name length

        // Create duplicate buffer for this worker (independent position tracking)
        // This is the KEY optimization - no math per byte!
        MappedByteBuffer buffer = chunk.buffer.duplicate();
        buffer.position(chunk.start);
        buffer.limit(chunk.end);

        // Process all records in this chunk
        while (buffer.hasRemaining()) {
            // Read station name until semicolon
            int stationLen = 0;
            byte b;
            while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                stationBytes[stationLen++] = b;
            }

            if (!buffer.hasRemaining()) break;

            // Parse temperature (custom parser, faster than Double.parseDouble)
            double temperature = parseTemperature(buffer);

            // Update statistics
            String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
            localStats.computeIfAbsent(station, k -> new Stats()).update(temperature);
        }

        return localStats;
    }

    // ============================================================
    // Temperature parsing (custom, optimized)
    // ============================================================

    /**
     * Parse temperature directly from buffer without String allocation.
     *
     * Format: [-]dd.d followed by newline
     * Examples: "23.5\n", "-12.3\n", "99.9\n"
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
