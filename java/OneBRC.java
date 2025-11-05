import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 1 Billion Row Challenge - Java Implementation
 *
 * Benchmarking different parsing and storage strategies:
 * 1. Standard Double.parseDouble() with BufferedReader
 * 2. Custom double parsing with memory-mapped I/O
 * 3. Custom float parsing with memory-mapped I/O
 * 4. Short integer (tenths) parsing with memory-mapped I/O
 * 5. Parallel processing with custom double parsing
 *
 * Usage:
 *   java OneBRC -std <file>   (Standard doubles with BufferedReader)
 *   java OneBRC -dbl <file>   (Custom double parsing, mmap)
 *   java OneBRC -flt <file>   (Custom float parsing, mmap)
 *   java OneBRC -int <file>   (Short integer tenths, mmap)
 *   java OneBRC -par <file>   (Parallel processing with mmap)
 */
public class OneBRC {

    enum Strategy {
        STANDARD_DOUBLES,
        CUSTOM_DOUBLES,
        CUSTOM_FLOATS,
        SHORT_INTEGERS,
        PARALLEL,
        PARALLEL_RANDOM_IO  // Old approach - random I/O
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java OneBRC [-std|-dbl|-flt|-int|-par|-par-old] <input_file> [num_threads] [chunk_size_kb]");
            System.err.println("  -std      Standard Double.parseDouble() with BufferedReader");
            System.err.println("  -dbl      Custom double parsing with mmap");
            System.err.println("  -flt      Custom float parsing with mmap");
            System.err.println("  -int      Short integer (tenths) with mmap");
            System.err.println("  -par      Parallel (sequential I/O with producer-consumer)");
            System.err.println("  -par-old  Parallel (random I/O - old approach)");
            System.err.println("  num_threads    (optional, for -par/-par-old) Number of threads to use");
            System.err.println("  chunk_size_kb  (optional, for -par only) Chunk size in KB");
            System.exit(1);
        }

        Strategy strategy = switch (args[0]) {
            case "-std" -> Strategy.STANDARD_DOUBLES;
            case "-dbl" -> Strategy.CUSTOM_DOUBLES;
            case "-flt" -> Strategy.CUSTOM_FLOATS;
            case "-int" -> Strategy.SHORT_INTEGERS;
            case "-par" -> Strategy.PARALLEL;
            case "-par-old" -> Strategy.PARALLEL_RANDOM_IO;
            default -> {
                System.err.println("Invalid flag. Use -std, -dbl, -flt, -int, -par, or -par-old");
                System.exit(1);
                yield null;
            }
        };

        String inputFile = args[1];
        int numThreads = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int chunkSizeKB = args.length > 3 ? Integer.parseInt(args[3]) : -1;  // Chunk size in KB

        System.err.println("Strategy: " + strategy);
        System.err.println("Input file: " + inputFile);
        System.err.println("Starting...\n");

