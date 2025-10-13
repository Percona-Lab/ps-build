#!/bin/bash

# Some notes about this script:
# 1. When started as suites-groups.sh check <path to mysql-test-run.pl> it checks if there are any incosistencies
#    between suites specified in mysql-test-run.pl and in this script
# 2. Jenkins pipeline checks for inconsistencies (1) and then sources this script to learn about suites split
# 3. The default split is defined in ./jenkins/suites-groups.sh
# 4. The default can be overrode by mysql-test/suites-groups.sh if the file is present. This allows one to define custom suites
#    split on development branch
# 5. Jenkins pipeline fails if inconsistencies are detected while using the default split (3)
# 6. Jenkins pipeline continues with warning if inconsistencies are detected while using the custom split (4)
# 7. Jenkins scripts support following suite formats:
#
#    main       - all tests will be allowed to be executed (big and no-big). Note that the final decision belongs to --big-tests MTR parameter
#    main|nobig - only no-big tests are allowed
#    main|big   - only big tests are allowed
#
#    Such approach makes it possible to split the suite execution among two workers, where one woker executes no-big test
#    and another executes only bit tests.


# is_version_equal_or_bigger <v1> <v2>
# returns 0 if v1 >= v2, 1 otherwise
function is_version_equal_or_bigger() {
  local v1="$1"
  local v2="$2"
  local first=$(printf "%s\n%s\n" "$v2" "$v1" | sort -V | head -n1)   # sort -V sorts versions correctly

  if [[ "$first" == "$v2" ]]; then
    return 0  # v1 >= v2
  else
    return 1  # v1 < v2
  fi
}

