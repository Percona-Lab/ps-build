LIST = $(wildcard *.yml)
targets =$(patsubst %.yml, %, $(LIST))
test-targets =$(addprefix percona-server-5.5-, $(filter-out view, $(targets)))

all: $(targets)

$(targets):
	jenkins-jobs update $@.yml
	@echo

test: $(test-targets)

$(test-targets):
	@mkdir -p result/$@
	jenkins-jobs test $(patsubst percona-server-5.5-%,%, $@).yml -o result/$@
	@mv result/$@/percona-server-* result/$@/config.xml
	@echo
