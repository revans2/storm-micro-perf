package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PsudoQRoundRobin implements Test {
    private ArrayList<Thread> _threads;
    private CountDownLatch _doneSignal;
    private CountDownLatch _startSignal;
    private ArrayList<Q> _q;
    private LatencyEstimation _lat;
    private int _numThreads;

    public PsudoQRoundRobin(int threads) {
        _numThreads = threads;
    }

    @Override
    public String description() {
        return "Sends events round robin to "+_numThreads+" threads, batched in Q";
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
        _doneSignal = new CountDownLatch(_numThreads);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>(_numThreads);
        _threads = new ArrayList<Thread>(_numThreads);

        //This is where we know the number of iterations so allocate the buffers here first
        for (int i = 0; i < _numThreads; i++) {
            Q q = Q.make("Q_"+i, conf, iterations);
            _q.add(q);
            Consumer c = new Consumer(_startSignal, _doneSignal, q, _lat);
            _threads.add(c);
            c.start();
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        for (int i = 0; i < iterations; i++) {
            long start = _lat.getStart(i);
            _q.get(i % _q.size()).publish(new TestData(i, start, false));
        }

        for (Q q: _q) {
            q.publish(new TestData(0, 0l, true));
        }

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
    }
}
