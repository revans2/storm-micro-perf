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
    private int _numThreads;
    private int _batchInsert;

    public QRoundRobin(int threads, int batchInsert) {
        _numThreads = threads;
        _batchInsert = batchInsert;
    }

    @Override
    public String description() {
        return "Sends events round robing to "+_numThreads+" threads";
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
        _doneSignal = new CountDownLatch(_numThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>(_numThreads);
        _threads = new ArrayList<Thread>(_numThreads);

        for (int i = 0; i < _numThreads; i++) {
            Q q = Q.make("Q_"+i, conf);
            _q.add(q);
            Consumer c = new Consumer(_startSignal, _doneSignal, q, _lat);
            _threads.add(c);
            c.start();
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        final int batchInsert = _batchInsert;
        _startSignal.countDown();
        int iteration = 0;
        for (int i = 0; i < (iterations/batchInsert) + 1; i++) {
            for (int j = 0; j < batchInsert; j++) {
                long start = _lat.getStart(iteration);
                _q.get(i % _q.size()).publish(new TestData(iteration, start, false));
                iteration++;
            }
        }

        for (Q q: _q) {
            q.publish(new TestData(0, 0l, true));
        }

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
    }
}
