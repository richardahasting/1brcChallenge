/**
 * 1 Billion Row Challenge - C Optimized Implementation
 *
 * Optimizations applied:
 * - Memory-mapped I/O (mmap) for zero-copy file access
 * - Multi-threading with pthreads
 * - Producer-consumer pattern for sequential I/O
 * - Lock-free per-thread hash tables (merged at end)
 * - Custom temperature parsing
 *
 * Usage:
 *   gcc -O3 -pthread -o onebrc_optimized onebrc_optimized.c
 *   ./onebrc_optimized <input_file> [num_threads] [chunk_size_kb]
 *
 * Author: Richard Hastings with Claude Code
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <float.h>

// Configuration
#define MAX_STATION_NAME 100
#define HASH_TABLE_SIZE 16384
#define DEFAULT_NUM_THREADS 7
#define DEFAULT_CHUNK_SIZE_KB 288
#define MAX_QUEUE_SIZE 14  // 2 × threads

// Station statistics
typedef struct Station {
    char name[MAX_STATION_NAME + 1];
    double min;
    double max;
    double sum;
    long count;
    struct Station *next;
} Station;

// Per-thread hash table
typedef struct {
    Station *buckets[HASH_TABLE_SIZE];
    int station_count;
} HashTable;

// Work chunk for producer-consumer
typedef struct {
    char *start;
    char *end;
    int is_poison;
} WorkChunk;

// Work queue
typedef struct {
    WorkChunk *chunks;
    int capacity;
    int size;
    int read_idx;
    int write_idx;
    pthread_mutex_t mutex;
    pthread_cond_t not_empty;
    pthread_cond_t not_full;
} WorkQueue;

// Worker thread context
typedef struct {
    int thread_id;
    WorkQueue *queue;
    HashTable *local_table;
} WorkerContext;

// Global state
typedef struct {
    char *file_data;
    size_t file_size;
    int num_threads;
    int chunk_size;
} GlobalState;

/**
 * Hash function (djb2)
 */
static inline unsigned long hash_string(const char *str, int len) {
    unsigned long hash = 5381;
    for (int i = 0; i < len; i++) {
        hash = ((hash << 5) + hash) + str[i];
    }
    return hash;
}

/**
 * Initialize hash table
 */
void init_hash_table(HashTable *table) {
    memset(table->buckets, 0, sizeof(table->buckets));
    table->station_count = 0;
}

/**
 * Find or create station (thread-local, no locking needed)
 */
Station* get_or_create_station(HashTable *table, const char *name, int name_len) {
    unsigned long hash = hash_string(name, name_len);
    int index = hash % HASH_TABLE_SIZE;

    // Search in chain
    Station *current = table->buckets[index];
    while (current != NULL) {
        if (strncmp(current->name, name, name_len) == 0 && current->name[name_len] == '\0') {
            return current;
        }
        current = current->next;
    }

    // Not found - create new
    Station *new_station = (Station*)malloc(sizeof(Station));
    memcpy(new_station->name, name, name_len);
    new_station->name[name_len] = '\0';
    new_station->min = DBL_MAX;
    new_station->max = -DBL_MAX;
    new_station->sum = 0.0;
    new_station->count = 0;
    new_station->next = table->buckets[index];

    table->buckets[index] = new_station;
    table->station_count++;

    return new_station;
}

/**
 * Parse temperature from buffer
 * Format: [-]dd.d
 */
static inline double parse_temperature(const char **ptr) {
    const char *p = *ptr;
    int negative = 0;
    double value = 0.0;

    if (*p == '-') {
        negative = 1;
        p++;
    }

    // Integer part
    while (*p >= '0' && *p <= '9') {
        value = value * 10.0 + (*p - '0');
        p++;
    }

    // Decimal point
    if (*p == '.') {
        p++;
    }

    // Decimal digit
    if (*p >= '0' && *p <= '9') {
        value += (*p - '0') / 10.0;
        p++;
    }

    // Skip newline
    if (*p == '\n') {
        p++;
    }

    *ptr = p;
    return negative ? -value : value;
}

/**
 * Process a chunk of data
 */
void process_chunk(HashTable *table, char *start, char *end) {
    char *p = start;

    while (p < end) {
        // Find semicolon
        char *semicolon = p;
        while (semicolon < end && *semicolon != ';') {
            semicolon++;
        }

        if (semicolon >= end) break;

        // Extract station name
        int name_len = semicolon - p;

        // Parse temperature
        const char *temp_ptr = semicolon + 1;
        double temperature = parse_temperature(&temp_ptr);

        // Update statistics
        Station *station = get_or_create_station(table, p, name_len);
        if (temperature < station->min) station->min = temperature;
        if (temperature > station->max) station->max = temperature;
        station->sum += temperature;
        station->count++;

        p = (char*)temp_ptr;
    }
}

