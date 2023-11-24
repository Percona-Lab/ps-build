LIST = param-parallel-mtr.yml pipeline-parallel-mtr.yml
targets =$(patsubst %.yml, %, $(LIST))
test-targets =$(addprefix percona-server-8.x-, $(filter-out view, $(targets)))

all: $(targets)

$(targets):
	jenkins-jobs --server ps.cd update $@.yml
	@echo

test: $(test-targets)

$(test-targets):
	@mkdir -p result/$@
	jenkins-jobs --server ps.cd test $(patsubst percona-server-8.x-%,%, $@).yml -o result/$@
	@mv result/$@/percona-server-* result/$@/config.xml
	@echo
