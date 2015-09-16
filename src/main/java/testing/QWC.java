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
    private int _sendThreads;
    private int _recvThreads;

    public QWC(int sendThreads, int recvThreads) {
        _sendThreads = sendThreads;
        _recvThreads = recvThreads;
    }

    @Override
    public String description() {
        return "Word Count one event at a time from "+_sendThreads+" to "+_recvThreads+" threads";
    }

    public static class Sender extends Thread {
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
                    long start = _lat.getStart(i);
                    String [] words = gen.getNext().split(" ");
                    for (int j = 0; j < words.length; j++) {
                        _q.get(Math.abs(words[j].hashCode()) % _q.size()).publish(new WCMessage(i, start, words[j], j == words.length-1, false));
                    }
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
        _counts = new ArrayList<HashMap<String, Integer>>();
        _doneSignal = new CountDownLatch(_recvThreads + _sendThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>();
        _threads = new ArrayList<Thread>();

        for (int i = 0; i < _recvThreads; i++) {
            HashMap<String, Integer> subCounts = new HashMap<String,Integer>();
            _counts.add(subCounts);
            Q q = Q.make("Q_"+i, conf);
            _q.add(q);
            Counter c = new Counter(_startSignal, _doneSignal, q, subCounts, _lat, _sendThreads);
            _threads.add(c);
            c.start();
        }

        for (int i = 0; i < _sendThreads; i++) {
            Sender s = new Sender(_startSignal, _doneSignal, _q, _lat, (iterations/_sendThreads) + 1);
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

        for (Q q: _q) {
            q.close();
        }
    }
}
