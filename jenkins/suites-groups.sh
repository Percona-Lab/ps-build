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
    WORKER_1_MTR_SUITES="group_replication|big,binlog|nobig,sysschema|big,engines/funcs|nobig,innodb_zip|big,parts|nobig,auth_sec,funcs_1,gcol,innodb_gis|nobig,query_rewrite_plugins,query_response_time,rocksdb_stress,percona-pam-for-mysql"
    WORKER_2_MTR_SUITES="main|nobig"
    WORKER_3_MTR_SUITES="innodb|nobig"
    WORKER_4_MTR_SUITES="rocksdb|big"
    WORKER_5_MTR_SUITES="rocksdb|nobig"
    WORKER_6_MTR_SUITES="rpl|nobig,innodb|big,perfschema|nobig,x,engines/funcs|big,perfschema|big,gis,engines/iuds,tokudb.rpl,rocksdb_sys_vars,tokudb.parts,tokudb.bugs,innodb_undo,data_masking"
    WORKER_7_MTR_SUITES="group_replication|nobig,rpl|big,rpl_encryption|nobig,encryption,innodb_zip|nobig,innodb_gis|big,audit_log,rpl_encryption|big,json,funcs_2,tokudb,opt_trace,connection_control,tokudb.alter_table,tokudb.add_index,tokudb.perfschema"
    WORKER_8_MTR_SUITES="main|big,innodb_fts|nobig,sys_vars,rocksdb_rpl,parts|big,binlog|big,sysschema|nobig,innodb_fts|big,binlog_encryption,jp,innodb_stress,test_service_sql_api,stress,audit_null,federated"
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
    WORKER_1_MTR_SUITES="main|big,rpl_nogtid|nobig,x|nobig,auth_sec|big,innodb_gis,rpl_gtid|big,connection_control,jp,rocksdb_sys_vars"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rocksdb|nobig,innodb_fts,x|big,component_masking_functions,funcs_2,test_service_sql_api,service_sys_var_registration,opt_trace"
    WORKER_4_MTR_SUITES="main|nobig,engines/funcs,rpl_nogtid|big,perfschema,binlog_nogtid,binlog_gtid,rpl_encryption,gis,data_masking"
    WORKER_5_MTR_SUITES="rpl|nobig,rpl_gtid|nobig,clone,component_keyring_file,innodb_zip,funcs_1,federated,information_schema,audit_null,service_udf_registration,procfs,percona-pam-for-mysql"
    WORKER_6_MTR_SUITES="group_replication|nobig,innodb|big,sys_vars,parts|nobig,sysschema,stress,audit_log,binlog_57_decryption,secondary_engine"
    WORKER_7_MTR_SUITES="innodb|nobig,component_encryption_udf,rocksdb_rpl,binlog,innodb_undo,rocksdb_stress,gcol,test_services,json,service_status_var_registration,encryption"
    WORKER_8_MTR_SUITES="rpl|big,rocksdb|big,parts|big,auth_sec|nobig,audit_log_filter,engines/iuds,collations,query_rewrite_plugins,interactive_utilities"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.0 with BUILD_TYPE=Debug"
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

