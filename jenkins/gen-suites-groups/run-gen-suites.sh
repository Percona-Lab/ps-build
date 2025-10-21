#!/bin/bash

for prefix in PS57-RelWithDebInfo PS57-Debug PS57-valgrind \
              PS80-RelWithDebInfo PS80-Debug PS80-valgrind \
              PS84-RelWithDebInfo PS84-Debug \
              PS9x-RelWithDebInfo PS9x-Debug \
              MySQL57-RelWithDebInfo MySQL57-Debug MySQL57-valgrind \
              MySQL80-valgrind; do
    echo "- Generating suites list for $prefix"
    ./gen-suites-groups.py ${prefix}* > suites-groups-${prefix}.txt
    echo "Output written to suites-groups-${prefix}.txt"
done
