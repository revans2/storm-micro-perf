package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class QWC implements Test {
    public static class WCMessage extends TestData {
        String word;
        boolean lastWord;

        public WCMessage(int iteration, long start, String word, boolean lastWord, boolean allDone) {
            super(iteration, start, allDone);
            this.word = word;
            this.lastWord = lastWord;
        }
    }

    private ArrayList<Thread> _threads;
    private ArrayList<HashMap<String, Integer>> _counts;
    private CountDownLatch _doneSignal;
    private CountDownLatch _startSignal;
    private ArrayList<Q> _q;
    private LatencyEstimation _lat;
    private int _numThreads;

    public QWC(int threads) {
        _numThreads = threads;
    }

    @Override
    public String description() {
        return "Word Count one event at a time using "+_numThreads+" threads";
    }

    private static class Counter extends EventThread<WCMessage> {
        private HashMap<String, Integer> _subCounts;
        private LatencyEstimation _lat;

        public Counter(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, HashMap<String, Integer> subCounts, LatencyEstimation lat) {
            super(startSignal, doneSignal, input, 1);
            _subCounts = subCounts;
            _lat = lat;
        }

        @Override
        public void onEvent(WCMessage data) throws Exception {
            if (data.allDone) {
                return;
            }
            Integer val = _subCounts.get(data.word);
            if (val == null) {
                val = 0;
            }
            val++;
            _subCounts.put(data.word, val);
            if (data.lastWord) {
                _lat.recordLatency(data.iteration, data.start);
            }
        }
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
        _counts = new ArrayList<HashMap<String, Integer>>();
        _doneSignal = new CountDownLatch(_numThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>(_numThreads);
        _threads = new ArrayList<Thread>(_numThreads);

        for (int i = 0; i < _numThreads; i++) {
            HashMap<String, Integer> subCounts = new HashMap<String,Integer>();
            _counts.add(subCounts);
            Q q = Q.make("Q_"+i, conf);
            _q.add(q);
            Counter c = new Counter(_startSignal, _doneSignal, q, subCounts, _lat);
            _threads.add(c);
            c.start();
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        _startSignal.countDown();
        GenSentences gen = new GenSentences();
        for (int i = 0; i < iterations; i++) {
            long start = _lat.getStart(i);
            String [] words = gen.getNext().split(" ");
            for (int j = 0; j < words.length; j++) {
                _q.get(Math.abs(words[j].hashCode()) % _q.size()).publish(new WCMessage(i, start, words[j], j == words.length-1, false));
            }
        }

        for (Q q: _q) {
            q.publish(new WCMessage(0, 0l, null, false, true));
        }

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