        try {
            long startTime = System.nanoTime();

            Map<String, ?> results = switch (strategy) {
                case STANDARD_DOUBLES -> processStandardDoubles(inputFile);
                case CUSTOM_DOUBLES -> processCustomDoubles(inputFile);
                case CUSTOM_FLOATS -> processCustomFloats(inputFile);
                case SHORT_INTEGERS -> processShortIntegers(inputFile);
                case PARALLEL -> processParallel(inputFile, numThreads, chunkSizeKB);
                case PARALLEL_RANDOM_IO -> processParallelRandomIO(inputFile, numThreads);
            };

            long endTime = System.nanoTime();
            double durationMs = (endTime - startTime) / 1_000_000.0;

            System.err.println("\n--- Results ---");
            System.err.println("Stations found: " + results.size());
            System.err.println("Duration: " + String.format("%.2f ms", durationMs));

            // Output first few results for verification
            outputResults(results, strategy);

        } catch (IOException | InterruptedException | ExecutionException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ============================================================
    // Strategy 1: Standard Double.parseDouble() with BufferedReader
    // ============================================================

    static class DoubleStats {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        long count = 0;

        void update(double temp) {
            if (temp < min) min = temp;
            if (temp > max) max = temp;
            sum += temp;
            count++;
        }

        double getMean() {
            return sum / count;
        }

        @Override
        public String toString() {
            return String.format("%.1f;%.1f;%.1f", min, getMean(), max);
        }
    }

    private static Map<String, DoubleStats> processStandardDoubles(String filename) throws IOException {
        Map<String, DoubleStats> stations = new HashMap<>(16384);

        try (BufferedReader reader = new BufferedReader(
                new FileReader(filename), 64 * 1024)) {

            String line;
            while ((line = reader.readLine()) != null) {
                int semicolonPos = line.indexOf(';');
                if (semicolonPos == -1) continue;

                String station = line.substring(0, semicolonPos);
                double temp = Double.parseDouble(line.substring(semicolonPos + 1));

                stations.computeIfAbsent(station, k -> new DoubleStats()).update(temp);
            }
        }

        return stations;
    }

    // ============================================================
    // Strategy 2: Custom double parsing with memory-mapped I/O
    // ============================================================

    private static Map<String, DoubleStats> processCustomDoubles(String filename) throws IOException {
        Map<String, DoubleStats> stations = new HashMap<>(16384);

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            byte[] stationBytes = new byte[100]; // Max station name length

            while (buffer.hasRemaining()) {
                // Read station name until semicolon
                int stationLen = 0;
                byte b;
                while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                    stationBytes[stationLen++] = b;
                }

                if (!buffer.hasRemaining()) break;

                // Parse temperature (custom, no String allocation)
                double temp = parseTemperatureAsDouble(buffer);

                // Create/lookup station
                String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
                stations.computeIfAbsent(station, k -> new DoubleStats()).update(temp);
            }
        }

