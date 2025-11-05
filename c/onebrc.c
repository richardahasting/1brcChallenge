/**
 * 1 Billion Row Challenge - C Baseline Implementation
 *
 * Simple, correct implementation focusing on clarity over performance.
 * This serves as the baseline for optimization experiments.
 *
 * Features:
 * - Standard FILE* I/O with fgets
 * - Simple hash table with chaining
 * - Custom temperature parsing (no atof)
 * - Single-threaded processing
 *
 * Usage:
 *   gcc -O3 -o onebrc onebrc.c
 *   ./onebrc <input_file>
 *
 * Author: Richard Hastings with Claude Code
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <limits.h>
#include <float.h>

// Configuration
#define MAX_STATION_NAME 100
#define HASH_TABLE_SIZE 16384  // Power of 2 for fast modulo
#define LINE_BUFFER_SIZE 256

// Station statistics
typedef struct Station {
    char name[MAX_STATION_NAME + 1];
    double min;
    double max;
    double sum;
    long count;
    struct Station *next;  // For chaining
} Station;

// Hash table
typedef struct {
    Station *buckets[HASH_TABLE_SIZE];
    int station_count;
} HashTable;

/**
 * Simple hash function (djb2 algorithm)
 */
unsigned long hash_string(const char *str) {
    unsigned long hash = 5381;
    int c;
    while ((c = *str++)) {
        hash = ((hash << 5) + hash) + c; // hash * 33 + c
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
 * Find or create a station in the hash table
 */
Station* get_or_create_station(HashTable *table, const char *name) {
    unsigned long hash = hash_string(name);
    int index = hash % HASH_TABLE_SIZE;

    // Search in chain
    Station *current = table->buckets[index];
    while (current != NULL) {
        if (strcmp(current->name, name) == 0) {
            return current;
        }
        current = current->next;
    }

    // Not found - create new station
    Station *new_station = (Station*)malloc(sizeof(Station));
    if (new_station == NULL) {
        fprintf(stderr, "Error: Out of memory\n");
        exit(1);
    }

    strncpy(new_station->name, name, MAX_STATION_NAME);
    new_station->name[MAX_STATION_NAME] = '\0';
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
 * Update station statistics
 */
void update_station(Station *station, double temperature) {
    if (temperature < station->min) station->min = temperature;
    if (temperature > station->max) station->max = temperature;
    station->sum += temperature;
    station->count++;
}

/**
 * Parse temperature from string (custom parser, faster than atof)
 * Format: [-]dd.d
 */
double parse_temperature(const char *str) {
    int negative = 0;
    double value = 0.0;

    // Check for negative sign
    if (*str == '-') {
        negative = 1;
        str++;
    }

    // Parse integer part
    while (*str >= '0' && *str <= '9') {
        value = value * 10.0 + (*str - '0');
        str++;
    }

    // Skip decimal point
    if (*str == '.') {
        str++;
    }

    // Parse decimal part (one digit)
    if (*str >= '0' && *str <= '9') {
        value += (*str - '0') / 10.0;
    }

    return negative ? -value : value;
}

/**
 * Process a single line
 */
void process_line(HashTable *table, char *line) {
    // Find semicolon separator
    char *semicolon = strchr(line, ';');
    if (semicolon == NULL) {
        return;  // Invalid line
    }

    // Extract station name
    *semicolon = '\0';
    char *station_name = line;

    // Parse temperature
    char *temp_str = semicolon + 1;
    double temperature = parse_temperature(temp_str);

    // Update statistics
    Station *station = get_or_create_station(table, station_name);
    update_station(station, temperature);
}

/**
 * Comparison function for qsort (sort stations by name)
 */
int compare_stations(const void *a, const void *b) {
    Station *s1 = *(Station**)a;
    Station *s2 = *(Station**)b;
    return strcmp(s1->name, s2->name);
}

/**
 * Collect all stations from hash table into array for sorting
 */
Station** collect_stations(HashTable *table) {
    Station **stations = (Station**)malloc(table->station_count * sizeof(Station*));
    if (stations == NULL) {
        fprintf(stderr, "Error: Out of memory\n");
        exit(1);
    }

    int idx = 0;
    for (int i = 0; i < HASH_TABLE_SIZE; i++) {
        Station *current = table->buckets[i];
        while (current != NULL) {
            stations[idx++] = current;
            current = current->next;
        }
    }

    return stations;
}

/**
 * Print results
 */
void print_results(HashTable *table) {
    // Collect stations into array
    Station **stations = collect_stations(table);

    // Sort by name
    qsort(stations, table->station_count, sizeof(Station*), compare_stations);

    // Print first 5 stations
    fprintf(stderr, "\nFirst 5 stations (alphabetically):\n");
    int limit = table->station_count < 5 ? table->station_count : 5;
    for (int i = 0; i < limit; i++) {
        Station *s = stations[i];
        double mean = s->sum / s->count;
        fprintf(stderr, "  %s;%.1f;%.1f;%.1f\n",
                s->name, s->min, mean, s->max);
    }

    free(stations);
}

/**
 * Free hash table memory
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
 * Main entry point
 */
int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <input_file>\n", argv[0]);
        return 1;
    }

    const char *filename = argv[1];

    fprintf(stderr, "=== 1 Billion Row Challenge - C Baseline ===\n");
    fprintf(stderr, "Input file: %s\n", filename);
    fprintf(stderr, "Strategy: Standard I/O, single-threaded\n\n");

    // Open file
    FILE *file = fopen(filename, "r");
    if (file == NULL) {
        fprintf(stderr, "Error: Cannot open file '%s'\n", filename);
        return 1;
    }

    // Initialize hash table
    HashTable table;
    init_hash_table(&table);

    // Process file
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    char line[LINE_BUFFER_SIZE];
    while (fgets(line, sizeof(line), file) != NULL) {
        process_line(&table, line);
    }

    clock_gettime(CLOCK_MONOTONIC, &end);

    fclose(file);

    // Calculate duration
    double duration = (end.tv_sec - start.tv_sec) +
                     (end.tv_nsec - start.tv_nsec) / 1e9;

    // Print results
    fprintf(stderr, "\n=== Results ===\n");
    fprintf(stderr, "Stations found: %d\n", table.station_count);
    fprintf(stderr, "Duration: %.2f seconds\n", duration);

    print_results(&table);

    // Cleanup
    free_hash_table(&table);

    return 0;
}
