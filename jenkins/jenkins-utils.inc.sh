#!/bin/echo This script should be sourced in a shell, not executed directly

#  required env vars:
#      WORKSPACE       - Jenkins workspace where results/atrifacts are copied
#      WORK_DIR        - path on a server where all tests are done
#      PS_BUILD_DIR    - path on a server to `ps-build` repo
#      PS_BUILD_REPO   - URL to ps-build repo e.g. https://github.com/Percona-Lab/ps-build
#      PS_BUILD_BRANCH - branch for ps-build repo e.g. "8.0"
#      SKIP_BUILD = (yes no) - skip building a server from sources and use last build for a given PS_BUILD_BRANCH
#      GIT_REPO, BRANCH - used by ./local/checkout
#      JOB_CMAKE, COMPILER, CMAKE_BUILD_TYPE, WITH_ROCKSDB, WITH_ROUTER, WITH_GCOV, WITH_MYSQLX, WITH_JS_LANG,
#                        ANALYZER_OPTS, MAKE_OPTS, CMAKE_OPTS - used by local/build-binary
#      CMAKE_BUILD_TYPE, KEEP_BUILD, ANALYZER_OPTS, MTR_ARGS, MTR_REPEAT, MTR_SUITES, WORKER_NO, DOCKER_OS, CI_FS_MTR,
#                        WITH_PS_PROTOCOL, KEYRING_VAULT_MTR, WITH_ROCKSDB - used by local/test-binary-parallel-mtr


# download "ps-build" repo, download server sources if needed, build a server, test a server with a sanitizer
function build_and_run_sanitizer() {
    if [[ -z "$PS_BUILD_DIR" ]] || [[ -z "$WORK_DIR" ]] || [[ -z "$WORKSPACE" ]]; then
        echo "Error: PS_BUILD_DIR or WORK_DIR or WORKSPACE is not set."
        return 1
    fi

    # RESULTS_DIR has to be equal to RESULTS_DIR variable defined in /local/test-binary-parallel-mtr
    local RESULTS_DIR=$WORK_DIR/results

    # clear results from the last run
    if [[ -d $RESULTS_DIR ]]; then
        ls -la $RESULTS_DIR
        rm -rf $RESULTS_DIR
    fi

    # download "ps-build" repo and share it with 8.0/8.x branches
    if [[ ! -d "$PS_BUILD_DIR" ]]; then
        sudo mkdir -p $PS_BUILD_DIR
        sudo chown $USER:$USER $PS_BUILD_DIR
        git clone --branch 8.0 "$PS_BUILD_REPO" "$PS_BUILD_DIR" || { echo "Error: git clone failed"; return 1; }
        cd $PS_BUILD_DIR
        # call /docker/install-deps from 8.0 branch
        sudo ./docker/install-deps
    fi

    # download repo if needed, checkout to PS_BUILD_BRANCH
    setup_git_repo $PS_BUILD_REPO $PS_BUILD_BRANCH $PS_BUILD_DIR
    cd $PS_BUILD_DIR

    # build from sources unless SKIP_BUILD=yes
    if [ "$SKIP_BUILD" != "yes" ]; then
        sudo rm -rf $WORK_DIR
        sudo mkdir -p $WORK_DIR
        sudo chown $USER:$USER $WORK_DIR
        sudo git clean -xdf
        ./local/checkout
        ./local/build-binary $WORK_DIR
    fi

    if [[ -z "$MTR_SUITES" ]]; then
        export MTR_SUITES=$(extract_default_suites sources/mysql-test/mysql-test-run.pl)
    fi
    echo MTR_SUITES=$MTR_SUITES
    ./local/test-binary-parallel-mtr $WORK_DIR | tee $WORK_DIR/mtr-test.log

    cd $WORK_DIR
    mv mtr-test.log mtr-test.full-log
    filter_valgrind_log mtr-test.full-log mtr-test.log
    zstd -c mtr-test.log > mtr-test.log.zst

    grep -A16 "Conditional jump or move depends on uninitialised" mtr-test.log >mtr-test-valgrind-ConditionalJump.log || :
    grep -A16 "are definitely lost"         mtr-test.log >mtr-test-valgrind-definitelyLost.log || :
    grep -B128 -A128 "Invalid read of size" mtr-test.log >mtr-test-valgrind-InvalidRead.log || :
    grep -A16 "ERROR: .*Sanitizer"          mtr-test.log >mtr-test-Errors-Sanitizers.log || :
    grep -A16 "ERROR: AddressSanitizer"     mtr-test.log >mtr-test-Errors-AddressSanitizer.log || :
    grep -A16 "ERROR: LeakSanitizer"        mtr-test.log >mtr-test-Errors-LeakSanitizer.log || :

    ls -l
    cp $WORK_DIR/*.log* $WORKSPACE/
    cp $WORK_DIR/*.txt $WORKSPACE/
    cp $RESULTS_DIR/*.xml $WORKSPACE/
    cp $RESULTS_DIR/*.tar.gz $WORKSPACE/
}