# usage: set_suites_80 <BUILD_TYPE>
function set_suites_80() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for Valgrind"
    # TO DO: Update, copied from Debug
    WORKER_1_MTR_SUITES="group_replication|nobig,rocksdb|big,innodb_gis|big,component_encryption_udf,audit_log_filter,binlog|big,encryption|big,rpl_encryption,innodb_zip|nobig,json,query_rewrite_plugins,binlog_57_decryption"
    WORKER_2_MTR_SUITES="main|big"
    WORKER_3_MTR_SUITES="group_replication|big,binlog|nobig,component_keyring_file|nobig,clone|big,sysschema|nobig,gcol|big,information_schema,jp,gis,audit_null,service_status_var_registration,service_udf_registration"
    WORKER_4_MTR_SUITES="main|nobig,perfschema|nobig,auth_sec|big,parts|nobig,engines/iuds,stress,innodb_gis|nobig,funcs_1|big,connection_control,interactive_utilities"
    WORKER_5_MTR_SUITES="innodb|nobig,parts|big,rocksdb_rpl|nobig,binlog_nogtid,innodb_fts|big,component_masking_functions,rocksdb_stress,gcol|nobig,service_sys_var_registration,secondary_engine"
    WORKER_6_MTR_SUITES="innodb|big,engines/funcs,rpl_gtid|nobig,component_keyring_file|big,rpl_nogtid|big,x|big,innodb_undo|big,federated,audit_log,test_services,procfs,percona-pam-for-mysql"
    WORKER_7_MTR_SUITES="rocksdb|nobig,innodb_undo|nobig,clone|nobig,rpl_gtid|big,sys_vars,auth_sec|nobig,innodb_zip|big,funcs_1|nobig,sysschema|big,perfschema|big,test_service_sql_api,rocksdb_sys_vars"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl|big,rpl_nogtid|nobig,x|nobig,innodb_fts|nobig,binlog_gtid,rocksdb_rpl|big,collations,funcs_2,encryption|nobig,data_masking,opt_trace"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="main|big,rpl_nogtid|nobig,x|nobig,auth_sec|big,innodb_gis,rpl_gtid|big,connection_control,jp,rocksdb_sys_vars"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rocksdb|nobig,innodb_fts,x|big,component_masking_functions,funcs_2,test_service_sql_api,service_sys_var_registration,opt_trace"
    WORKER_4_MTR_SUITES="main|nobig,engines/funcs,rpl_nogtid|big,perfschema,binlog_nogtid,binlog_gtid,rpl_encryption,gis,data_masking"
    WORKER_5_MTR_SUITES="rpl|nobig,rpl_gtid|nobig,clone,component_keyring_file,innodb_zip,funcs_1,federated,information_schema,audit_null,service_udf_registration,procfs,percona-pam-for-mysql"
    WORKER_6_MTR_SUITES="group_replication|nobig,innodb|big,sys_vars,parts|nobig,sysschema,stress,audit_log,binlog_57_decryption,secondary_engine"
    WORKER_7_MTR_SUITES="innodb|nobig,component_encryption_udf,rocksdb_rpl,binlog,innodb_undo,rocksdb_stress,gcol,test_services,json,service_status_var_registration,encryption"
    WORKER_8_MTR_SUITES="rpl|big,rocksdb|big,parts|big,auth_sec|nobig,audit_log_filter,engines/iuds,collations,query_rewrite_plugins,interactive_utilities"
  else # Debug (and everything different from "RelWithDebInfo")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="group_replication|nobig,rocksdb|big,innodb_gis|big,component_encryption_udf,audit_log_filter,binlog|big,encryption|big,rpl_encryption,innodb_zip|nobig,json,query_rewrite_plugins,binlog_57_decryption"
    WORKER_2_MTR_SUITES="main|big"
    WORKER_3_MTR_SUITES="group_replication|big,binlog|nobig,component_keyring_file|nobig,clone|big,sysschema|nobig,gcol|big,information_schema,jp,gis,audit_null,service_status_var_registration,service_udf_registration"
    WORKER_4_MTR_SUITES="main|nobig,perfschema|nobig,auth_sec|big,parts|nobig,engines/iuds,stress,innodb_gis|nobig,funcs_1|big,connection_control,interactive_utilities"
    WORKER_5_MTR_SUITES="innodb|nobig,parts|big,rocksdb_rpl|nobig,binlog_nogtid,innodb_fts|big,component_masking_functions,rocksdb_stress,gcol|nobig,service_sys_var_registration,secondary_engine"
    WORKER_6_MTR_SUITES="innodb|big,engines/funcs,rpl_gtid|nobig,component_keyring_file|big,rpl_nogtid|big,x|big,innodb_undo|big,federated,audit_log,test_services,procfs,percona-pam-for-mysql"
    WORKER_7_MTR_SUITES="rocksdb|nobig,innodb_undo|nobig,clone|nobig,rpl_gtid|big,sys_vars,auth_sec|nobig,innodb_zip|big,funcs_1|nobig,sysschema|big,perfschema|big,test_service_sql_api,rocksdb_sys_vars"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl|big,rpl_nogtid|nobig,x|nobig,innodb_fts|nobig,binlog_gtid,rocksdb_rpl|big,collations,funcs_2,encryption|nobig,data_masking,opt_trace"
  fi
}

