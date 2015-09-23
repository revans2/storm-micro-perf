package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

public abstract class EventThread<T extends TestData> extends Thread implements EventHandler<Object> {
    private boolean _isDone = false;
    private Q _input;
    private CountDownLatch _startSignal;
    private CountDownLatch _doneSignal;
    private int _currentSize = 0;
    private int _numDoneMessages;
    //private PctEstimation _batchSize;
    //private PctEstimation _qSize;
    //private PctEstimation _pop;
    //private static final int MAX_SAMPLES = 100000;

    public EventThread(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, int numDoneMessages) {
        _numDoneMessages = numDoneMessages;
        _input = input;
        _startSignal = startSignal;
        _doneSignal = doneSignal;
        //_batchSize = new PctEstimation(1.0, MAX_SAMPLES);
        //_qSize = new PctEstimation(1.0, MAX_SAMPLES);
        //_pop = new PctEstimation(1.0, MAX_SAMPLES);
    }

    @Override
    public void run() {
        try {
            _startSignal.await();
            Q input = _input;
            while (!_isDone) {
                input.consumeBatchWhenAvailable(this);
            }
            //System.out.println(_input+" Q_SIZE "+_qSize);
            //System.out.println(_input+" POP_SIZE "+_pop);
            //System.out.println(_input+" BATCH_SIZE "+_batchSize);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            _doneSignal.countDown();
            onAllDone();
        }
    }

    @Override
    public void onEvent(Object event, long sequence, boolean endOfBatch) throws Exception {
        T data = (T)event;
        if (data.allDone) {
            _numDoneMessages--;
            if (_numDoneMessages <= 0) {
              _isDone = true;
            }
        }
        _currentSize++;

        //_pop.recordValue(data.iteration, (double)_input.population());
        //_qSize.recordValue(data.iteration, _input.getCursor() - sequence);
        //_batchSize.recordValue(data.iteration, _currentSize);

        if (endOfBatch) {
            _currentSize = 0;
        }
        onEvent(data);
    }

    public abstract void onEvent(T event) throws Exception;

    public void onAllDone() {
        //Empty
    }
}