/**
 * Initialize work queue
 */
void init_queue(WorkQueue *queue, int capacity) {
    queue->chunks = (WorkChunk*)malloc(capacity * sizeof(WorkChunk));
    queue->capacity = capacity;
    queue->size = 0;
    queue->read_idx = 0;
    queue->write_idx = 0;
    pthread_mutex_init(&queue->mutex, NULL);
    pthread_cond_init(&queue->not_empty, NULL);
    pthread_cond_init(&queue->not_full, NULL);
}

/**
 * Enqueue work chunk (blocking if full)
 */
void enqueue(WorkQueue *queue, WorkChunk chunk) {
    pthread_mutex_lock(&queue->mutex);

    while (queue->size == queue->capacity) {
        pthread_cond_wait(&queue->not_full, &queue->mutex);
    }

    queue->chunks[queue->write_idx] = chunk;
    queue->write_idx = (queue->write_idx + 1) % queue->capacity;
    queue->size++;

    pthread_cond_signal(&queue->not_empty);
    pthread_mutex_unlock(&queue->mutex);
}

/**
 * Dequeue work chunk (blocking if empty)
 */
WorkChunk dequeue(WorkQueue *queue) {
    pthread_mutex_lock(&queue->mutex);

    while (queue->size == 0) {
        pthread_cond_wait(&queue->not_empty, &queue->mutex);
    }

    WorkChunk chunk = queue->chunks[queue->read_idx];
    queue->read_idx = (queue->read_idx + 1) % queue->capacity;
    queue->size--;

    pthread_cond_signal(&queue->not_full);
    pthread_mutex_unlock(&queue->mutex);

    return chunk;
}

/**
 * Worker thread function
 */
void* worker_thread(void *arg) {
    WorkerContext *ctx = (WorkerContext*)arg;

    while (1) {
        WorkChunk chunk = dequeue(ctx->queue);

        if (chunk.is_poison) {
            break;
        }

        process_chunk(ctx->local_table, chunk.start, chunk.end);
    }

    return NULL;
}

/**
 * Reader thread - creates work chunks sequentially
 */
void reader_thread(GlobalState *state, WorkQueue *queue) {
    char *p = state->file_data;
    char *end = state->file_data + state->file_size;

    while (p < end) {
        char *chunk_start = p;
        char *chunk_end = p + state->chunk_size;

        if (chunk_end >= end) {
            chunk_end = end;
        } else {
            // Find next newline
            while (chunk_end < end && *chunk_end != '\n') {
                chunk_end++;
            }
            if (chunk_end < end) {
                chunk_end++;  // Include newline
            }
        }

        WorkChunk chunk = {chunk_start, chunk_end, 0};
        enqueue(queue, chunk);

        p = chunk_end;
    }
}

/**
 * Merge two stations
 */
void merge_station(Station *dest, Station *src) {
    if (src->min < dest->min) dest->min = src->min;
    if (src->max > dest->max) dest->max = src->max;
    dest->sum += src->sum;
    dest->count += src->count;
}

/**
 * Merge worker results into global table
 */
void merge_tables(HashTable *global, HashTable *local) {
    for (int i = 0; i < HASH_TABLE_SIZE; i++) {
        Station *current = local->buckets[i];
        while (current != NULL) {
            Station *global_station = get_or_create_station(global, current->name, strlen(current->name));
            merge_station(global_station, current);
            current = current->next;
        }
    }
}

/**
 * Comparison for qsort
 */
int compare_stations(const void *a, const void *b) {
    Station *s1 = *(Station**)a;
    Station *s2 = *(Station**)b;
    return strcmp(s1->name, s2->name);
}

/**
 * Print results
 */
void print_results(HashTable *table) {
    // Collect stations
    Station **stations = (Station**)malloc(table->station_count * sizeof(Station*));
    int idx = 0;
    for (int i = 0; i < HASH_TABLE_SIZE; i++) {
        Station *current = table->buckets[i];
        while (current != NULL) {
            stations[idx++] = current;
            current = current->next;
        }
    }

    // Sort
    qsort(stations, table->station_count, sizeof(Station*), compare_stations);

    // Print first 5
    fprintf(stderr, "\nFirst 5 stations (alphabetically):\n");
    int limit = table->station_count < 5 ? table->station_count : 5;
    for (int i = 0; i < limit; i++) {
        Station *s = stations[i];
        double mean = s->sum / s->count;
        fprintf(stderr, "  %s;%.1f;%.1f;%.1f\n", s->name, s->min, mean, s->max);
    }

    free(stations);
}

/**
 * Free hash table
 */
