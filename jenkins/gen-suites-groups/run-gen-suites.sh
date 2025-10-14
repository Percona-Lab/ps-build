#!/bin/bash

for prefix in "PS80-Debug" "PS80-RelWithDebInfo" "PS84-Debug" "PS84-RelWithDebInfo"; do
    echo "- Generating suites list for $prefix"
    ./gen-suites-groups.py results-${prefix}* > suites-groups-${prefix}.txt
    echo "Output written to suites-groups-${prefix}.txt"
done
