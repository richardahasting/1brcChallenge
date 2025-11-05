#!/usr/bin/env python3
"""
Generate test data for the 1 Billion Row Challenge.

Usage:
    ./generate_data.py <num_rows> <output_file>

Example:
    ./generate_data.py 1000000 ../data/measurements_1m.txt
"""

import sys
import random
import os

# Weather station names (mix of real cities and varied lengths)
STATIONS = [
    "Hamburg", "Bulawayo", "Palembang", "St. John's", "Cracow",
    "Bridgetown", "Istanbul", "Roseau", "Conakry", "Istanbul",
    "Pyongyang", "Halifax", "Colombo", "Kabul", "Dodoma",
    "Tashkent", "Bratislava", "Gaborone", "Ouagadougou", "Athens",
    "Ulan Bator", "Kampala", "Rangoon", "Riga", "Zanzibar",
    "Jerusalem", "Yerevan", "Amman", "Lome", "Mogadishu",
    "Suva", "Niamey", "Manama", "Thimphu", "Zagreb",
    "Prague", "Antananarivo", "Sarajevo", "Kuala Lumpur", "Belgrade",
    "Kuwait City", "Bamako", "Asmara", "Reykjavik", "Valletta",
    "Podgorica", "Nicosia", "Tegucigalpa", "Nouakchott", "Vilnius",
    "Buenos Aires", "San Francisco", "New York", "Los Angeles", "Chicago",
    "Houston", "Phoenix", "Philadelphia", "San Antonio", "San Diego",
    "Dallas", "San Jose", "Austin", "Jacksonville", "Fort Worth",
    "Columbus", "Indianapolis", "Charlotte", "Seattle", "Denver",
    "Boston", "Detroit", "Nashville", "Memphis", "Portland",
    "Oklahoma City", "Las Vegas", "Louisville", "Baltimore", "Milwaukee",
    "Albuquerque", "Tucson", "Fresno", "Sacramento", "Kansas City",
    "Mesa", "Atlanta", "Omaha", "Colorado Springs", "Raleigh",
    "Miami", "Long Beach", "Virginia Beach", "Oakland", "Minneapolis",
    "Tulsa", "Tampa", "Arlington", "New Orleans", "Wichita",
]

def generate_measurement():
    """Generate a single measurement line."""
    station = random.choice(STATIONS)
    # Temperature between -99.9 and 99.9, always with one decimal
    temp = random.uniform(-99.9, 99.9)
    return f"{station};{temp:.1f}\n"

def generate_data_file(num_rows, output_file):
    """Generate a data file with specified number of rows."""
    print(f"Generating {num_rows:,} measurements to {output_file}...")

    # Create output directory if it doesn't exist
    os.makedirs(os.path.dirname(output_file), exist_ok=True)

    with open(output_file, 'w', encoding='utf-8') as f:
        for i in range(num_rows):
            f.write(generate_measurement())

            # Progress indicator every 10 million rows
            if (i + 1) % 10_000_000 == 0:
                print(f"  Generated {i + 1:,} rows...")

    # Get file size
    file_size = os.path.getsize(output_file)
    file_size_mb = file_size / (1024 * 1024)
    file_size_gb = file_size / (1024 * 1024 * 1024)

    if file_size_gb >= 1.0:
        print(f"Done! Generated {num_rows:,} rows ({file_size_gb:.2f} GB)")
    else:
        print(f"Done! Generated {num_rows:,} rows ({file_size_mb:.2f} MB)")

def main():
    if len(sys.argv) != 3:
        print("Usage: generate_data.py <num_rows> <output_file>")
        print("\nExamples:")
        print("  ./generate_data.py 10000 ../data/measurements_10k.txt")
        print("  ./generate_data.py 1000000 ../data/measurements_1m.txt")
        print("  ./generate_data.py 1000000000 ../data/measurements_1b.txt")
        sys.exit(1)

    try:
        num_rows = int(sys.argv[1])
        output_file = sys.argv[2]

        if num_rows <= 0:
            print("Error: num_rows must be positive")
            sys.exit(1)

        generate_data_file(num_rows, output_file)

    except ValueError:
        print("Error: num_rows must be an integer")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