# usage: set_suites_ps84 <BUILD_TYPE>
function set_suites_ps84() {
  # Comparing to 8.0 added in 8.4: component_audit_log_filter, percona, percona_innodb, component_js_lang
  # Comparing to 8.0 removed from 8.4: data_masking, binlog_57_decryption, audit_log_filter, audit_log
  if [[ "$1" == "Valgrind" ]]; then
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.4 with Valgrind"
    # TO DO: Update, copied from 8.0 Valgrind
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
    WORKER_1_MTR_SUITES="main|big,engines/funcs,parts|big,rpl_nogtid|big,component_keyring_file,innodb_undo,stress,test_service_sql_api,federated,secondary_engine,encryption"
    WORKER_2_MTR_SUITES="group_replication|big"
    WORKER_3_MTR_SUITES="rocksdb|nobig,percona_innodb,auth_sec|nobig,perfschema,rocksdb_stress,rpl_encryption,gis,opt_trace"
    WORKER_4_MTR_SUITES="group_replication|nobig,component_encryption_udf,percona,innodb_gis,gcol,funcs_2,rocksdb_sys_vars,service_status_var_registration"
    WORKER_5_MTR_SUITES="rpl|nobig,rpl_gtid,auth_sec|big,parts|nobig,binlog_nogtid,jp,json,information_schema,audit_null"
    WORKER_6_MTR_SUITES="main|nobig,rpl_nogtid|nobig,binlog,component_audit_log_filter,engines/iuds,binlog_gtid,query_rewrite_plugins,service_udf_registration,procfs,percona-pam-for-mysql,component_js_lang"
    WORKER_7_MTR_SUITES="rpl|big,innodb|big,x|nobig,clone,x|big,innodb_zip,funcs_1,connection_control,service_sys_var_registration,interactive_utilities"
    WORKER_8_MTR_SUITES="innodb|nobig,rocksdb|big,rocksdb_rpl,sys_vars,innodb_fts,sysschema,component_masking_functions,test_services,collations"
  else # Debug (and everything different from "RelWithDebInfo" and "Valgrind")
    # Unit tests, KEYRING_VAULT tests, ps_protocol, ci_fs will be executed by worker 1
    echo "Setting WORKER_x_MTR_SUITES for PS 8.4 with BUILD_TYPE=Debug"
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

# usage: set_suites_ps9x <BUILD_TYPE> <SERVER_VERSION>
function set_suites_ps9x() {
  local server_version="$2"
  # Comparing to 8.4 added in 9.4: component_connection_control, jdv

  if [[ "$1" == "RelWithDebInfo" ]]; then
    echo "Setting WORKER_x_MTR_SUITES for PS 9.x with BUILD_TYPE=RelWithDebInfo"
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
    echo "Setting WORKER_x_MTR_SUITES for PS 9.x with BUILD_TYPE=Debug"
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
  local build_type="$1"
  local server_version="$2"

  case "$server_version" in
    5.7.44-post-eol-*)
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

# usage: get_default_suites_57 <path_to_mysql-test-run.pl>
function get_default_suites_57() {
    local input_file="$1"
    local suites=""
    local capturing=0

    while read -r line; do
        # Start capturing after DEFAULT_SUITES assignment
        if [[ "$line" == *"DEFAULT_SUITES"* && "$capturing" == "0" ]]; then
            capturing=1
            # Remove everything before the first = including spaces
            line="${line#*=}"
        fi

        if [[ "$capturing" == "1" ]]; then
            # Remove leading dot if it's used for Perl concatenation
            line="${line#.}"

            # Remove quotes and spaces only, keep internal dots
            line=$(echo "$line" | tr -d '" ')

            # Append to suites
            suites+="$line"

            # Stop capturing when line ends with semicolon
            if [[ "$line" == *";"* ]]; then
                capturing=0
                break
            fi
        fi
    done < "$input_file"

    # Remove trailing semicolon or comma
    suites="${suites%;}"
    suites="${suites%,}"

    echo "$suites"
}

# usage: get_default_suites_80 <path_to_mysql-test-run.pl>
function get_default_suites_80() {
  local input_file="$1"
  local all_suites=""
  local capturing=0

  while read -r line; do
    if [[ "${capturing}" == "1" ]]; then
      if [[ "${line}" == *");"* ]]; then
        capturing=0
        break
      else
        all_suites+="${line},"
      fi
    fi

    if [[ "${line}" == *"DEFAULT_SUITES = qw"* ]]; then
      capturing=1
    fi
  done < "${input_file}"

  # Trim trailing comma if present
  all_suites="${all_suites%,}"

  echo "${all_suites}"
}

# usage: check_suites <path_to_mysql-test-run.pl> <SERVER_VERSION>
function check_suites() {
  local input_file=${1:-./mysql-test-run.pl}
  local server_version="$2"

  if [[ ! -f ${input_file} ]]
    then
    echo "${input_file} file does not exist on your filesystem."
    return 1
  fi

  echo "Checking if suites list is consistent with the one specified in mysql-test-run.pl"
  echo

  local all_suites_1=,${WORKER_1_MTR_SUITES},${WORKER_2_MTR_SUITES},${WORKER_3_MTR_SUITES},${WORKER_4_MTR_SUITES},${WORKER_5_MTR_SUITES},${WORKER_6_MTR_SUITES},${WORKER_7_MTR_SUITES},${WORKER_8_MTR_SUITES},
  local all_suites_2

  case "$server_version" in
    5.7.*)
      all_suites_2=$(get_default_suites_57 "${input_file}")
      ;;
    *)
      all_suites_2=$(get_default_suites_80 "${input_file}")
      ;;
  esac

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
