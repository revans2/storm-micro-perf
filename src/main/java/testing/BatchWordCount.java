package testing;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BatchWordCount implements Test {
    private final int _numThreads;
    private ArrayList<HashMap<String, Integer>> _counts;
    private ArrayList<Thread> _threads;
    private volatile int _numIterationsPerThread = 0;

    private CountDownLatch _startSignal;
    private CountDownLatch _doneSignal;

    public BatchWordCount(int numThreads) {
        _numThreads = numThreads;
    }

    @Override
    public String description() {
        return "Word Count in parallel using "+_numThreads+" threads";
    }

    @Override
    public void prepare(final LatencyEstimation lat, Map<String, String> conf, int iterations) {
        _counts = new ArrayList<HashMap<String, Integer>>();
        _startSignal = new CountDownLatch(1);
        _doneSignal = new CountDownLatch(_numThreads);
        _threads = new ArrayList<Thread>();
        for (int i = 0; i < _numThreads; i++) {
            final HashMap<String, Integer> subCounts = new HashMap<String,Integer>();
            _counts.add(subCounts);
            Thread t = new Thread() {
                GenSentences _gen = new GenSentences();

                @Override
                public void run() {
                    try {
                        _startSignal.await();
                        GenSentences gen = _gen;
                        int iterations = _numIterationsPerThread;
                        for (int i = 0; i < iterations; i++) {
                            long start = lat.getStart(i);
                            for (String word: gen.getNext().split(" ")) {
                                Integer val = subCounts.get(word);
                                if (val == null) {
                                    val = 0;
                                }
                                val++;
                                subCounts.put(word, val);
                            }
                            lat.recordLatency(i, start);
                        }
                    } catch (Exception e) {
                        //Ignored
                    } finally {
                        _doneSignal.countDown();
                    }
                }
            };
            t.start();
            _threads.add(t);
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        _numIterationsPerThread = (iterations/_numThreads) + 1;
        _startSignal.countDown();
        _doneSignal.await();
        HashMap<String, Integer> result = new HashMap<String,Integer>();
        if (_counts.size() == 1) {
            result = _counts.get(0);
        } else {
            for (HashMap<String,Integer> part: _counts) {
                for (Map.Entry<String,Integer> entry: part.entrySet()) {
                    Integer val = result.get(entry.getKey());
                    if (val == null) {
                        val = 0;
                    }
                    val += entry.getValue();
                    result.put(entry.getKey(), val);
                }
            }
        }
    }

    @Override
    public void cleanup() throws Exception {
        for (Thread t: _threads) {
            if (t.isAlive()) {
                t.interrupt();
                t.join();
            }
        }
    }
}
