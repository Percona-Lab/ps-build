#!/bin/python

'''
1. For a given server version (8.0, 8.4) and build type (Debug/RelWithDebInfo) run full Jenkins test.

2. Copy results from "Test Results" e.g.
https://ps80.cd.percona.com/view/ASan+Valgrind/job/percona-server-TEST-pipeline-parallel-mtr/14/testReport/
to text file. The format is following:
ubuntu-noble.RelWithDebInfo.WORKER_4.group_replication-big	2 hr 59 min	0		654	+654	218	+218	872	+872
ubuntu-noble.RelWithDebInfo.WORKER_5.rocksdb	1 hr 24 min	2	+2	89	+89	265	+265	356	+356
ubuntu-noble.RelWithDebInfo.WORKER_7.main	1 hr 20 min	0		369	+369	1289	+1289	1658	+1658

3. Update manually results for UNIT_TESTS

4. Run this script (e.g. "./gen-suites-groups.py results-PS80-Debug*") that:
- Computes average runtime for each suite across all input files.
- Divides suites into 8 groups so each group will take similar amount of time.
  The following suites are always assigned to WORKER_1:
  fixed_first_group = "UNIT_TESTS", "ps_protocol", "ci_fs", "keyring_vault_dev_v1", "keyring_vault_dev_v2",
                                                          "keyring_vault_prod_v1", "keyring_vault_prod_v2"
- Prints 8 groups of suites as WORKER_x_MTR_SUITES, but doesn't print suites from "fixed_first_group"
'''

import re
import sys
from collections import defaultdict

def time_to_seconds(time_str):
    hours = minutes = 0
    seconds = 0.0

    hr_match = re.search(r'(\d+)\s*hr', time_str)
    min_match = re.search(r'(\d+)\s*min', time_str)
    sec_match = re.search(r'(\d+(?:\.\d+)?)\s*sec', time_str)
    ms_match = re.search(r'(\d+(?:\.\d+)?)\s*ms', time_str)

    if hr_match:
        hours = int(hr_match.group(1))
    if min_match:
        minutes = int(min_match.group(1))
    if sec_match:
        seconds += float(sec_match.group(1))
    if ms_match:
        seconds += float(ms_match.group(1)) / 1000.0

    return hours * 3600 + minutes * 60 + seconds

def parse_file(filename):
    print("Parsing "+ filename, file=sys.stderr)
    results = {}
    suite_pattern = re.compile(
        r'([^\s]+)\s+((?:\d+\s*hr\s*)?(?:\d+\s*min\s*)?(?:\d+(?:\.\d+)?\s*sec\s*)?(?:\d+(?:\.\d+)?\s*ms)?)'
    )

    with open(filename, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            match = suite_pattern.match(line)
            if not match:
                continue

            full_name, runtime = match.groups()
            suite_name = full_name.split('.')[-1]

            # Replacement adjustments
            suite_name = suite_name.replace("engines_funcs", "engines/funcs")
            suite_name = suite_name.replace("engines_iuds", "engines/iuds")

            seconds = time_to_seconds(runtime)
            results[suite_name] = (runtime.strip(), seconds)

    return results

def combine_results(files):
    """Combine results from multiple files and compute average time per suite."""
    from collections import defaultdict
    suite_totals = defaultdict(float)
    suite_counts = defaultdict(int)

    for f in files:
        results = parse_file(f)
        for suite, (_, seconds) in results.items():
            suite_totals[suite] += seconds
            suite_counts[suite] += 1

    averaged = {}
    for suite in suite_totals:
        avg_seconds = suite_totals[suite] / suite_counts[suite]
        # Only keep seconds, remove avg_runtime_str
        averaged[suite] = (avg_seconds,)

    return averaged

def pair_suites(results):
    paired = {}
    processed = set()

    for suite in results:
        if suite in processed:
            continue

        if suite.endswith('-big'):
            base_suite = suite[:-4]
            big_suite = suite
            nobig_suite = base_suite
        else:
            base_suite = suite
            nobig_suite = suite
            big_suite = suite + '-big'

        nobig_data = results.get(nobig_suite)
        big_data = results.get(big_suite)

        nobig_seconds = nobig_data[0] if nobig_data else 0
        big_seconds = big_data[0] if big_data else 0

        MERGE_MIN_TIME = 120
        if nobig_seconds < MERGE_MIN_TIME or big_seconds < MERGE_MIN_TIME:
            total_seconds = nobig_seconds + big_seconds
            data_to_store = nobig_data if nobig_data else big_data
            paired[nobig_suite] = (total_seconds,)
        else:
            if nobig_data:
                paired[f"{base_suite}|nobig"] = nobig_data
            if big_data:
                paired[f"{base_suite}|big"] = big_data

        processed.update([nobig_suite, big_suite])

    return paired

def group_suites(paired_results, num_groups=8, fixed_first_group=None):
    if fixed_first_group is None:
        fixed_first_group = []

    suites_sorted = sorted(paired_results.items(), key=lambda x: -x[1][0])
    groups = [[] for _ in range(num_groups)]
    group_times = [0.0] * num_groups

    for suite_name in fixed_first_group:
        if suite_name in paired_results:
            seconds = paired_results[suite_name][0]
            groups[0].append((suite_name, seconds))
            group_times[0] += seconds

    remaining_suites = [(s, r) for s, r in suites_sorted if s not in fixed_first_group]

    for suite_name, (seconds,) in remaining_suites:
        idx_min = group_times.index(min(group_times))
        groups[idx_min].append((suite_name, seconds))
        group_times[idx_min] += seconds

    return groups

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: python {sys.argv[0]} <input_file1> [<input_file2> ...] [--debug]")
        sys.exit(1)

    debug = "--debug" in sys.argv
    input_files = [arg for arg in sys.argv[1:] if arg != "--debug"]

    if not input_files:
        print(f"Error: No input files provided.\nUsage: python {sys.argv[0]} <input_file1> [<input_file2> ...] [--debug]", file=sys.stderr)
        sys.exit(1)

    results = combine_results(input_files)
    paired_results = pair_suites(results)

    if debug:
        print(f"{'Suite':<30}\t{'Avg Runtime':<20}\t{'Avg Seconds'}")
        for suite, (runtime, seconds) in paired_results.items():
            print(f"{suite:<30}\t{runtime:<20}\t{seconds:.2f}")
        print("\n")

    print("Number of original suites:", len(results), file=sys.stderr)
    print("Number of paired suites:", len(paired_results), file=sys.stderr)

    fixed_first_group = [
        "UNIT_TESTS", "ps_protocol", "ci_fs",
        "keyring_vault_dev_v1", "keyring_vault_dev_v2",
        "keyring_vault_prod_v1", "keyring_vault_prod_v2"
    ]

    groups = group_suites(paired_results, num_groups=8, fixed_first_group=fixed_first_group)

    # Print detailed group info only in debug mode
    for i, group in enumerate(groups):
        total_time = sum(s[1] for s in group)  # seconds is now at index 1
        print(f"\nGroup {i+1} - Total time: {total_time:.3f} sec")
        for suite_name, seconds in group:
            print(f"{suite_name:<30}\t{seconds:.2f}")

    print("")
    for i, group in enumerate(groups):
        total_time = sum(s[1] for s in group)
        suite_names = [
            suite_name for suite_name, seconds in group
            if suite_name not in fixed_first_group
        ]
        print(f"WORKER_{i+1}_MTR_SUITES=\"{','.join(suite_names)}\"")
