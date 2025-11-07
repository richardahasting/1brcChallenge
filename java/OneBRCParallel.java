import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * 1 Billion Row Challenge - Parallel Java Implementation
 *
 * This version removes the sequential reader bottleneck by creating all chunks
 * upfront and processing them in parallel (similar to Rust's rayon approach).
 *
 * Key differences from OneBRCOptimized:
 * - NO single sequential reader thread
 * - NO producer-consumer queue
 * - All chunks created upfront (just metadata: buffer, start, end)
 * - All threads process chunks in parallel via ExecutorService
 *
 * This architecture should work much better with RAM drive where random access
 * is as fast as sequential access.
 *
 * Usage:
 *   javac OneBRCParallel.java
 *   java OneBRCParallel <input_file> [num_threads] [chunk_size_kb]
 *
 * Examples:
 *   java OneBRCParallel measurements.txt           (use defaults: 7 threads, 288KB chunks)
 *   java OneBRCParallel measurements.txt 20        (20 threads, 288KB chunks)
 *   java OneBRCParallel measurements.txt 30 512    (30 threads, 512KB chunks)
 */
public class OneBRCParallel {

    // ============================================================
    // Configuration defaults
    // ============================================================

    private static final int DEFAULT_NUM_THREADS = 20;      // Higher default for RAM drive
    private static final int DEFAULT_CHUNK_SIZE_MB = 256;   // To be tuned (256 MB = ~50 chunks for 13GB file)

    // ============================================================
    // Statistics tracking
    // ============================================================

    /**
     * Per-station statistics: min, max, sum, count
     */
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

    /**
     * Work chunk metadata.
     * Contains reference to mapped buffer and boundaries (no data copying).
     */
    static class WorkChunk {
        final MappedByteBuffer buffer;  // Reference to mapped buffer (shared, zero-copy)
        final int start;                // Start position in buffer
        final int end;                  // End position (exclusive)

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
            System.err.println("Usage: java OneBRCParallel <input_file> [num_threads] [chunk_size_mb]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  input_file      Path to measurements file");
            System.err.println("  num_threads     (optional) Number of worker threads (default: 20)");
            System.err.println("  chunk_size_mb   (optional) Chunk size in MB (default: 256)");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  java OneBRCParallel measurements.txt");
            System.err.println("  java OneBRCParallel measurements.txt 30");
            System.err.println("  java OneBRCParallel measurements.txt 30 512");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_THREADS;
        int chunkSizeMB = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE_MB;

        System.err.println("=== 1 Billion Row Challenge - Parallel Architecture ===");
        System.err.println("Input file: " + inputFile);
        System.err.println("Worker threads: " + numThreads);
        System.err.println("Chunk size: " + chunkSizeMB + " MB");
        System.err.println();

        try {
            // Get CPU time tracking
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpuTimeStart = osBean.getProcessCpuTime(); // nanoseconds
            long startTime = System.nanoTime();

            Map<String, Stats> results = processFile(inputFile, numThreads, chunkSizeMB);

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

    /**
     * Main processing function using parallel architecture.
     *
     * Architecture:
     * - Main thread: creates chunks using pure math (no scanning!)
     * - ExecutorService: processes all chunks in parallel
     * - Each worker finds its own boundaries
     * - No sequential reader bottleneck!
     */
    private static Map<String, Stats> processFile(String filename, int numThreads, int chunkSizeMB)
            throws IOException, InterruptedException, ExecutionException {

        long chunkSize = chunkSizeMB * 1024L * 1024L;  // Convert MB to bytes

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();

            System.err.println("File size: " + String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0)));

            // Create chunks using pure math (no scanning!)
            long numChunks = (fileSize + chunkSize - 1) / chunkSize;  // Ceiling division
            System.err.println("Calculated " + numChunks + " chunks (no scanning required)");
            System.err.println();

            // Map entire file (handle >2GB by mapping in segments if needed)
            List<MappedByteBuffer> buffers = new ArrayList<>();
            long offset = 0;
            while (offset < fileSize) {
                long remainingInFile = fileSize - offset;
                long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);
                buffers.add(channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize));
                offset += segmentSize;
            }

            // Process all chunks in parallel
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            List<Callable<Map<String, Stats>>> tasks = new ArrayList<>();
            for (int i = 0; i < numChunks; i++) {
                final long chunkStart = i * chunkSize;
                final long chunkEnd = Math.min(chunkStart + chunkSize, fileSize);
                final boolean isFirstChunk = (i == 0);
                final boolean isLastChunk = (chunkEnd == fileSize);

                tasks.add(() -> processChunk(buffers, chunkStart, chunkEnd, fileSize, isFirstChunk, isLastChunk));
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
    // Helper: Get byte from file at absolute position
    // ============================================================

    /**
     * Read a single byte from file at given absolute position.
     * Handles multiple buffer segments for files > 2GB.
     */
    private static byte getByteAt(List<MappedByteBuffer> buffers, long position) {
        // Each buffer is up to 2GB (Integer.MAX_VALUE)
        int bufferIndex = (int)(position / Integer.MAX_VALUE);
        int positionInBuffer = (int)(position % Integer.MAX_VALUE);
        return buffers.get(bufferIndex).get(positionInBuffer);
    }

    // ============================================================
    // Worker processing (parallel)
    // ============================================================

    /**
     * Processes a chunk with calculated boundaries.
     *
     * Each worker:
     * 1. If not first chunk: scan forward from start to find first '\n', skip partial line
     * 2. Process all complete lines
     * 3. If not last chunk: scan forward past end to find '\n' to complete last line
     *
     * This ensures no line is processed twice and no line is skipped.
     */
    private static Map<String, Stats> processChunk(List<MappedByteBuffer> buffers,
                                                     long chunkStart, long chunkEnd, long fileSize,
                                                     boolean isFirstChunk, boolean isLastChunk) {
        Map<String, Stats> localStats = new HashMap<>(4096);
        byte[] stationBytes = new byte[100];  // Max station name length

        // Determine actual processing boundaries
        long processStart = chunkStart;
        long processEnd = chunkEnd;

        // If not first chunk, skip forward to first complete line
        if (!isFirstChunk) {
            while (processStart < fileSize && getByteAt(buffers, processStart) != '\n') {
                processStart++;
            }
            processStart++;  // Skip the '\n' itself
        }

        // If not last chunk, extend to include last complete line
        if (!isLastChunk) {
            while (processEnd < fileSize && getByteAt(buffers, processEnd) != '\n') {
                processEnd++;
            }
            processEnd++;  // Include the '\n'
        }

        // Now process [processStart, processEnd)
        long position = processStart;

        while (position < processEnd) {
            // Read station name until semicolon
            int stationLen = 0;
            byte b;
            while (position < processEnd && (b = getByteAt(buffers, position++)) != ';') {
                stationBytes[stationLen++] = b;
            }

            if (position >= processEnd) break;

            // Parse temperature: [-]dd.d\n
            boolean negative = false;
            int value = 0;

            b = getByteAt(buffers, position++);
            if (b == '-') {
                negative = true;
                b = getByteAt(buffers, position++);
            }

            // Read digits before decimal
            while (b != '.') {
                value = value * 10 + (b - '0');
                b = getByteAt(buffers, position++);
            }

            // Read decimal digit
            b = getByteAt(buffers, position++);
            int decimal = b - '0';

            // Skip newline
            position++;

            double temperature = value + decimal / 10.0;
            if (negative) temperature = -temperature;

            // Update statistics
            String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
            localStats.computeIfAbsent(station, k -> new Stats()).update(temperature);
        }

        return localStats;
    }

}