void free_hash_table(HashTable *table) {
    for (int i = 0; i < HASH_TABLE_SIZE; i++) {
        Station *current = table->buckets[i];
        while (current != NULL) {
            Station *next = current->next;
            free(current);
            current = next;
        }
    }
}

/**
 * Main
 */
int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <input_file> [num_threads] [chunk_size_kb]\n", argv[0]);
        fprintf(stderr, "\nArguments:\n");
        fprintf(stderr, "  input_file      Path to measurements file\n");
        fprintf(stderr, "  num_threads     (optional) Number of worker threads (default: 7)\n");
        fprintf(stderr, "  chunk_size_kb   (optional) Chunk size in KB (default: 288)\n");
        return 1;
    }

    const char *filename = argv[1];
    int num_threads = argc > 2 ? atoi(argv[2]) : DEFAULT_NUM_THREADS;
    int chunk_size_kb = argc > 3 ? atoi(argv[3]) : DEFAULT_CHUNK_SIZE_KB;
    int chunk_size = chunk_size_kb * 1024;

    fprintf(stderr, "=== 1 Billion Row Challenge - C Optimized ===\n");
    fprintf(stderr, "Input file: %s\n", filename);
    fprintf(stderr, "Worker threads: %d\n", num_threads);
    fprintf(stderr, "Chunk size: %d KB\n", chunk_size_kb);
    fprintf(stderr, "Queue capacity: %d\n\n", MAX_QUEUE_SIZE);

    // Open and map file
    int fd = open(filename, O_RDONLY);
    if (fd == -1) {
        fprintf(stderr, "Error: Cannot open file '%s': %s\n", filename, strerror(errno));
        return 1;
    }

    struct stat sb;
    if (fstat(fd, &sb) == -1) {
        fprintf(stderr, "Error: Cannot stat file: %s\n", strerror(errno));
        close(fd);
        return 1;
    }

    size_t file_size = sb.st_size;
    fprintf(stderr, "File size: %.2f GB\n", file_size / (1024.0 * 1024.0 * 1024.0));

    char *file_data = mmap(NULL, file_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (file_data == MAP_FAILED) {
        fprintf(stderr, "Error: Cannot mmap file: %s\n", strerror(errno));
        close(fd);
        return 1;
    }

    // Advise sequential access
    madvise(file_data, file_size, MADV_SEQUENTIAL);

    // Initialize global state
    GlobalState state = {file_data, file_size, num_threads, chunk_size};

    // Initialize work queue
    WorkQueue queue;
    init_queue(&queue, MAX_QUEUE_SIZE);

    // Create worker threads
    pthread_t *threads = (pthread_t*)malloc(num_threads * sizeof(pthread_t));
    WorkerContext *contexts = (WorkerContext*)malloc(num_threads * sizeof(WorkerContext));
    HashTable *worker_tables = (HashTable*)malloc(num_threads * sizeof(HashTable));

    for (int i = 0; i < num_threads; i++) {
        init_hash_table(&worker_tables[i]);
        contexts[i].thread_id = i;
        contexts[i].queue = &queue;
        contexts[i].local_table = &worker_tables[i];
        pthread_create(&threads[i], NULL, worker_thread, &contexts[i]);
    }

    // Start timing
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    // Reader thread (main thread)
    reader_thread(&state, &queue);

    // Send poison pills
    for (int i = 0; i < num_threads; i++) {
        WorkChunk poison = {NULL, NULL, 1};
        enqueue(&queue, poison);
    }

    // Wait for workers
    for (int i = 0; i < num_threads; i++) {
        pthread_join(threads[i], NULL);
    }

    // Merge results
    HashTable global_table;
    init_hash_table(&global_table);
    for (int i = 0; i < num_threads; i++) {
        merge_tables(&global_table, &worker_tables[i]);
    }

    clock_gettime(CLOCK_MONOTONIC, &end);

    // Calculate duration
    double duration = (end.tv_sec - start.tv_sec) + (end.tv_nsec - start.tv_nsec) / 1e9;
    double throughput_mb = (file_size / (1024.0 * 1024.0)) / duration;

    // Print results
    fprintf(stderr, "\n=== Results ===\n");
    fprintf(stderr, "Stations found: %d\n", global_table.station_count);
    fprintf(stderr, "Duration: %.2f seconds\n", duration);
    fprintf(stderr, "Throughput: %.2f MB/s\n", throughput_mb);

    print_results(&global_table);

    // Cleanup
    for (int i = 0; i < num_threads; i++) {
        free_hash_table(&worker_tables[i]);
    }
    free_hash_table(&global_table);
    free(threads);
    free(contexts);
    free(worker_tables);
    munmap(file_data, file_size);
    close(fd);

    return 0;
}