        return stations;
    }

    /**
     * Parse temperature directly from buffer as double.
     * Format: [-]dd.d followed by newline
     */
    private static double parseTemperatureAsDouble(MappedByteBuffer buffer) {
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

    // ============================================================
    // Strategy 3: Custom float parsing with memory-mapped I/O
    // ============================================================

    static class FloatStats {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        double sum = 0.0;  // Use double for sum to maintain precision
        long count = 0;

        void update(float temp) {
            if (temp < min) min = temp;
            if (temp > max) max = temp;
            sum += temp;
            count++;
        }

        double getMean() {
            return sum / count;
        }

        @Override
        public String toString() {
            return String.format("%.1f;%.1f;%.1f", min, getMean(), max);
        }
    }

    private static Map<String, FloatStats> processCustomFloats(String filename) throws IOException {
        Map<String, FloatStats> stations = new HashMap<>(16384);

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            byte[] stationBytes = new byte[100];

            while (buffer.hasRemaining()) {
                // Read station name until semicolon
                int stationLen = 0;
                byte b;
                while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                    stationBytes[stationLen++] = b;
                }

                if (!buffer.hasRemaining()) break;

                // Parse temperature (custom, no String allocation)
                float temp = parseTemperatureAsFloat(buffer);

                // Create/lookup station
                String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
                stations.computeIfAbsent(station, k -> new FloatStats()).update(temp);
            }
        }

        return stations;
    }

    /**
     * Parse temperature directly from buffer as float.
     * Format: [-]dd.d followed by newline
     */
    private static float parseTemperatureAsFloat(MappedByteBuffer buffer) {
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

        float result = value + decimal / 10.0f;
        return negative ? -result : result;
    }

    // ============================================================
    // Strategy 4: Short integer (tenths) with memory-mapped I/O
    // ============================================================

    static class ShortStats {
        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        long sum = 0;  // Use long to avoid overflow
        long count = 0;

        void update(short temp) {
            if (temp < min) min = temp;
            if (temp > max) max = temp;
            sum += temp;
            count++;
        }

        double getMean() {
            return (sum / (double) count) / 10.0;
        }

        @Override
        public String toString() {
            return String.format("%.1f;%.1f;%.1f", min / 10.0, getMean(), max / 10.0);
        }
    }

    private static Map<String, ShortStats> processShortIntegers(String filename) throws IOException {
        Map<String, ShortStats> stations = new HashMap<>(16384);

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            byte[] stationBytes = new byte[100];

            while (buffer.hasRemaining()) {
                // Read station name until semicolon
                int stationLen = 0;
                byte b;
                while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                    stationBytes[stationLen++] = b;
                }

                if (!buffer.hasRemaining()) break;

                // Parse temperature as short (tenths)
                short temp = parseTemperatureAsShort(buffer);

                // Create/lookup station
                String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
                stations.computeIfAbsent(station, k -> new ShortStats()).update(temp);
            }
        }

        return stations;
    }

    /**
     * Parse temperature as short integer in tenths.
     * "12.3" -> 123, "-5.7" -> -57
     */
    private static short parseTemperatureAsShort(MappedByteBuffer buffer) {
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

        // Read decimal digit and combine
        b = buffer.get();
        value = value * 10 + (b - '0');

        // Skip newline
        buffer.get();

        return (short) (negative ? -value : value);
    }

    // ============================================================
    // Strategy 5: Parallel processing with producer-consumer pattern
    // ============================================================

    static class WorkChunk {
        final MappedByteBuffer buffer;  // Reference to mapped buffer
        final int start;                // Start position
        final int end;                  // End position (exclusive)
        final boolean isPoison;         // Signal to workers to stop

        WorkChunk(MappedByteBuffer buffer, int start, int end) {
            this.buffer = buffer;
            this.start = start;
            this.end = end;
            this.isPoison = false;
        }

        // Poison pill constructor
        private WorkChunk() {
            this.buffer = null;
            this.start = 0;
            this.end = 0;
            this.isPoison = true;
        }

        static WorkChunk poisonPill() {
            return new WorkChunk();
        }
    }

    private static Map<String, DoubleStats> processParallel(String filename, int requestedThreads, int chunkSizeKB) throws IOException, InterruptedException, ExecutionException {
        int numWorkers = requestedThreads > 0 ? requestedThreads : Runtime.getRuntime().availableProcessors();
        int queueCapacity = numWorkers * 2;  // Optimal
        int chunkSize = chunkSizeKB > 0 ? chunkSizeKB * 1024 : 16 * 1024 * 1024;  // Default 16MB

        System.err.println("Using " + numWorkers + " worker threads");
        System.err.println("Queue capacity: " + queueCapacity);
        System.err.println("Chunk size: " + (chunkSize / 1024) + " KB");

        // Get page size for prefetching
        int pageSize = getPageSize();
        System.err.println("Page size: " + pageSize + " bytes");

        BlockingQueue<WorkChunk> workQueue = new ArrayBlockingQueue<>(queueCapacity);

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();

            // Start reader thread
            Thread readerThread = new Thread(() -> {
                try {
                    readFileSequentially(channel, fileSize, workQueue, pageSize, chunkSize);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            readerThread.start();

            // Start worker threads
            ExecutorService workerPool = Executors.newFixedThreadPool(numWorkers);
            List<Future<Map<String, DoubleStats>>> futures = new ArrayList<>();

            for (int i = 0; i < numWorkers; i++) {
                futures.add(workerPool.submit(() -> processWorkQueue(workQueue)));
            }

            // Wait for reader to finish
            readerThread.join();

            // Send poison pills to workers
            for (int i = 0; i < numWorkers; i++) {
                workQueue.put(WorkChunk.poisonPill());
            }

            // Merge results from all workers
            Map<String, DoubleStats> merged = new HashMap<>(16384);
            for (Future<Map<String, DoubleStats>> future : futures) {
                Map<String, DoubleStats> workerResult = future.get();

                for (Map.Entry<String, DoubleStats> entry : workerResult.entrySet()) {
                    merged.merge(entry.getKey(), entry.getValue(), (existing, newStats) -> {
                        existing.min = Math.min(existing.min, newStats.min);
                        existing.max = Math.max(existing.max, newStats.max);
                        existing.sum += newStats.sum;
                        existing.count += newStats.count;
                        return existing;
                    });
                }
            }

            workerPool.shutdown();
            return merged;
        }
    }

    /**
     * Get system page size.
     */
    private static int getPageSize() {
        try {
            ProcessBuilder pb = new ProcessBuilder("getconf", "PAGESIZE");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            return Integer.parseInt(line.trim());
        } catch (Exception e) {
            // Default to common page size
            return 4096;
        }
    }

    /**
     * Reader thread: reads file sequentially, creates work chunks.
     */
    private static void readFileSequentially(FileChannel channel, long fileSize,
                                            BlockingQueue<WorkChunk> workQueue,
                                            int pageSize, int chunkSize) throws IOException, InterruptedException {
        final int PREFETCH_PAGES = 8;  // Prefetch 8 pages ahead
        final int PREFETCH_TRIGGER = 4096;  // Trigger prefetch every 4KB

        long offset = 0;
        long bytesRead = 0;

        // Handle files > 2GB by mapping in segments
        while (offset < fileSize) {
            long remainingInFile = fileSize - offset;
            long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);

            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize);

            while (buffer.hasRemaining()) {
                int chunkStart = buffer.position();
                int available = Math.min(chunkSize, buffer.remaining());

                // Find next newline boundary (scan forward to find complete lines)
                int chunkEnd = chunkStart + available;
                if (chunkEnd < buffer.limit()) {
                    // Scan forward to find next newline
                    buffer.position(chunkEnd);
                    while (buffer.hasRemaining() && buffer.get() != '\n') {
                        chunkEnd++;
                    }
                    chunkEnd++;  // Include the newline
                } else {
                    // Last chunk - goes to end of buffer
                    chunkEnd = buffer.limit();
                }

                int chunkLength = chunkEnd - chunkStart;

                // Prefetch upcoming pages
                bytesRead += chunkLength;
                if (bytesRead % PREFETCH_TRIGGER == 0) {
                    prefetchPages(buffer, PREFETCH_PAGES, pageSize);
                }

                // Put chunk on queue (blocks if queue is full - backpressure!)
                // Pass buffer reference, not a copy!
                workQueue.put(new WorkChunk(buffer, chunkStart, chunkEnd));

                // Move to next chunk
                buffer.position(chunkEnd);
            }

            offset += segmentSize;
        }
    }

    /**
     * Touch bytes ahead to trigger page faults and prefetch.
     */
    private static void prefetchPages(MappedByteBuffer buffer, int numPages, int pageSize) {
        try {
            int currentPos = buffer.position();
            int prefetchDistance = numPages * pageSize;
            int prefetchPos = Math.min(currentPos + prefetchDistance, buffer.limit() - 1);

            if (prefetchPos > currentPos) {
                // Touch a byte to force page fault
                buffer.get(prefetchPos);
                // Restore position
                buffer.position(currentPos);
            }
        } catch (Exception e) {
            // Ignore prefetch errors
        }
    }

    /**
     * Worker thread: processes chunks from queue.
     */
    private static Map<String, DoubleStats> processWorkQueue(BlockingQueue<WorkChunk> workQueue) throws InterruptedException {
        Map<String, DoubleStats> localStats = new HashMap<>(4096);
        byte[] stationBytes = new byte[100];

        while (true) {
            WorkChunk chunk = workQueue.take();

            if (chunk.isPoison) {
                break;  // Done processing
            }

            // Create a duplicate buffer for this worker (independent position)
            MappedByteBuffer buffer = chunk.buffer.duplicate();
            buffer.position(chunk.start);
            buffer.limit(chunk.end);

            // Process this chunk
            while (buffer.hasRemaining()) {
                // Read station name until semicolon
                int stationLen = 0;
                byte b;
                while (buffer.hasRemaining() && (b = buffer.get()) != ';') {
                    stationBytes[stationLen++] = b;
                }

                if (!buffer.hasRemaining()) break;

                // Parse temperature directly from buffer
                double temp = parseTemperatureFromBuffer(buffer);

                // Update stats
                String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
                localStats.computeIfAbsent(station, k -> new DoubleStats()).update(temp);
            }
        }

        return localStats;
    }

    /**
     * Parse temperature from buffer.
     * Format: [-]dd.d followed by newline
     */
    private static double parseTemperatureFromBuffer(MappedByteBuffer buffer) {
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

    // ============================================================
    // Strategy 6: Parallel processing with random I/O (old approach)
    // ============================================================

    /**
     * Old parallel approach: divide file into chunks upfront, all threads start reading simultaneously.
     * This causes random I/O patterns.
     */
    private static Map<String, DoubleStats> processParallelRandomIO(String filename, int requestedThreads)
            throws IOException, InterruptedException, ExecutionException {
        int numThreads = requestedThreads > 0 ? requestedThreads : Runtime.getRuntime().availableProcessors();
        System.err.println("Using " + numThreads + " threads (random I/O)");

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();

            // Map entire file (or first 2GB if larger)
            long mapSize = Math.min(fileSize, Integer.MAX_VALUE);
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);

            // Calculate chunk boundaries upfront
            long[] chunkStarts = new long[numThreads];
            long[] chunkEnds = new long[numThreads];

            long chunkSize = mapSize / numThreads;

            for (int i = 0; i < numThreads; i++) {
                chunkStarts[i] = i * chunkSize;

                if (i == numThreads - 1) {
                    chunkEnds[i] = mapSize;
                } else {
                    long searchPos = (i + 1) * chunkSize;
                    chunkEnds[i] = findNextNewlineOld(buffer, searchPos);
                }
            }

            for (int i = 1; i < numThreads; i++) {
                chunkStarts[i] = chunkEnds[i - 1];
            }

            // All threads start processing simultaneously (random I/O!)
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            List<Future<Map<String, DoubleStats>>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                long start = chunkStarts[i];
                long end = chunkEnds[i];

                futures.add(executor.submit(() -> processChunkOld(buffer, start, end)));
            }

            // Merge results
            Map<String, DoubleStats> merged = new HashMap<>(16384);
            for (Future<Map<String, DoubleStats>> future : futures) {
                Map<String, DoubleStats> chunkResult = future.get();

                for (Map.Entry<String, DoubleStats> entry : chunkResult.entrySet()) {
                    merged.merge(entry.getKey(), entry.getValue(), (existing, newStats) -> {
                        existing.min = Math.min(existing.min, newStats.min);
                        existing.max = Math.max(existing.max, newStats.max);
                        existing.sum += newStats.sum;
                        existing.count += newStats.count;
                        return existing;
                    });
                }
            }

            executor.shutdown();
            return merged;
        }
    }

    private static long findNextNewlineOld(MappedByteBuffer buffer, long startPos) {
        long pos = startPos;
        buffer.position((int) pos);

        while (buffer.hasRemaining()) {
            if (buffer.get() == '\n') {
                return pos + 1;
            }
            pos++;
        }
        return pos;
    }

    private static Map<String, DoubleStats> processChunkOld(MappedByteBuffer buffer, long start, long end) {
        Map<String, DoubleStats> stations = new HashMap<>(4096);

        MappedByteBuffer chunkBuffer = buffer.duplicate();
        chunkBuffer.position((int) start);
        chunkBuffer.limit((int) end);

        byte[] stationBytes = new byte[100];

        while (chunkBuffer.hasRemaining()) {
            int stationLen = 0;
            byte b;
            while (chunkBuffer.hasRemaining() && (b = chunkBuffer.get()) != ';') {
                stationBytes[stationLen++] = b;
            }

            if (!chunkBuffer.hasRemaining()) break;

            double temp = parseTemperatureFromBuffer(chunkBuffer);

            String station = new String(stationBytes, 0, stationLen, StandardCharsets.UTF_8);
            stations.computeIfAbsent(station, k -> new DoubleStats()).update(temp);
        }

        return stations;
    }

    // ============================================================
    // Output helper
    // ============================================================

    private static void outputResults(Map<String, ?> results, Strategy strategy) {
        System.err.println("\nFirst 5 stations (alphabetically):");

        results.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(5)
                .forEach(entry -> {
                    System.err.println("  " + entry.getKey() + ";" + entry.getValue());
                });
    }
}
