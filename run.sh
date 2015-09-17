#!/bin/sh
LD_LIBRARY_PATH=./hyperic-sigar-1.6.4/sigar-bin/lib/ java -XX:+UseConcMarkSweepGC -Xmx8G -cp ./target/storm-micro-perf-1.0.0.jar:./target/dependency/\*:./hyperic-sigar-1.6.4/sigar-bin/lib/sigar.jar testing.Main "$@"
