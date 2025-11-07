import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * 1 Billion Row Challenge - Baseline I/O Test
 *
 * This version reads every byte but does NO processing:
 * - No parsing
 * - No string creation
 * - No hash table operations
 * - No statistics
 *
 * This measures the theoretical maximum throughput limited only by:
 * - Memory mapping overhead
 * - Memory bandwidth
 * - Cache effects
 *
 * Purpose: Determine what % of our time is spent on I/O vs processing
 *
 * Usage:
 *   javac OneBRCBaseline.java
 *   java OneBRCBaseline <input_file> [num_threads] [chunk_size_mb]
 */
public class OneBRCBaseline {

    private static final int DEFAULT_NUM_THREADS = 11;
    private static final int DEFAULT_CHUNK_SIZE_MB = 256;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java OneBRCBaseline <input_file> [num_threads] [chunk_size_mb]");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_THREADS;
        int chunkSizeMB = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE_MB;

        System.err.println("=== 1 Billion Row Challenge - Baseline I/O Test ===");
        System.err.println("Input file: " + inputFile);
        System.err.println("Worker threads: " + numThreads);
        System.err.println("Chunk size: " + chunkSizeMB + " MB");
        System.err.println();
        System.err.println("NOTE: This test reads every byte but does NO processing");
        System.err.println("      It measures pure I/O + memory bandwidth throughput");
        System.err.println();

        try {
            // Get CPU time tracking
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long cpuTimeStart = osBean.getProcessCpuTime();
            long startTime = System.nanoTime();

            long bytesRead = processFile(inputFile, numThreads, chunkSizeMB);

            long endTime = System.nanoTime();
            long cpuTimeEnd = osBean.getProcessCpuTime();

            double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
            double cpuTimeSeconds = (cpuTimeEnd - cpuTimeStart) / 1_000_000_000.0;

            // Calculate CPU utilization
            int numCores = Runtime.getRuntime().availableProcessors();
            double cpuUtilization = (cpuTimeSeconds / durationSeconds) * 100.0;
            double cpuUtilizationPerCore = cpuUtilization / numCores;

            double fileSizeMB = bytesRead / (1024.0 * 1024.0);
            double throughputMBps = fileSizeMB / durationSeconds;

            System.err.println();
            System.err.println("=== Baseline Results ===");
            System.err.println("Bytes read: " + String.format("%,d", bytesRead) +
                             " (" + String.format("%.2f GB", bytesRead / (1024.0 * 1024.0 * 1024.0)) + ")");
            System.err.println("Duration: " + String.format("%.2f seconds", durationSeconds));
            System.err.println("CPU time: " + String.format("%.2f seconds", cpuTimeSeconds));
            System.err.println("CPU utilization: " + String.format("%.1f%%", cpuUtilization) +
                             " (of " + numCores + " cores = " + String.format("%.1f%%", cpuUtilizationPerCore) + " per core)");
            System.err.println("Throughput: " + String.format("%.2f MB/s", throughputMBps));
            System.err.println();
            System.err.println("This represents the THEORETICAL MAXIMUM for this system.");
            System.err.println("Any actual processing will be slower than this.");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Process file by reading every byte but doing nothing with it.
     */
    private static long processFile(String filename, int numThreads, int chunkSizeMB)
            throws IOException, InterruptedException, ExecutionException {

        long chunkSize = chunkSizeMB * 1024L * 1024L;

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            System.err.println("File size: " + String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0)));

            // Map entire file
            List<MappedByteBuffer> buffers = new ArrayList<>();
            long offset = 0;
            while (offset < fileSize) {
                long remainingInFile = fileSize - offset;
                long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);
                buffers.add(channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize));
                offset += segmentSize;
            }

            // Calculate chunks
            long numChunks = (fileSize + chunkSize - 1) / chunkSize;
            System.err.println("Calculated " + numChunks + " chunks");
            System.err.println();

            // Process all chunks in parallel
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < numChunks; i++) {
                final long chunkStart = i * chunkSize;
                final long chunkEnd = Math.min(chunkStart + chunkSize, fileSize);

                tasks.add(() -> readChunk(buffers, chunkStart, chunkEnd));
            }

            // Execute all tasks
            List<Future<Long>> futures = executor.invokeAll(tasks);

            // Sum bytes read (should equal fileSize)
            long totalBytesRead = 0;
            for (Future<Long> future : futures) {
                totalBytesRead += future.get();
            }

            executor.shutdown();
            return totalBytesRead;
        }
    }

    /**
     * Read a chunk: iterate through every byte but do nothing with it.
     *
     * To prevent compiler optimization from eliminating the read,
     * we XOR all bytes together and return the result (forcing actual reads).
     */
    private static long readChunk(List<MappedByteBuffer> buffers, long chunkStart, long chunkEnd) {
        long bytesRead = 0;
        byte checksum = 0;  // XOR accumulator (prevents optimization)

        for (long position = chunkStart; position < chunkEnd; position++) {
            byte b = getByteAt(buffers, position);
            checksum ^= b;  // Force the read to actually happen
            bytesRead++;
        }

        // Use checksum in a way that prevents dead code elimination
        if (checksum == (byte)0xFF) {
            System.err.println("Unlikely checksum");  // Never happens, but prevents optimization
        }

        return bytesRead;
    }

    /**
     * Read byte at absolute file position.
     */
    private static byte getByteAt(List<MappedByteBuffer> buffers, long position) {
        int bufferIndex = (int)(position / Integer.MAX_VALUE);
        int positionInBuffer = (int)(position % Integer.MAX_VALUE);
        return buffers.get(bufferIndex).get(positionInBuffer);
    }
}
