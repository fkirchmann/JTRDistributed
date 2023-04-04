#!/bin/bash
apt-get update
apt-get -y install git build-essential libssl-dev zlib1g-dev yasm libgmp-dev libpcap-dev libbz2-dev nvidia-opencl-dev
git clone https://github.com/openwall/john -b bleeding-jumbo john
cd john/src
./configure && make -s clean && make -sj4

mkdir -p /etc/OpenCL/vendors && echo "libnvidia-opencl.so.1" > /etc/OpenCL/vendors/nvidia.icd

tar -czvf john.tar.gz john/run
