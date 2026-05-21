#!/bin/bash

# Notes about this script:
# 1. This file defines the function set_suites(), which sets WORKER_x_MTR_SUITES for x=1..8.
# 2. The function check_suites(), defined in https://github.com/Percona-Lab/ps-build/blob/8.0/local/utils.inc.sh
#    checks for inconsistencies between the suites specified in mysql-test-run.pl and those defined in this script.
# 3. The default split is defined in https://github.com/Percona-Lab/ps-build/blob/8.0/jenkins/suites-groups.sh
# 4. The default split can be overridden by mysql-test/suites-groups.sh, if present,
#    allowing custom suite splits on development branches.
# 5. By default, the Jenkins pipeline fails if inconsistencies are detected (IGNORE_INCONSISTENCY=0).
# 6. If IGNORE_INCONSISTENCY=1 is set, the pipeline continues with a warning instead of failing.
# 7. Jenkins scripts support the following suite formats:
#
#    main       - all tests will be allowed to be executed (big and no-big). Note that the final decision belongs to --big-tests MTR parameter
#    main|nobig - only no-big tests are allowed
#    main|big   - only big tests are allowed
#
#    Such approach makes it possible to split the suite execution among two workers, where one woker executes no-big test
#    and another executes only bit tests.

# Uncomment to continue testing even if this file is inconsistent with DEFAULT_SUITES from mysql-test-run.pl
#IGNORE_INCONSISTENCY=1


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

# usage: set_suites_mysql57 <BUILD_TYPE>
function set_suites_mysql57() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 5.7 with Valgrind"
    WORKER_1_MTR_SUITES="innodb|big,sysschema|big,binlog|nobig,gis,engines/iuds,test_service_sql_api"
    WORKER_2_MTR_SUITES="innodb|nobig"
    WORKER_3_MTR_SUITES="rpl|nobig,innodb_gis|big,gcol,innodb_gis|nobig"
    WORKER_4_MTR_SUITES="main|big,engines/funcs|nobig,innodb_zip|big,jp"
    WORKER_5_MTR_SUITES="binlog|big,perfschema|big,engines/funcs|big,auth_sec,innodb_zip|nobig,opt_trace,federated"
    WORKER_6_MTR_SUITES="group_replication|nobig,sys_vars,perfschema|nobig,parts|nobig,funcs_2,connection_control"
    WORKER_7_MTR_SUITES="main|nobig,group_replication|big,parts|big,innodb_fts|big,funcs_1,stress,audit_null"
    WORKER_8_MTR_SUITES="rpl|big,innodb_fts|nobig,x,sysschema|nobig,json,query_rewrite_plugins,innodb_undo"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 5.7 with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="x,auth_sec,funcs_1,innodb_gis,gcol,gis"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rpl|nobig"
    WORKER_4_MTR_SUITES="group_replication|nobig"
    WORKER_5_MTR_SUITES="rpl|big,perfschema,json,funcs_2,audit_null"
    WORKER_6_MTR_SUITES="innodb,sys_vars,innodb_zip,connection_control,sysschema,engines/iuds"
    WORKER_7_MTR_SUITES="main|nobig,parts,engines/funcs,test_service_sql_api,query_rewrite_plugins,opt_trace"
    WORKER_8_MTR_SUITES="main|big,binlog,stress,innodb_fts,jp,innodb_undo,federated"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 5.7 with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="main|big,innodb|big,x,engines/funcs,funcs_2,opt_trace"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rpl|nobig,sys_vars"
    WORKER_4_MTR_SUITES="group_replication|nobig,binlog|nobig,innodb_undo,audit_null,engines/iuds"
    WORKER_5_MTR_SUITES="innodb_fts,json,sysschema,test_service_sql_api,gis"
    WORKER_6_MTR_SUITES="rpl|big,innodb|nobig,funcs_1,connection_control,federated"
    WORKER_7_MTR_SUITES="binlog|big,parts,auth_sec,stress,query_rewrite_plugins"
    WORKER_8_MTR_SUITES="innodb_gis,main|nobig,perfschema,innodb_zip,gcol,jp"
  fi
}

