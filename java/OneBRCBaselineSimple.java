import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;

public class OneBRCBaselineSimple {
    static class WorkChunk {
        final MappedByteBuffer buffer;
        final int start, end;
        WorkChunk(MappedByteBuffer b, int s, int e) { buffer=b; start=s; end=e; }
    }
    
    public static void main(String[] args) throws Exception {
        String file = args[0];
        int threads = 11;
        int chunkKB = 2048;
        
        long startTime = System.nanoTime();
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            
            List<WorkChunk> chunks = new ArrayList<>();
            long offset = 0;
            long fileSize = channel.size();
            int chunkSize = chunkKB * 1024;
            
            // Create chunks
            while (offset < fileSize) {
                long segmentSize = Math.min(Integer.MAX_VALUE, fileSize - offset);
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset, segmentSize);
                
                int pos = 0;
                while (pos < buffer.limit()) {
                    int end = Math.min(pos + chunkSize, buffer.limit());
                    chunks.add(new WorkChunk(buffer, pos, end));
                    pos = end;
                }
                offset += segmentSize;
            }
            
            System.out.println("Created " + chunks.size() + " chunks");
            
            // Process in parallel
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            List<Callable<Long>> tasks = new ArrayList<>();
            
            for (WorkChunk c : chunks) {
                tasks.add(() -> {
                    MappedByteBuffer buf = c.buffer.duplicate();
                    buf.position(c.start);
                    buf.limit(c.end);
                    long count = 0;
                    byte checksum = 0;
                    while (buf.hasRemaining()) {
                        checksum ^= buf.get();
                        count++;
                    }
                    return count;
                });
            }
            
            long total = 0;
            for (Future<Long> f : exec.invokeAll(tasks)) {
                total += f.get();
            }
            exec.shutdown();
            
            long endTime = System.nanoTime();
            double seconds = (endTime - startTime) / 1_000_000_000.0;
            double mb = total / (1024.0 * 1024.0);
            
            System.out.println("Bytes: " + total);
            System.out.println("Time: " + String.format("%.2f", seconds) + "s");
            System.out.println("Throughput: " + String.format("%.2f", mb/seconds) + " MB/s");
        }
    }
}
