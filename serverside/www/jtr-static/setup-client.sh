#!/bin/bash
hashdl=currenthash.txt
# You can use the included build-john.sh to build this archive
johndl=john-amd64-opencl-11.0.tar.gz
javadl=java8-jre-corretto-amd64.tar.gz
# Run mvn package in the client directory to build this jar
clientdl=client-1.0-SNAPSHOT-shaded.jar

workdir=/root/jtr-distributed
staticurl=https://CHANGEME.com/jtr-static

cd /root

if [[ ! -f "$workdir/isfinished-$hashdl.touch" ]]
then
	if [[ -d "$workdir" ]]
	then
		rm -rf $workdir
	fi
	mkdir $workdir
	cd $workdir
	
	apt-get update
	apt-get -y install ocl-icd-libopencl1 libgomp1 wget tmux
	mkdir -p /etc/OpenCL/vendors && echo "libnvidia-opencl.so.1" > /etc/OpenCL/vendors/nvidia.icd
	
	echo Downloading $johndl
	wget -4 $staticurl/$johndl 2>&1
	tar xf $johndl
	rm $johndl

	echo Downloading $javadl
	wget -4 $staticurl/$javadl 2>&1
	tar xf $javadl
	rm $javadl
	
	echo Downloading $clientdl
	wget -4 -O $clientdl $staticurl/$clientdl?fresh=true 2>&1
	
	echo Downloading $hashdl
	wget -4 -O $hashdl $staticurl/$hashdl?fresh=true 2>&1
	echo Downloads finished.
	
	touch $workdir/isfinished-$hashdl.touch
fi

cd $workdir

NUM_GPUS=$(john/run/john --list=opencl-devices | grep "^    Device #" | wc -l)

echo Name: $VAST_CONTAINERLABEL
echo GPUS: $NUM_GPUS

echo Setting up tmux
tmux new-session -d -s ssh_tmux
for i in $(seq 1 $NUM_GPUS)
do
	echo Starting instance $i
	tmux new-window -n gpu-$i
	tmux send-keys -t gpu-$i "jre/bin/java -jar $clientdl ${VAST_CONTAINERLABEL}-gpu${i} CHANGEME-API-KEY-HERE $workdir/john/run $i $workdir/$hashdl -lws=64 -gws=1000000 | tee -a gpu-$i.log" C-m
done