# usage: set_suites_mysql80 <BUILD_TYPE>
function set_suites_mysql80() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 8.0 with Valgrind"
    WORKER_1_MTR_SUITES="main|nobig,rpl_nogtid|nobig,innodb_undo|big,parts|nobig,funcs_2|big,gcol|nobig,binlog_gtid|big,gis|nobig,interactive_utilities"
    WORKER_2_MTR_SUITES="innodb|big"
    WORKER_3_MTR_SUITES="rpl|big,sys_vars|nobig,binlog_nogtid|big,innodb_zip|nobig,auth_sec|big,query_rewrite_plugins,test_services,service_udf_registration,gis|big"
    WORKER_4_MTR_SUITES="main|big,perfschema|nobig,x|big,innodb_fts|big,binlog_gtid|nobig,innodb_gis|big,service_sys_var_registration,perfschema|big,information_schema"
    WORKER_5_MTR_SUITES="innodb|nobig,rpl_gtid|nobig,x|nobig,innodb_undo|nobig,innodb_fts|nobig,sysschema|nobig,gcol|big,funcs_2|nobig,connection_control,opt_trace|big"
    WORKER_6_MTR_SUITES="rpl_gtid|big,component_keyring_file|big,federated|big,parts|big,binlog_nogtid|nobig,encryption|nobig,innodb_gis|nobig,secondary_engine,service_status_var_registration"
    WORKER_7_MTR_SUITES="binlog|big,clone|nobig,clone|big,binlog|nobig,collations,json,opt_trace|nobig,encryption|big"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl_nogtid|big,sysschema|big,auth_sec|nobig,component_keyring_file|nobig,innodb_zip|big,sys_vars|big,test_service_sql_api,federated|nobig"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 8.0 with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="rpl_gtid|nobig,sys_vars,perfschema,binlog|nobig,funcs_2,gis"
    WORKER_2_MTR_SUITES="rpl|nobig"
    WORKER_3_MTR_SUITES="main|nobig,collations,interactive_utilities"
    WORKER_4_MTR_SUITES="rpl|big,auth_sec|nobig,sysschema,gcol,test_services,service_sys_var_registration"
    WORKER_5_MTR_SUITES="innodb|nobig,auth_sec|big,parts|nobig,rpl_gtid|big,federated,service_udf_registration"
    WORKER_6_MTR_SUITES="main|big,clone,innodb_fts,component_keyring_file,binlog_nogtid,binlog_gtid,json,secondary_engine"
    WORKER_7_MTR_SUITES="rpl_nogtid|nobig,x|nobig,binlog|big,innodb_undo,x|big,test_service_sql_api,information_schema,service_status_var_registration,encryption"
    WORKER_8_MTR_SUITES="innodb|big,parts|big,rpl_nogtid|big,innodb_gis,innodb_zip,connection_control,query_rewrite_plugins,opt_trace"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for MySQL 8.0 with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="clone|nobig,rpl_nogtid|nobig,rpl_gtid|big,sys_vars,auth_sec|big,binlog_nogtid|nobig,innodb_gis|nobig,funcs_2,connection_control,secondary_engine"
    WORKER_2_MTR_SUITES="main|big"
    WORKER_3_MTR_SUITES="main|nobig"
    WORKER_4_MTR_SUITES="innodb|nobig,innodb_fts|big,gcol|big,innodb_zip|nobig,perfschema|big"
    WORKER_5_MTR_SUITES="innodb|big,binlog|big,auth_sec|nobig,binlog_nogtid|big,sysschema|nobig,federated,information_schema,gis,interactive_utilities,service_status_var_registration,service_udf_registration"
    WORKER_6_MTR_SUITES="rpl|nobig,parts|big,binlog|nobig,component_keyring_file|nobig,parts|nobig,innodb_undo|big,encryption|big,collations,test_services,service_sys_var_registration"
    WORKER_7_MTR_SUITES="innodb_undo|nobig,rpl_gtid|nobig,x|nobig,component_keyring_file|big,rpl_nogtid|big,innodb_zip|big,sysschema|big,gcol|nobig,test_service_sql_api,query_rewrite_plugins"
    WORKER_8_MTR_SUITES="rpl|big,innodb_gis|big,perfschema|nobig,innodb_fts|nobig,clone|big,binlog_gtid,x|big,encryption|nobig,json,opt_trace"
  fi
}