# usage: set_suites_84 <BUILD_TYPE>
function set_suites_84() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for Valgrind"
    # TO DO: Update, copied from Debug
    WORKER_1_MTR_SUITES="rocksdb|nobig,innodb_undo|nobig,clone|big,binlog|nobig,percona,rpl_nogtid|big,rocksdb_rpl|big,engines/iuds,gcol|big,test_services,connection_control"
    WORKER_2_MTR_SUITES="main|big,sysschema|nobig,stress,encryption|nobig,binlog_gtid|big,query_rewrite_plugins,procfs,percona-pam-for-mysql,component_js_lang"
    WORKER_3_MTR_SUITES="group_replication|big,x|nobig,component_keyring_file|nobig,innodb_zip|big,x|big,component_masking_functions,funcs_1|big,gis"
    WORKER_4_MTR_SUITES="main|nobig,perfschema|nobig,component_audit_log_filter,binlog|big,binlog_nogtid,rpl_encryption,innodb_gis|nobig,rocksdb_stress,secondary_engine,audit_null"
    WORKER_5_MTR_SUITES="innodb|nobig,rpl_gtid|nobig,parts|big,rocksdb_rpl|nobig,component_keyring_file|big,binlog_gtid|nobig,encryption|big,innodb_zip|nobig,jp,service_sys_var_registration"
    WORKER_6_MTR_SUITES="innodb|big,innodb_gis|big,component_encryption_udf,innodb_fts|nobig,innodb_undo|big,auth_sec|nobig,perfschema|big,funcs_2,opt_trace,test_service_sql_api"
    WORKER_7_MTR_SUITES="group_replication|nobig,rocksdb|big,engines/funcs,clone|nobig,sys_vars,auth_sec|big,innodb_fts|big,collations,federated,gcol|nobig,interactive_utilities,information_schema"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl|big,rpl_nogtid|nobig,percona_innodb|nobig,rpl_gtid|big,parts|nobig,percona_innodb|big,funcs_1|nobig,sysschema|big,json,rocksdb_sys_vars,service_status_var_registration,service_udf_registration"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="main|big,engines/funcs,parts|big,rpl_nogtid|big,component_keyring_file,innodb_undo,stress,test_service_sql_api,federated,secondary_engine,encryption"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rocksdb|nobig,percona_innodb,auth_sec|nobig,perfschema,rocksdb_stress,rpl_encryption,gis,opt_trace"
    WORKER_4_MTR_SUITES="group_replication|nobig,component_encryption_udf,percona,innodb_gis,gcol,funcs_2,rocksdb_sys_vars,service_status_var_registration"
    WORKER_5_MTR_SUITES="rpl|nobig,rpl_gtid,auth_sec|big,parts|nobig,binlog_nogtid,jp,json,information_schema,audit_null"
    WORKER_6_MTR_SUITES="main|nobig,rpl_nogtid|nobig,binlog,component_audit_log_filter,engines/iuds,binlog_gtid,query_rewrite_plugins,service_udf_registration,procfs,percona-pam-for-mysql,component_js_lang"
    WORKER_7_MTR_SUITES="rpl|big,innodb|big,x|nobig,clone,x|big,innodb_zip,funcs_1,connection_control,service_sys_var_registration,interactive_utilities"
    WORKER_8_MTR_SUITES="innodb|nobig,rocksdb|big,rocksdb_rpl,sys_vars,innodb_fts,sysschema,component_masking_functions,test_services,collations"
  else # Debug (and everything different from "RelWithDebInfo")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="rocksdb|nobig,innodb_undo|nobig,clone|big,binlog|nobig,percona,rpl_nogtid|big,rocksdb_rpl|big,engines/iuds,gcol|big,test_services,connection_control"
    WORKER_2_MTR_SUITES="main|big,sysschema|nobig,stress,encryption|nobig,binlog_gtid|big,query_rewrite_plugins,procfs,percona-pam-for-mysql,component_js_lang"
    WORKER_3_MTR_SUITES="group_replication|big,x|nobig,component_keyring_file|nobig,innodb_zip|big,x|big,component_masking_functions,funcs_1|big,gis"
    WORKER_4_MTR_SUITES="main|nobig,perfschema|nobig,component_audit_log_filter,binlog|big,binlog_nogtid,rpl_encryption,innodb_gis|nobig,rocksdb_stress,secondary_engine,audit_null"
    WORKER_5_MTR_SUITES="innodb|nobig,rpl_gtid|nobig,parts|big,rocksdb_rpl|nobig,component_keyring_file|big,binlog_gtid|nobig,encryption|big,innodb_zip|nobig,jp,service_sys_var_registration"
    WORKER_6_MTR_SUITES="innodb|big,innodb_gis|big,component_encryption_udf,innodb_fts|nobig,innodb_undo|big,auth_sec|nobig,perfschema|big,funcs_2,opt_trace,test_service_sql_api"
    WORKER_7_MTR_SUITES="group_replication|nobig,rocksdb|big,engines/funcs,clone|nobig,sys_vars,auth_sec|big,innodb_fts|big,collations,federated,gcol|nobig,interactive_utilities,information_schema"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl|big,rpl_nogtid|nobig,percona_innodb|nobig,rpl_gtid|big,parts|nobig,percona_innodb|big,funcs_1|nobig,sysschema|big,json,rocksdb_sys_vars,service_status_var_registration,service_udf_registration"
  fi
}

