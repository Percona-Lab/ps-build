#!/usr/bin/env bash

# Loops over all log files provided as arguments.
# Reads each file line by line.
# Detects a suite name using --junit-package=WORKER_<N>.<suite> and stores it in current_suite.
# Detects a line like Spent X of Y seconds executing testcases and extracts Y.
# Prints <suite> <total_seconds> whenever a time line is found.

if [ $# -lt 1 ]; then
    echo "Usage: $0 <logfile1> [<logfile2> ...]"
    exit 1
fi

for logfile in "$@"; do
    if [ ! -f "$logfile" ]; then
        echo "Warning: File '$logfile' not found, skipping."
        continue
    fi

    current_suite=""

    while IFS= read -r line; do
        # Extract suite from --junit-package
        if [[ "$line" =~ --junit-package=.*\.WORKER_[0-9]+\.(.*) ]]; then
            current_suite="${BASH_REMATCH[1]}"
        fi

        # Extract total seconds from "Spent X of Y seconds executing testcases"
        if [[ "$line" =~ Spent[[:space:]]+[0-9.]+[[:space:]]+of[[:space:]]+([0-9]+)[[:space:]]+seconds[[:space:]]+executing[[:space:]]+testcases ]]; then
            total_seconds="${BASH_REMATCH[1]}"
            if [ -n "$current_suite" ]; then
                echo "$current_suite $total_seconds"
            fi
        fi
    done < "$logfile"
done
