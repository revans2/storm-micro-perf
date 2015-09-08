package testing;

import com.lmax.disruptor.EventHandler;

import java.util.concurrent.CountDownLatch;

public class PassThrough extends EventThread<TestData> {
    private Q _output;

    public PassThrough(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, Q output) {
        super(startSignal, doneSignal, input);
        _output = output;
    }

    @Override
    public void onEvent(TestData event) throws Exception {
        _output.publish(event);
    }
}
