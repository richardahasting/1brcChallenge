import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * 1 Billion Row Challenge - TRUE Baseline I/O Test
 *
 * This version uses buffer.duplicate() for fast sequential reads.
 * This measures the TRUE theoretical maximum without per-byte overhead.
 *
 * Usage:
 *   javac OneBRCBaselineFast.java
 *   java OneBRCBaselineFast <input_file> [num_threads] [chunk_size_mb]
 */
public class OneBRCBaselineFast {

    private static final int DEFAULT_NUM_THREADS = 11;
    private static final int DEFAULT_CHUNK_SIZE_MB = 2;  // 2MB like our ultimate version

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

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java OneBRCBaselineFast <input_file> [num_threads] [chunk_size_mb]");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_NUM_THREADS;
        int chunkSizeMB = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CHUNK_SIZE_MB;

        System.err.println("=== 1 Billion Row Challenge - TRUE Baseline I/O Test ===");
        System.err.println("Input file: " + inputFile);
        System.err.println("Worker threads: " + numThreads);
        System.err.println("Chunk size: " + chunkSizeMB + " MB");
        System.err.println();
        System.err.println("NOTE: Using buffer.duplicate() for FAST sequential reads");
        System.err.println("      This measures the TRUE I/O ceiling");
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
            System.err.println("=== TRUE Baseline Results ===");
            System.err.println("Bytes read: " + String.format("%,d", bytesRead) +
                             " (" + String.format("%.2f GB", bytesRead / (1024.0 * 1024.0 * 1024.0)) + ")");
            System.err.println("Duration: " + String.format("%.2f seconds", durationSeconds));
            System.err.println("CPU time: " + String.format("%.2f seconds", cpuTimeSeconds));
            System.err.println("CPU utilization: " + String.format("%.1f%%", cpuUtilization) +
                             " (of " + numCores + " cores = " + String.format("%.1f%%", cpuUtilizationPerCore) + " per core)");
            System.err.println("Throughput: " + String.format("%.2f MB/s", throughputMBps));
            System.err.println();
            System.err.println("This is the TRUE THEORETICAL MAXIMUM for this system.");
            System.err.println("Any processing will add overhead beyond this.");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static long processFile(String filename, int numThreads, int chunkSizeMB)
            throws IOException, InterruptedException, ExecutionException {

        int chunkSize = chunkSizeMB * 1024 * 1024;

        try (RandomAccessFile file = new RandomAccessFile(filename, "r");
             FileChannel channel = file.getChannel()) {

            long fileSize = channel.size();
            System.err.println("File size: " + String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0)));

            // Create chunks
            List<WorkChunk> chunks = createChunks(channel, fileSize, chunkSize);
            System.err.println("Created " + chunks.size() + " chunks");
            System.err.println();

            // Process all chunks in parallel
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            List<Callable<Long>> tasks = new ArrayList<>();
            for (WorkChunk chunk : chunks) {
                tasks.add(() -> readChunk(chunk));
            }

            // Execute all tasks
            List<Future<Long>> futures = executor.invokeAll(tasks);

            // Sum bytes read
            long totalBytesRead = 0;
            for (Future<Long> future : futures) {
                totalBytesRead += future.get();
            }

            executor.shutdown();
            return totalBytesRead;
        }
    }

    private static List<WorkChunk> createChunks(FileChannel channel, long fileSize, int chunkSize)
            throws IOException {

        List<WorkChunk> chunks = new ArrayList<>();
        long offset = 0;

        while (offset < fileSize) {
            long remainingInFile = fileSize - offset;
            long segmentSize = Math.min(Integer.MAX_VALUE, remainingInFile);

            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize);

            int position = 0;
            while (position < buffer.limit()) {
                int chunkStart = position;
                int chunkEnd = Math.min(chunkStart + chunkSize, buffer.limit());

                chunks.add(new WorkChunk(buffer, chunkStart, chunkEnd));
                position = chunkEnd;
            }

            offset += segmentSize;
        }

        return chunks;
    }

    /**
     * Read a chunk using buffer.duplicate() for FAST sequential access.
     * No per-byte division/modulo overhead!
     */
    private static long readChunk(WorkChunk chunk) {
        // Use buffer.duplicate() for fast sequential reads
        MappedByteBuffer buffer = chunk.buffer.duplicate();
        buffer.position(chunk.start);
        buffer.limit(chunk.end);

        long bytesRead = 0;
        byte checksum = 0;  // XOR accumulator (prevents optimization)

        // Fast sequential read - just buffer.get()!
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            checksum ^= b;
            bytesRead++;
        }

        // Use checksum to prevent dead code elimination
        if (checksum == (byte)0xFF) {
            System.err.println("Unlikely checksum");
        }

        return bytesRead;
    }
}
