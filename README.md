# Docker way
```
git clone https://github.com/Percona-Lab/ps-build
cd ps-build
./local/checkout

./docker/run-build centos:6
./docker/run-test centos:6
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