# usage: set_suites_ps57 <BUILD_TYPE>
function set_suites_ps57() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 5.7 with Valgrind"
    WORKER_1_MTR_SUITES="group_replication|big,innodb_fts|nobig,innodb|big,engines/funcs|nobig,x,innodb_zip|big,innodb_gis|big,innodb_fts|big,tokudb.rpl,json,innodb_gis|nobig,test_service_sql_api,tokudb.bugs,innodb_undo,data_masking"
    WORKER_2_MTR_SUITES="main|nobig"
    WORKER_3_MTR_SUITES="innodb|nobig"
    WORKER_4_MTR_SUITES="rocksdb|big"
    WORKER_5_MTR_SUITES="rocksdb|nobig"
    WORKER_6_MTR_SUITES="rpl|nobig,rpl_encryption|nobig,rocksdb_rpl,parts|big,parts|nobig,binlog|big,rpl_encryption|big,binlog_encryption,tokudb,innodb_stress,query_response_time,connection_control,federated,tokudb.perfschema"
    WORKER_7_MTR_SUITES="main|big,rpl|big,sys_vars,perfschema|big,engines/funcs|big,auth_sec,gis,engines/iuds,gcol,funcs_2,tokudb.parts,opt_trace,audit_null,tokudb.alter_table"
    WORKER_8_MTR_SUITES="group_replication|nobig,binlog|nobig,sysschema|big,perfschema|nobig,encryption,innodb_zip|nobig,sysschema|nobig,audit_log,funcs_1,jp,rocksdb_sys_vars,query_rewrite_plugins,stress,rocksdb_stress,percona-pam-for-mysql,tokudb.add_index"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 5.7 with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="main|nobig,rocksdb_stress,binlog,perfschema,stress,connection_control,gcol,data_masking,innodb_undo,percona-pam-for-mysql"
    WORKER_2_MTR_SUITES="innodb_stress"
    WORKER_3_MTR_SUITES="group_replication|big"
    WORKER_4_MTR_SUITES="rpl|nobig,sys_vars,tokudb,tokudb.parts,innodb_gis,jp,gis"
    WORKER_5_MTR_SUITES="group_replication|nobig,rpl_encryption|nobig,innodb_zip,engines/funcs,json,tokudb.bugs,audit_null,tokudb.perfschema"
    WORKER_6_MTR_SUITES="innodb,rocksdb|big,rocksdb_rpl,funcs_1,rocksdb_sys_vars,test_service_sql_api,query_rewrite_plugins,engines/iuds,tokudb.add_index"
    WORKER_7_MTR_SUITES="rocksdb|nobig,main|big,parts,encryption,tokudb.rpl,tokudb.alter_table,funcs_2,federated"
    WORKER_8_MTR_SUITES="rpl|big,rpl_encryption|big,x,auth_sec,audit_log,innodb_fts,sysschema,binlog_encryption,opt_trace,query_response_time"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 5.7 with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="rpl|big,main|nobig,innodb|big,innodb_zip,encryption,connection_control,tokudb.bugs,tokudb.perfschema"
    WORKER_2_MTR_SUITES="innodb_stress,perfschema,stress,innodb_undo,test_service_sql_api,engines/iuds"
    WORKER_3_MTR_SUITES="group_replication|big,rpl_encryption|nobig,sysschema,tokudb,funcs_2,gcol,jp"
    WORKER_4_MTR_SUITES="rocksdb|big,innodb_gis,json,audit_log,tokudb.rpl,audit_null,data_masking,query_response_time"
    WORKER_5_MTR_SUITES="rocksdb|nobig,rocksdb_rpl,funcs_1,sys_vars,gis"
    WORKER_6_MTR_SUITES="rpl|nobig,rpl_encryption|big,rocksdb_stress,binlog_encryption,tokudb.parts,query_rewrite_plugins,tokudb.add_index"
    WORKER_7_MTR_SUITES="group_replication|nobig,main|big,binlog,x,auth_sec,tokudb.alter_table,percona-pam-for-mysql"
    WORKER_8_MTR_SUITES="innodb_fts,innodb|nobig,parts,engines/funcs,rocksdb_sys_vars,opt_trace,federated"
  fi
}

