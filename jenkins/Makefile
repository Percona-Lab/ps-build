LIST = $(wildcard *.yml)
targets =$(patsubst %.yml, %, $(LIST))
test-targets =$(addprefix percona-server-8.0-, $(filter-out view, $(targets)))

all: $(targets)

$(targets):
	jenkins-jobs --server ps.cd update $@.yml
	@echo

test: $(test-targets)

$(test-targets):
	@mkdir -p result/$@
	jenkins-jobs --server ps.cd test $(patsubst percona-server-8.0-%,%, $@).yml -o result/$@
	@mv result/$@/percona-server-* result/$@/config.xml
	@echo
