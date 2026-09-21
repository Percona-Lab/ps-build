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
        # Extract suite from --junit-package.
        # New dynamic-pipeline tag: ...WORKER_<worker>_<seq>_<suite>[-big|-nobig]
        # Old static format:        ...WORKER_<N>.<suite>
        # Match the new format first, fall back to the old one. [^[:space:]]+ stops at the
        # next MTR argument instead of swallowing the rest of the line.
        if [[ "$line" =~ --junit-package=[^[:space:]]*\.WORKER_[0-9]+_[0-9]+_([^[:space:]]+) ]]; then
            current_suite="${BASH_REMATCH[1]}"
            # The dynamic tag sanitizes the queue item ("innodb|big" -> "innodb_big") and MTR's
            # own "-big" suffix is appended after it, so the big half arrives as "innodb_big-big"
            # and the nobig half as "innodb_nobig". Map both back to the "<suite>" / "<suite>-big"
            # convention gen-suites-groups.py pairs on; a light (unsplit) suite has neither
            # marker and is already in that form.
            if [[ "$current_suite" == *-big ]]; then
                current_suite="${current_suite%-big}"
                current_suite="${current_suite%_big}-big"
            else
                current_suite="${current_suite%_nobig}"
            fi
        elif [[ "$line" =~ --junit-package=[^[:space:]]*\.WORKER_[0-9]+\.([^[:space:]]+) ]]; then
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