# usage: set_suites_ps80 <BUILD_TYPE>
function set_suites_ps80() {
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.0 with Valgrind"
    WORKER_1_MTR_SUITES="group_replication|big,engines/funcs|big,rocksdb|nobig,sysschema|big,sys_vars|big,parts|nobig,binlog_gtid|nobig,collations,innodb_zip|big,opt_trace|nobig,component_encryption_udf|nobig,data_masking,procfs"
    WORKER_2_MTR_SUITES="innodb|nobig,innodb_gis|nobig,funcs_2|nobig,test_services,interactive_utilities,percona-pam-for-mysql"
    WORKER_3_MTR_SUITES="innodb|big,engines/funcs|nobig,sys_vars|nobig,rocksdb_rpl|big,funcs_2|big,funcs_1|nobig,jp,gcol|big,audit_null,binlog_57_decryption,rocksdb_stress"
    WORKER_4_MTR_SUITES="main|nobig,component_keyring_file|big,rpl_nogtid|big,audit_log_filter,binlog|big,innodb_fts|big,binlog_nogtid|nobig,auth_sec|big,gcol|nobig,binlog_gtid|big,federated|nobig,service_udf_registration,opt_trace|big"
    WORKER_5_MTR_SUITES="main|big,x|big,stress|big,rpl_nogtid|nobig,innodb_fts|nobig,parts|big,innodb_gis|big,rocksdb_rpl|nobig,json,gis|nobig,service_sys_var_registration,connection_control"
    WORKER_6_MTR_SUITES="rpl|big,rpl|nobig,rocksdb|big,rpl_gtid|nobig,innodb_zip|nobig,auth_sec|nobig,component_encryption_udf|big,encryption|nobig,sysschema|nobig,test_service_sql_api,perfschema|big,service_status_var_registration"
    WORKER_7_MTR_SUITES="group_replication|nobig,clone|nobig,innodb_undo|nobig,binlog|nobig,federated|big,component_keyring_file|nobig,rpl_encryption,funcs_1|big,query_rewrite_plugins,rocksdb_sys_vars,secondary_engine,information_schema"
    WORKER_8_MTR_SUITES="rpl_gtid|big,clone|big,perfschema|nobig,innodb_undo|big,x|nobig,engines/iuds|big,binlog_nogtid|big,audit_log,component_masking_functions,engines/iuds|nobig,stress|nobig,encryption|big,gis|big"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.0 with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="rpl_nogtid|nobig,auth_sec,sys_vars,gcol,information_schema,json,binlog_57_decryption,percona-pam-for-mysql"
    WORKER_2_MTR_SUITES="group_replication|big,binlog_nogtid,audit_log_filter,funcs_2,query_rewrite_plugins,audit_null"
    WORKER_3_MTR_SUITES="rocksdb|big,rocksdb_rpl,rpl_nogtid|big,innodb_fts,audit_log,stress,collations,service_sys_var_registration,encryption"
    WORKER_4_MTR_SUITES="rocksdb|nobig,parts,innodb_gis,perfschema,innodb_undo,federated,jp,secondary_engine"
    WORKER_5_MTR_SUITES="component_encryption_udf,rpl_gtid,sysschema,x|big,rocksdb_stress,engines/iuds,test_services,data_masking,procfs"
    WORKER_6_MTR_SUITES="rpl|nobig,main|big,innodb|big,component_keyring_file,innodb_zip,binlog_gtid,interactive_utilities,opt_trace,service_udf_registration"
    WORKER_7_MTR_SUITES="rpl|big,innodb|nobig,clone,engines/funcs,test_service_sql_api,connection_control,rocksdb_sys_vars,service_status_var_registration"
    WORKER_8_MTR_SUITES="group_replication|nobig,main|nobig,binlog,x|nobig,funcs_1,component_masking_functions,rpl_encryption,gis"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.0 with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="rocksdb|nobig,innodb_undo|nobig,clone|nobig,x|nobig,component_keyring_file|nobig,federated,innodb_undo|big,collations,binlog_57_decryption,procfs"
    WORKER_2_MTR_SUITES="rocksdb|big,rpl_gtid|nobig,binlog|big,rocksdb_rpl|big,auth_sec|nobig,rpl_encryption,audit_log,service_sys_var_registration,secondary_engine"
    WORKER_3_MTR_SUITES="main|big,parts|big,gcol,rpl_nogtid|big,perfschema|big,engines/iuds,binlog_gtid|big,json,gis"
    WORKER_4_MTR_SUITES="group_replication|big,rpl_gtid|big,engines/funcs,component_encryption_udf,binlog_nogtid,x|big,rocksdb_stress,component_masking_functions,query_rewrite_plugins,jp"
    WORKER_5_MTR_SUITES="innodb|nobig,component_keyring_file|big,binlog|nobig,audit_log_filter,innodb_zip|big,clone|big,funcs_2,rocksdb_sys_vars,data_masking,service_udf_registration"
    WORKER_6_MTR_SUITES="main|nobig,group_replication|nobig,perfschema|nobig,rocksdb_rpl|nobig,sysschema|big,stress,binlog_gtid|nobig,innodb_gis|nobig,test_services,audit_null,service_status_var_registration"
    WORKER_7_MTR_SUITES="innodb|big,innodb_gis|big,rpl_nogtid|nobig,sys_vars,innodb_fts|nobig,auth_sec|big,parts|nobig,innodb_zip|nobig,opt_trace,interactive_utilities,percona-pam-for-mysql"
    WORKER_8_MTR_SUITES="rpl|nobig,rpl|big,innodb_fts|big,encryption,sysschema|nobig,information_schema,funcs_1|big,funcs_1|nobig,test_service_sql_api,connection_control"
  fi
}