# usage: set_suites_9x <BUILD_TYPE> <SERVER_VERSION>
function set_suites_9x() {
  local server_version="$2"

  if [[ "$1" == "RelWithDebInfo" ]]; then
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=RelWithDebInfo"
    # Unit tests will be executed by worker 1
    WORKER_1_MTR_SUITES="main|nobig,percona|nobig,binlog_nogtid,innodb_undo,test_services,service_sys_var_registration,connection_control,service_status_var_registration,service_udf_registration,interactive_utilities"
    WORKER_2_MTR_SUITES="main|big,percona|big,component_js_lang"
    WORKER_3_MTR_SUITES="innodb,percona_innodb"
    WORKER_4_MTR_SUITES="component_connection_control,auth_sec,component_audit_log_filter,component_encryption_udf,component_masking_functions,percona-pam-for-mysql,procfs,rpl_encryption,audit_null,engines/iuds,engines/funcs,group_replication,jp,stress"
    WORKER_5_MTR_SUITES="rpl,rpl_gtid,rpl_nogtid,binlog,sys_vars,funcs_2,opt_trace,json,collations"
    WORKER_6_MTR_SUITES="innodb_gis,perfschema,parts,clone,query_rewrite_plugins,funcs_1"
    WORKER_7_MTR_SUITES="rocksdb,rocksdb_stress,rocksdb_rpl,innodb_zip,information_schema,rocksdb_sys_vars"
    WORKER_8_MTR_SUITES="component_keyring_file,innodb_fts,x,encryption,sysschema,binlog_gtid,gcol,federated,test_service_sql_api,gis,secondary_engine"
  else # Debug (and everything different from "RelWithDebInfo")
    echo "Setting WORKER_x_MTR_SUITES for BUILD_TYPE=Debug"
    # Unit tests will be executed by worker 1
    WORKER_1_MTR_SUITES="main|nobig,percona|nobig,binlog_nogtid,innodb_undo,test_services,service_sys_var_registration,connection_control,service_status_var_registration,service_udf_registration,interactive_utilities"
    WORKER_2_MTR_SUITES="main|big,percona|big"
    WORKER_3_MTR_SUITES="innodb,percona_innodb"
    WORKER_4_MTR_SUITES="component_connection_control,auth_sec,component_audit_log_filter,component_encryption_udf,component_masking_functions,percona-pam-for-mysql,procfs,rpl_encryption,audit_null,engines/iuds,engines/funcs,group_replication,jp,stress"
    WORKER_5_MTR_SUITES="rpl,rpl_gtid,rpl_nogtid,binlog,sys_vars,funcs_2,opt_trace,json,collations"
    WORKER_6_MTR_SUITES="innodb_gis,perfschema,parts,clone,query_rewrite_plugins,funcs_1"
    WORKER_7_MTR_SUITES="rocksdb,rocksdb_stress,rocksdb_rpl,innodb_zip,information_schema,rocksdb_sys_vars"
    WORKER_8_MTR_SUITES="component_keyring_file,innodb_fts,x,encryption,sysschema,binlog_gtid,gcol,federated,test_service_sql_api,gis,secondary_engine,component_js_lang"
  fi

  if is_version_equal_or_bigger "$server_version" "9.4"; then
    WORKER_3_MTR_SUITES+=",jdv"
  fi
}

