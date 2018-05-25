LIST = $(wildcard *.yml)
targets =$(patsubst %.yml, %, $(LIST))

all: $(targets)

$(targets):
	jenkins-jobs update $@.yml
	@echo