# usage: set_suites_ps84 <BUILD_TYPE>
function set_suites_ps84() {
  # Comparing to 8.0 added in 8.4: component_audit_log_filter, percona, percona_innodb, component_js_lang
  # Comparing to 8.0 removed from 8.4: data_masking, binlog_57_decryption, audit_log_filter, audit_log
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.4 with Valgrind"
    # TO DO: Update, copied from 8.0 Valgrind + synchronized
    WORKER_1_MTR_SUITES="group_replication|big,engines/funcs|big,rocksdb|nobig,sysschema|big,sys_vars|big,parts|nobig,binlog_gtid|nobig,collations,innodb_zip|big,opt_trace|nobig,component_encryption_udf|nobig,procfs"
    WORKER_2_MTR_SUITES="innodb|nobig,innodb_gis|nobig,funcs_2|nobig,test_services,interactive_utilities,percona-pam-for-mysql"
    WORKER_3_MTR_SUITES="innodb|big,engines/funcs|nobig,sys_vars|nobig,rocksdb_rpl|big,funcs_2|big,funcs_1|nobig,jp,gcol|big,audit_null,rocksdb_stress"
    WORKER_4_MTR_SUITES="main|nobig,component_keyring_file|big,rpl_nogtid|big,component_audit_log_filter,binlog|big,innodb_fts|big,binlog_nogtid|nobig,auth_sec|big,gcol|nobig,binlog_gtid|big,federated|nobig,service_udf_registration,opt_trace|big"
    WORKER_5_MTR_SUITES="main|big,x|big,stress|big,rpl_nogtid|nobig,innodb_fts|nobig,parts|big,innodb_gis|big,rocksdb_rpl|nobig,json,gis|nobig,service_sys_var_registration,connection_control"
    WORKER_6_MTR_SUITES="rpl|big,rpl|nobig,rocksdb|big,rpl_gtid|nobig,innodb_zip|nobig,auth_sec|nobig,component_encryption_udf|big,encryption|nobig,sysschema|nobig,test_service_sql_api,perfschema|big,service_status_var_registration"
    WORKER_7_MTR_SUITES="group_replication|nobig,clone|nobig,innodb_undo|nobig,binlog|nobig,federated|big,component_keyring_file|nobig,rpl_encryption,funcs_1|big,query_rewrite_plugins,rocksdb_sys_vars,secondary_engine,information_schema"
    WORKER_8_MTR_SUITES="percona,percona_innodb,component_js_lang,rpl_gtid|big,clone|big,perfschema|nobig,innodb_undo|big,x|nobig,engines/iuds|big,binlog_nogtid|big,component_masking_functions,engines/iuds|nobig,stress|nobig,encryption|big,gis|big"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.4 with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="main|nobig,rpl_gtid,rpl_nogtid|big,innodb_gis,binlog_nogtid,binlog_gtid,json,information_schema,service_status_var_registration"
    WORKER_2_MTR_SUITES="group_replication|big,funcs_1,component_masking_functions,collations,gis,percona-pam-for-mysql"
    WORKER_3_MTR_SUITES="rocksdb|big,clone,x|nobig,rocksdb_stress,funcs_2,test_services,encryption,component_js_lang"
    WORKER_4_MTR_SUITES="rocksdb|nobig,innodb|nobig,sys_vars,component_keyring_file,innodb_zip,federated,rpl_encryption,secondary_engine"
    WORKER_5_MTR_SUITES="component_encryption_udf,main|big,parts,x|big,binlog,component_audit_log_filter,connection_control,rocksdb_sys_vars"
    WORKER_6_MTR_SUITES="innodb|big,percona_innodb,rocksdb_rpl,engines/funcs,innodb_fts,engines/iuds,service_sys_var_registration,audit_null,procfs"
    WORKER_7_MTR_SUITES="rpl|nobig,rpl_nogtid|nobig,auth_sec,perfschema,gcol,test_service_sql_api,interactive_utilities,jp,service_udf_registration"
    WORKER_8_MTR_SUITES="group_replication|nobig,rpl|big,sysschema,percona,innodb_undo,stress,query_rewrite_plugins,opt_trace"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.4 with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="innodb|nobig,rpl|big,perfschema|nobig,component_encryption_udf,clone|nobig,federated,rpl_encryption,collations,audit_null,component_js_lang"
    WORKER_2_MTR_SUITES="rocksdb|big,component_keyring_file|big,percona,x|nobig,component_keyring_file|nobig,engines/iuds,funcs_2,component_masking_functions,interactive_utilities,percona-pam-for-mysql"
    WORKER_3_MTR_SUITES="clone|big,percona_innodb|big,innodb_undo|big,component_audit_log_filter,rocksdb_rpl|big,innodb_zip|big,opt_trace,test_services,jp,service_udf_registration"
    WORKER_4_MTR_SUITES="group_replication|big,rpl_nogtid|nobig,innodb_undo|nobig,binlog|big,rpl_nogtid|big,perfschema|big,binlog_nogtid|nobig,innodb_zip|nobig,gis,information_schema"
    WORKER_5_MTR_SUITES="main|big,percona_innodb|nobig,rpl_gtid|nobig,gcol,sysschema|nobig,parts|nobig,rocksdb_stress,rocksdb_sys_vars,connection_control,procfs"
    WORKER_6_MTR_SUITES="main|nobig,group_replication|nobig,engines/funcs,rpl_gtid|big,innodb_fts|nobig,auth_sec|big,auth_sec|nobig,binlog_nogtid|big,service_sys_var_registration,service_status_var_registration"
    WORKER_7_MTR_SUITES="innodb|big,innodb_gis,innodb_fts|big,sys_vars,rocksdb_rpl|nobig,sysschema|big,stress,binlog_gtid|big,test_service_sql_api,secondary_engine"
    WORKER_8_MTR_SUITES="rpl|nobig,rocksdb|nobig,parts|big,binlog|nobig,encryption,funcs_1,x|big,binlog_gtid|nobig,json,query_rewrite_plugins"
  fi
}