# usage: set_suites <BUILD_TYPE> <SERVER_VERSION>
function set_suites() {
  local server_version="$2"

  case "$server_version" in
    8.0.*)
      set_suites_80 $1
      ;;
    8.4.*)
      set_suites_84 $1
      ;;
    9.*)
      set_suites_9x $1 "$server_version"
      ;;
    *)
      echo "Unsupported server version: $server_version"
      return 1
      ;;
  esac
}

# usage: check_suites <path_to_mysql-test-run.pl>
function check_suites() {
  INPUT=${1:-./mysql-test-run.pl}

  if [[ ! -f ${INPUT} ]]
    then
    echo "${INPUT} file does not exist on your filesystem."
    return 1
  fi

  echo "Checking if suites list is consistent with the one specified in mysql-test-run.pl"
  echo

  local all_suites_1=,${WORKER_1_MTR_SUITES},${WORKER_2_MTR_SUITES},${WORKER_3_MTR_SUITES},${WORKER_4_MTR_SUITES},${WORKER_5_MTR_SUITES},${WORKER_6_MTR_SUITES},${WORKER_7_MTR_SUITES},${WORKER_8_MTR_SUITES},

  local all_suites_2=
  local capturing=0
  while read -r line
  do
    if [[ "${capturing}" == "1" ]]; then
      if [[ "${line}" == *");"* ]]; then
        capturing=0
        break
      fi
    fi

    if [[ "$capturing" == "1" ]]; then
      local all_suites_2=${all_suites_2}${line},
    fi

    if [[ "${line}" == *"DEFAULT_SUITES = qw"* ]]; then
      capturing=1
    fi

  done < "${INPUT}"

  # add leading and trailing commas for easier parsing
  all_suites_2=,${all_suites_2},

  echo "Suites for Jenkins: ${all_suites_1}"
  echo
  echo "Suites from mysql-test-run.pl: ${all_suites_2}"
  echo

  local failure=0

  # check if splited suite contains both big/nobig parts
  for suite in ${all_suites_1//,/ }
  do
    if [[ ${suite} == *"|"* ]]; then

        arrSuite=(${suite//|/ })
        suite=${arrSuite[0]}
        nobig_found=0
        for suite_nobig in ${all_suites_1//,/ }
        do
          if [[ ${suite_nobig} == "${suite}|nobig" ]]; then
            nobig_found=1
          fi
        done

        big_found=0
        for suite_big in ${all_suites_1//,/ }
        do
          if [[ ${suite_big} == "${suite}|big" ]]; then
            big_found=1
          fi
        done

        if [[ ${nobig_found} == "0" || ${big_found} == "0" ]]; then
          echo "${suite} big|nobig (${big_found}|${nobig_found} mismatch)"
          failure=1
        fi
    fi
  done
  # get rid of bin/nobig before two-way matching
  all_suites_1=${all_suites_1//"|big"/""}
  all_suites_1=${all_suites_1//"|nobig"/""}

  # check if the suite from pl scipt is assigned to any worker
  for suite in ${all_suites_2//,/ }
  do
    if [[ ${all_suites_1} != *",${suite},"* ]]; then
      echo "${suite} specified in mysql-test-run.pl but missing in Jenkins"
      failure=1
    fi
  done

  # check if the suite from pl scipt is assigned to any worker
  for suite in ${all_suites_1//,/ }
  do
    if [[ ${all_suites_2} != *",${suite},"* ]]; then
      echo "${suite} specified in Jenkins but not present in mysql-test-run.pl"
      failure=1
    fi
  done

  echo "************************"
  if [[ "${failure}" == "1" ]]; then
    echo "Inconsitencies detected"
  else
    echo "Everything is OK"
  fi
  echo "************************"

  return ${failure}
}
