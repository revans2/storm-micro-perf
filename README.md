# storm-micro-perf
Micro performance benchmarks for storm

First install the latest version of strom from the master branch 0.11.0-SNAPSHOT

Then install a shaded version of the latest disruptor code.
```
cd shaded-disruptor && mvn clean install
```

Finally you can build and run the help command to see the tests
```
mvn clean package
./run.sh -h
```