# usage: set_suites_ps9x <BUILD_TYPE> <SERVER_VERSION>
function set_suites_ps9x() {
  local server_version="$2"
  # Comparing to 8.4 added in 9.4: component_connection_control, jdv

  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 9.x with Valgrind"
    # TO DO: Update, copied from 9.x Debug
    WORKER_1_MTR_SUITES="rocksdb|nobig,rpl|big,innodb_undo|nobig,percona,sysschema|nobig,x|big,binlog_nogtid|nobig,encryption,service_sys_var_registration,audit_null"
    WORKER_2_MTR_SUITES="clone|big,component_keyring_file|big,perfschema|nobig,rocksdb_rpl|nobig,clone|nobig,innodb_zip|big,rocksdb_stress,binlog_nogtid|big,query_rewrite_plugins,jdv"
    WORKER_3_MTR_SUITES="rocksdb|big,rpl_nogtid|nobig,percona_innodb|big,component_audit_log_filter,sysschema|big,parts|nobig,rpl_encryption,component_masking_functions,jp,information_schema"
    WORKER_4_MTR_SUITES="group_replication|big,percona_innodb|nobig,binlog|nobig,rpl_nogtid|big,rocksdb_rpl|big,federated,innodb_gis|nobig,collations,test_services,component_js_lang"
    WORKER_5_MTR_SUITES="main|big,innodb_gis|big,rpl_gtid|big,binlog|big,funcs_1,auth_sec|big,binlog_gtid|nobig,opt_trace,secondary_engine,service_status_var_registration"
    WORKER_6_MTR_SUITES="main|nobig,group_replication|nobig,parts|big,innodb_undo|big,innodb_fts|nobig,perfschema|big,engines/iuds,innodb_zip|nobig,rocksdb_sys_vars,component_connection_control,service_udf_registration"
    WORKER_7_MTR_SUITES="innodb|big,rpl|nobig,engines/funcs,sys_vars,x|nobig,component_keyring_file|nobig,stress,funcs_2,json,interactive_utilities,procfs"
    WORKER_8_MTR_SUITES="component_encryption_udf,innodb|nobig,innodb_fts|big,rpl_gtid|nobig,gcol,gis,auth_sec|nobig,binlog_gtid|big,test_service_sql_api,connection_control,percona-pam-for-mysql"
  elif [[ "$1" == "RelWithDebInfo" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 9.x with BUILD_TYPE=RelWithDebInfo"
    WORKER_1_MTR_SUITES="main|nobig,main|big,parts,percona,component_audit_log_filter,engines/iuds,component_connection_control,opt_trace,information_schema"
    WORKER_2_MTR_SUITES="component_encryption_udf,innodb_zip,gis,json,component_js_lang"
    WORKER_3_MTR_SUITES="group_replication|big,binlog,test_service_sql_api,component_masking_functions,connection_control,service_sys_var_registration,jdv"
    WORKER_4_MTR_SUITES="rocksdb|big,rpl_gtid,auth_sec,engines/funcs,innodb_undo,interactive_utilities,query_rewrite_plugins,audit_null"
    WORKER_5_MTR_SUITES="rocksdb|nobig,percona_innodb,sys_vars,perfschema,innodb_fts,binlog_gtid,rpl_encryption,service_udf_registration,procfs"
    WORKER_6_MTR_SUITES="rpl|nobig,rpl_nogtid|big,rocksdb_rpl,clone,innodb_gis,funcs_1,funcs_2,collations,jp,percona-pam-for-mysql"
    WORKER_7_MTR_SUITES="group_replication|nobig,rpl_nogtid|nobig,innodb|nobig,component_keyring_file,rocksdb_stress,gcol,stress,test_services,secondary_engine,service_status_var_registration"
    WORKER_8_MTR_SUITES="innodb|big,rpl|big,sysschema,x|big,x|nobig,binlog_nogtid,federated,rocksdb_sys_vars,encryption"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 9.x with BUILD_TYPE=Debug"
    WORKER_1_MTR_SUITES="rocksdb|nobig,rpl|big,innodb_undo|nobig,percona,sysschema|nobig,x|big,binlog_nogtid|nobig,encryption,service_sys_var_registration,audit_null"
    WORKER_2_MTR_SUITES="clone|big,component_keyring_file|big,perfschema|nobig,rocksdb_rpl|nobig,clone|nobig,innodb_zip|big,rocksdb_stress,binlog_nogtid|big,query_rewrite_plugins,jdv"
    WORKER_3_MTR_SUITES="rocksdb|big,rpl_nogtid|nobig,percona_innodb|big,component_audit_log_filter,sysschema|big,parts|nobig,rpl_encryption,component_masking_functions,jp,information_schema"
    WORKER_4_MTR_SUITES="group_replication|big,percona_innodb|nobig,binlog|nobig,rpl_nogtid|big,rocksdb_rpl|big,federated,innodb_gis|nobig,collations,test_services,component_js_lang"
    WORKER_5_MTR_SUITES="main|big,innodb_gis|big,rpl_gtid|big,binlog|big,funcs_1,auth_sec|big,binlog_gtid|nobig,opt_trace,secondary_engine,service_status_var_registration"
    WORKER_6_MTR_SUITES="main|nobig,group_replication|nobig,parts|big,innodb_undo|big,innodb_fts|nobig,perfschema|big,engines/iuds,innodb_zip|nobig,rocksdb_sys_vars,component_connection_control,service_udf_registration"
    WORKER_7_MTR_SUITES="innodb|big,rpl|nobig,engines/funcs,sys_vars,x|nobig,component_keyring_file|nobig,stress,funcs_2,json,interactive_utilities,procfs"
    WORKER_8_MTR_SUITES="component_encryption_udf,innodb|nobig,innodb_fts|big,rpl_gtid|nobig,gcol,gis,auth_sec|nobig,binlog_gtid|big,test_service_sql_api,connection_control,percona-pam-for-mysql"
  fi

  if is_version_equal_or_bigger "$server_version" "9.4"; then
    :
  fi
}

# usage: set_suites <BUILD_TYPE> <SERVER_VERSION>
function set_suites() {
  local build_type="$1"
  local server_version="$2"

  case "$server_version" in
    5.7.44-eol-*|5.7.44-post-eol-*)
      set_suites_mysql57 "$build_type"
      ;;
    5.7.*-*)
      set_suites_ps57 "$build_type"
      ;;
    8.0.*-*)
      set_suites_ps80 "$build_type"
      ;;
    8.0.*)
      set_suites_mysql80 "$build_type"
      ;;
    8.4.*-*)
      set_suites_ps84 "$build_type"
      ;;
    #8.4.*)
    #  set_suites_mysql84 "$build_type"
    #  ;;
    9.*-*)
      set_suites_ps9x "$build_type" "$server_version"
      ;;
    #9.*)
    #  set_suites_mysql9x "$build_type" "$server_version"
    #  ;;
    *)
      echo "Unsupported server version: $server_version"
      return 1
      ;;
  esac
}
