package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class QStormWC implements Test {
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
    private ArrayList<Q> _toSplit;
    private ArrayList<Q> _toCount;
    private LatencyEstimation _lat;
    private int _sendThreads;
    private int _splitThreads;
    private int _countThreads;
    private volatile boolean throttle = false;

    private class CB implements BpCb {
        public void highWaterMark() {
            check();
        }

        public void check() {
            boolean tmp = false;
            for (Q q: _toSplit) {
                tmp = tmp || q.isThrottled();
            }
            for (Q q: _toCount) {
                tmp = tmp || q.isThrottled();
            }
        }

        public void lowWaterMark() {
            check();
        }
    }

    public QStormWC(int sendThreads, int splitThreads, int countThreads) {
        _sendThreads = sendThreads;
        _splitThreads = splitThreads;
        _countThreads = countThreads;
    }

    @Override
    public String description() {
        return "Word Count one event at a time from "+_sendThreads+" to split:"+_splitThreads+" and count:"+_countThreads+" threads";
    }

    public class Sender extends Thread {
        private CountDownLatch _doneSignal;
        private CountDownLatch _startSignal;
        private ArrayList<Q> _q;
        private LatencyEstimation _lat;
        private int _iterations;
 
        public Sender(CountDownLatch startSignal, CountDownLatch doneSignal, ArrayList<Q> output, LatencyEstimation lat, int iterations) {
            _doneSignal = doneSignal;
            _startSignal = startSignal;
            _q = output;
            _lat = lat;
            _iterations = iterations;
        }

        @Override
        public void run() {
            try {
                final GenSentences gen = new GenSentences();
                final int iterations = _iterations;
                _startSignal.await();
                for (int i = 0; i < iterations; i++) {
                    while (throttle) {
                        Thread.sleep(1);
                    }
                    long start = _lat.getStart(i);
                    _q.get(i % _q.size()).publish(new WCMessage(i, start, gen.getNext(), true, false));
                }

                for (Q q: _q) {
                    q.publish(new WCMessage(0, 0l, null, false, true));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                _doneSignal.countDown();
            }
        }
    }

    private static class Splitter extends EventThread<WCMessage> {
        private ArrayList<Q> _output;

        public Splitter(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, ArrayList<Q> output, int senders) {
            super(startSignal, doneSignal, input, senders);
            _output = output;
        }

        @Override
        public void onEvent(WCMessage data) throws Exception {
            if (data.allDone) {
                return;
            }
            String [] words = data.word.split(" ");
            for (int j = 0; j < words.length; j++) {
                _output.get(Math.abs(words[j].hashCode()) % _output.size()).publish(new WCMessage(data.iteration, data.start, words[j], j == words.length-1, false));
            }
        }

        @Override
        public void onAllDone() {
            for (Q q: _output) {
                q.publish(new WCMessage(0, 0l, null, false, true));
            }
        }
    }

    private static class Counter extends EventThread<WCMessage> {
        private HashMap<String, Integer> _subCounts;
        private LatencyEstimation _lat;

        public Counter(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, HashMap<String, Integer> subCounts, LatencyEstimation lat, int senders) {
            super(startSignal, doneSignal, input, senders);
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
        CB cb = new CB();
        _counts = new ArrayList<HashMap<String, Integer>>();
        _doneSignal = new CountDownLatch(_countThreads + _sendThreads + _splitThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _toSplit = new ArrayList<Q>();
        _toCount = new ArrayList<Q>();
        _threads = new ArrayList<Thread>();

        for (int i = 0; i < _countThreads; i++) {
            HashMap<String, Integer> subCounts = new HashMap<String,Integer>();
            _counts.add(subCounts);
            Q q = Q.make("Q_COUNT_"+i, conf);
            q.register(cb);
            _toCount.add(q);
            Counter c = new Counter(_startSignal, _doneSignal, q, subCounts, _lat, _splitThreads);
            _threads.add(c);
            c.start();
        }

        for (int i = 0; i < _splitThreads; i++) {
            Q q = Q.make("Q_SPLIT_"+i, conf);
            q.register(cb);
            _toSplit.add(q);
            Splitter s = new Splitter(_startSignal, _doneSignal, q, _toCount, _sendThreads);
            _threads.add(s);
            s.start();
        }

        for (int i = 0; i < _sendThreads; i++) {
            Sender s = new Sender(_startSignal, _doneSignal, _toSplit, _lat, (iterations/_sendThreads) + 1);
            _threads.add(s);
            s.start();
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
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

        for (Q q: _toSplit) {
            q.close();
        }

        for (Q q: _toCount) {
            q.close();
        }
    }
}
