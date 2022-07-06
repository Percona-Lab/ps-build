# Docker way
```
git clone https://github.com/Percona-Lab/ps-build
cd ps-build
./local/checkout

./docker/run-build centos:7
./docker/run-test centos:7
```

## Docker debug
```
git clone https://github.com/Percona-Lab/ps-build
cd ps-build
./local/checkout

./docker/run-build-debug centos:7
```

# Local way
```
git clone https://github.com/Percona-Lab/ps-build
cd ps-build
./local/checkout

sudo ./docker/install-deps
./local/build-binary
./local/test-binary
```
