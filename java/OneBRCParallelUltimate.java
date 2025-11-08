import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * 1 Billion Row Challenge - Ultimate Optimized Java Implementation
 *
 * ULTIMATE optimization: Custom hash table using raw hash values!
 * - No HashMap overhead
 * - No object allocations (String or ByteArrayKey)
 * - Direct hash-based indexing
 * - Linear probing for collisions
 * - Raw byte arrays stored in table
 *
 * This eliminates ALL allocation overhead during processing!
 *
 * Usage:
 *   javac OneBRCParallelUltimate.java
 *   java OneBRCParallelUltimate <input_file> [num_threads] [chunk_size_kb]
 */
public class OneBRCParallelUltimate {

    // ============================================================
    // Configuration defaults
    // ============================================================

    private static final int DEFAULT_NUM_THREADS = 11;
    private static final int DEFAULT_CHUNK_SIZE_KB = 2048;  // 2MB optimal

    // ============================================================
    // Custom Hash Table (zero allocation!)
    // ============================================================

    /**
     * Custom hash table that stores raw byte arrays and stats.
     * No object allocations - just arrays and primitives!
     *
     * Challenge allows up to 1000 unique stations.
     * Use table size 2048 (next power of 2, ~50% load factor).
     * Linear probing for collision resolution.
     */
    static class StationTable {
        private static final int TABLE_SIZE = 2048;  // Power of 2 for fast modulo
        private static final int MASK = TABLE_SIZE - 1;

        // Parallel arrays (more cache-friendly than array of objects)
        private final byte[][] names;      // Station names (raw bytes)
        private final int[] lengths;       // Name lengths
        private final double[] mins;       // Min temperatures
        private final double[] maxs;       // Max temperatures
        private final double[] sums;       // Sum of temperatures
        private final long[] counts;       // Count of measurements
        private final boolean[] occupied;  // Slot occupied flag

        StationTable() {
            names = new byte[TABLE_SIZE][];
            lengths = new int[TABLE_SIZE];
            mins = new double[TABLE_SIZE];
            maxs = new double[TABLE_SIZE];
            sums = new double[TABLE_SIZE];
            counts = new long[TABLE_SIZE];
            occupied = new boolean[TABLE_SIZE];

            // Initialize mins/maxs
            Arrays.fill(mins, Double.POSITIVE_INFINITY);
            Arrays.fill(maxs, Double.NEGATIVE_INFINITY);
        }

        /**
         * Update or insert a measurement.
         * Returns without allocation - just updates arrays!
         */
        void update(byte[] nameBytes, int nameLength, double temperature) {
            // Calculate hash from raw bytes
            int hash = hashBytes(nameBytes, nameLength);
            int index = hash & MASK;  // Fast modulo for power of 2

            // Linear probing to find slot
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
        }

        /**
         * Convert to HashMap for final merge (only ~100 conversions!)
         */
        Map<String, Stats> toMap() {
            Map<String, Stats> result = new HashMap<>(128);
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

        /**
         * Fast hash function for byte arrays.
         * Using a simple but effective hash (similar to Java's Arrays.hashCode).
         */
        private static int hashBytes(byte[] bytes, int length) {
            int hash = 1;
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + bytes[i];
            }
            return hash;
        }

        /**
         * Compare byte arrays without allocation.
         */
        private static boolean arrayEquals(byte[] a, byte[] b, int length) {
            for (int i = 0; i < length; i++) {
                if (a[i] != b[i]) return false;
            }
            return true;
        }
    }

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
        final MappedByteBuffer buffer;
        final int start;
        final int end;

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
            System.err.println("Usage: java OneBRCParallelUltimate <input_file> [num_threads] [chunk_size_kb]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  input_file      Path to measurements file");
            System.err.println("  num_threads     (optional) Number of worker threads (default: 11)");
            System.err.println("  chunk_size_kb   (optional) Chunk size in KB (default: 2048)");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_THREADS;
        int chunkSizeKB = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE_KB;

        System.err.println("=== 1 Billion Row Challenge - Ultimate Optimized ===");
        System.err.println("Input file: " + inputFile);
        System.err.println("Worker threads: " + numThreads);
        System.err.println("Chunk size: " + chunkSizeKB + " KB");
        System.err.println();
        System.err.println("Optimizations: Custom hash table with zero allocations!");
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

            List<Callable<StationTable>> tasks = new ArrayList<>();
            for (WorkChunk chunk : chunks) {
                tasks.add(() -> processChunk(chunk));
            }

            // Execute all tasks in parallel and wait for completion
            List<Future<StationTable>> futures = executor.invokeAll(tasks);

            // Merge results from all workers
            Map<String, Stats> merged = new HashMap<>(256);
            for (Future<StationTable> future : futures) {
                StationTable table = future.get();
                Map<String, Stats> workerResult = table.toMap();  // Convert to Map (only ~100 entries)

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

                // Find next newline boundary
                if (chunkEnd < buffer.limit()) {
                    while (chunkEnd < buffer.limit() && buffer.get(chunkEnd) != '\n') {
                        chunkEnd++;
                    }
                    chunkEnd++;  // Include the newline
                } else {
                    chunkEnd = buffer.limit();
                }

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
     * Processes a single chunk using custom hash table.
     *
     * ZERO allocations during processing (except initial table creation)!
     */
    private static StationTable processChunk(WorkChunk chunk) {
        StationTable table = new StationTable();
        byte[] stationBytes = new byte[100];  // Reused buffer for station names

        // Create duplicate buffer for fast sequential access
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

            // Update custom hash table (NO allocations!)
            table.update(stationBytes, stationLen, temperature);
        }

        return table;
    }

    // ============================================================
    // Temperature parsing (custom, optimized)
    // ============================================================

    /**
     * Parse temperature from buffer.
     *
     * Format: [-]dd.d\n
     * Examples:
     *   "23.5\n" → 23.5
     *   "-12.3\n" → -12.3
     *   "99.9\n" → 99.9
     *
     * Custom parser is faster than Double.parseDouble.
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

        // Combine: value + decimal/10.0 (e.g., 23 + 5/10.0 = 23.5)
        double result = value + decimal / 10.0;
        return negative ? -result : result;
    }
}
