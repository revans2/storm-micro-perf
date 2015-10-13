package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class QRoundRobin implements Test {
    private ArrayList<Thread> _threads;
    private CountDownLatch _doneSignal;
    private CountDownLatch _startSignal;
    private ArrayList<Q> _q;
    private LatencyEstimation _lat;
    private int _numSendThreads;
    private int _numRecvThreads;
    private int _batchInsert;
    private volatile boolean throttle = false;

    private class CB implements BpCb {
        public void highWaterMark() {
            check();
        }

        public void check() {
            boolean tmp = false;
            for (Q q: _q) {
                tmp = tmp || q.isThrottled();
            }
        }

        public void lowWaterMark() {
            check();
        }
    }

    public class Sender extends Thread {
        private CountDownLatch _doneSignal;
        private CountDownLatch _startSignal;
        private ArrayList<Q> _q;
        private LatencyEstimation _lat;
        private int _batchInsert;
        private int _iterations;
 
        public Sender(CountDownLatch startSignal, CountDownLatch doneSignal, ArrayList<Q> output, LatencyEstimation lat, int batchInsert, int iterations) {
            _doneSignal = doneSignal;
            _startSignal = startSignal;
            _q = output;
            _lat = lat;
            _batchInsert = batchInsert;
            _iterations = iterations;
        }

        @Override
        public void run() {
            try {
                final int batchInsert = _batchInsert;
                final int iterations = _iterations;
                _startSignal.await();
                int iteration = 0;
                for (int i = 0; i < (iterations/batchInsert) + 1; i++) {
                    for (int j = 0; j < batchInsert; j++) {
                        while (throttle) {
                            Thread.sleep(1);
                        }
                        long start = _lat.getStart(iteration);
                        _q.get(i % _q.size()).publish(new TestData(iteration, start, false));
                        iteration++;
                    }
                }

                for (Q q: _q) {
                    q.publish(new TestData(0, 0l, true));
                }
             } catch (Exception e) {
                throw new RuntimeException(e);
             } finally {
                _doneSignal.countDown();
             }
        }
    }

    public QRoundRobin(int sendT, int recvT, int batchInsert) {
        _numSendThreads = sendT;
        _numRecvThreads = recvT;
        _batchInsert = batchInsert;
    }

    @Override
    public String description() {
        return "Sends events round robin from "+ _numSendThreads +" to "+_numRecvThreads+" threads";
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
        CB cb = new CB();
        _doneSignal = new CountDownLatch(_numSendThreads + _numRecvThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>(_numRecvThreads);
        _threads = new ArrayList<Thread>(_numSendThreads + _numRecvThreads);

        for (int i = 0; i < _numRecvThreads; i++) {
            Q q = Q.make("Q_"+i, conf);
            q.register(cb);
            _q.add(q);
            Consumer c = new Consumer(_startSignal, _doneSignal, q, _lat, _numSendThreads);
            _threads.add(c);
            c.start();
        }

        for (int i = 0; i < _numSendThreads; i++) {
            Sender s = new Sender(_startSignal, _doneSignal, _q, _lat, _batchInsert, iterations/_numSendThreads + 1);
            _threads.add(s);
            s.start();
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        _startSignal.countDown();
        _doneSignal.await();
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
