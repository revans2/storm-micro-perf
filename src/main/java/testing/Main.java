package testing;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;

public class Main {
    private static final int NUM_CORES = Runtime.getRuntime().availableProcessors();

    public static Test NOOP = new Test(){
        private LatencyEstimation _lat;

        @Override
        public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
            _lat = lat;
        }

        @Override
        public void runTest(int iterations) {
            LatencyEstimation lat = _lat;
            for (int i = 0; i < iterations; i++) {
                lat.recordLatency(i, lat.getStart(i));
            }
        }

        @Override
        public void cleanup() {
            //Empty
        }

        @Override
        public String description() {
            return "Loops N times and does nothing.";
        }
    };


    public static void main(String [] args) throws Exception {
        Map<String, Test> tests = new HashMap<String, Test>();
        tests.put("NOOP_1", NOOP);
        tests.put("GEN_SENTENCES_1", GenSentences.TEST);
        for (int threads = 1; threads <= NUM_CORES*2; threads *= 2) {
            String t = String.format("%02d", threads);
            tests.put("BATCH_WC_"+t, new BatchWordCount(threads));
            tests.put("Q_PIPE_"+t, new QPipeline(threads));
            tests.put("Q_WC_"+t, new QWC(threads));
            tests.put("Q_RR_"+t, new QRoundRobin(threads));
            tests.put("PSUDO_Q_RR_"+t, new PsudoQRoundRobin(threads));
        }
        tests.put("R_MEM_01k", new RandomMemory(1024));
        tests.put("R_MEM_01m", new RandomMemory(1024 * 1024));
        tests.put("R_MEM_10m", new RandomMemory(1024 * 1024 * 10));
        tests.put("R_MEM_50m", new RandomMemory(1024 * 1024 * 50));
        tests.put("S_MEM_01k", new SeqMemory(1024));
        tests.put("S_MEM_01m", new SeqMemory(1024 * 1024));
        tests.put("S_MEM_10m", new SeqMemory(1024 * 1024 * 10));
        tests.put("S_MEM_50m", new SeqMemory(1024 * 1024 * 50));

        Options options = new Options();
        options.addOption("h", "help", false, "print help message");
        options.addOption("l", "latency", false, "Enable latency measurements");
        options.addOption("i", "iterations", true, "Number of iterations within each test");
        options.addOption("t", "times", true, "Number of times to run each test");
        options.addOption(OptionBuilder.withArgName("property=value")
                                .hasArgs(2)
                                .withValueSeparator()
                                .withDescription("use value for given property")
                                .create("D"));
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        if (cmd.hasOption("h")) {
            HelpFormatter formatter = new HelpFormatter();
            ArrayList<String> al = new ArrayList<String>(tests.keySet());
            Collections.sort(al);
            StringBuilder sb = new StringBuilder();
            sb.append("\nPossible Tests:\n");
            for (String testName: al) {
                sb.append(testName).append(" - ").append(tests.get(testName).description()).append("\n");
            }
            formatter.printHelp("test [<options>] [TEST_NAME_RE*]", "", options, sb.toString());
            return;
        }

        boolean trackLatency = cmd.hasOption("l");
        int iterations = Integer.valueOf(cmd.getOptionValue("i", "1000000"));
        int times = Integer.valueOf(cmd.getOptionValue("t", "5"));
        Map<String, String> conf = new HashMap<String, String>();
        Properties props = cmd.getOptionProperties("D");
        for (String s: props.stringPropertyNames()) {
            conf.put(s, props.getProperty(s));
        }

        int sampleGoal = Math.min(100000, iterations);
        double pct = ((double)sampleGoal)/iterations;

        Set<String> testsToRun = tests.keySet();
        List<String> testArgs = cmd.getArgList();
        if (!testArgs.isEmpty()) {
            testsToRun = new HashSet<String>();
            for (String t: testArgs) {
                if (!tests.containsKey(t)) {
                    boolean matched = false;
                    for (String testName: tests.keySet()) {
                        if (testName.matches(t)) {
                            testsToRun.add(testName);
                            matched = true;
                        }
                    }
                    if (!matched) {
                        throw new Exception(t + " is not a valid test case "+tests.keySet());
                    }
                } else {
                    testsToRun.add(t);
                }
            }
        }
        ArrayList<String> testNames = new ArrayList<String>(testsToRun);
        Collections.sort(testNames);

        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans(); 
        CompilationMXBean comp = ManagementFactory.getCompilationMXBean();
        final boolean compSupported = comp != null && comp.isCompilationTimeMonitoringSupported();

        System.out.println("COMMAND LINE: "+ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("Conf: "+conf);
        System.out.println();
        System.out.printf("%15s\t%5s\t%15s\t%17s\t%17s\t%4s\t%5s","Test Name", "Num", "Iterations", "Time ns", "Throughput", "GC", "GC ms");
        if (compSupported) {
            System.out.printf("\t%6s", "JIT ms");
        }
        if (trackLatency) {
            System.out.printf("\t%15s\t%15s\t%15s\t%15s\t%15s", "min latency", "latency 50th", "latency 90th", "latency 99th", "max latency");
        }
        System.out.println();
        for (int i = 0; i < times; i++) {
            for (String testName: testNames) {
                Test test = tests.get(testName);
                LatencyEstimation latency = trackLatency? new LatencyEstimationImpl(pct, iterations) : new NoopLatencyEstimation();
                test.prepare(latency, conf, iterations);
                System.gc();
                long startGcCount = 0;
                long startGcTime = 0;
                for (GarbageCollectorMXBean gc: gcs) {
                    startGcCount += gc.getCollectionCount();
                    startGcTime += gc.getCollectionTime();
                }
                long compStart = 0;
                if (compSupported) {
                    compStart = comp.getTotalCompilationTime();
                }
                long start = System.nanoTime();
                test.runTest(iterations);
                long end = System.nanoTime();
                long endGcCount = 0;
                long endGcTime = 0;
                for (GarbageCollectorMXBean gc: gcs) {
                    endGcCount += gc.getCollectionCount();
                    endGcTime += gc.getCollectionTime();
                }
                long compEnd = 0;
                if (compSupported) {
                    compEnd = comp.getTotalCompilationTime();
                }
                test.cleanup();
                System.out.printf("%15s\t%,5d\t%,15d\t%,17d\t%,17.0f\t%,4d\t%,5d",testName,i+1, iterations, end - start,((double)iterations)/(end - start) * 1000000000.0,endGcCount-startGcCount, endGcTime-startGcTime);
                if (compSupported) {
                    System.out.printf("\t%,6d", compEnd-compStart);
                }
                if (trackLatency) {
                    System.out.printf("\t%,15.0f\t%,15.0f\t%,15.0f\t%,15.0f\t%,15.0f", latency.getMin(), latency.get50th(), latency.get90th(), latency.get99th(), latency.getMax());
                }
                System.out.println();
            }
            System.out.println();
        }
    }
}
