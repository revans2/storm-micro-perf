package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class QPipeline implements Test {
    private ArrayList<Thread> _threads;
    private CountDownLatch _doneSignal;
    private CountDownLatch _startSignal;
    private ArrayList<Q> _q;
    private LatencyEstimation _lat;
    private int _depth;

    public QPipeline(int depth) {
        _depth = depth;
    }

    @Override
    public String description() {
        return "Sends events through a pipeline "+_depth+" deep.";
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf) {
        _doneSignal = new CountDownLatch(_depth);
        _startSignal = new CountDownLatch(1);
        _lat = lat;
        _q = new ArrayList<Q>(_depth - 1);
        _threads = new ArrayList<Thread>(_depth - 1);

        Q previous = null;
        for (int i = 0; i < _depth+1; i++) {
            Q current = null;
            if (i != _depth) {
                current = Q.make("Q_"+i, conf);
                _q.add(current);
            }
            if (i == _depth) {
                Consumer c = new Consumer(_startSignal, _doneSignal, previous, _lat);
                _threads.add(c);
                c.start();
            } else if (previous != null) {
                PassThrough p = new PassThrough(_startSignal, _doneSignal, previous, current);
                _threads.add(p);
                p.start();
            }
            previous = current;
        }
    }

    @Override
    public void runTest(int iterations) throws Exception {
        _startSignal.countDown();
        Q q = _q.get(0);
        LatencyEstimation lat = _lat;
        for (int i = 1; i <= iterations; i++) {
            long start = lat.getStart(i);
            q.publish(new TestData(i, start, false));
        }
        q.publish(new TestData(-1, 0l, true));
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
