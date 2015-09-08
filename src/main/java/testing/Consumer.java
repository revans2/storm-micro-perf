package testing;

import java.util.concurrent.CountDownLatch;

public class Consumer extends EventThread<TestData> {
    private LatencyEstimation _lat;

    public Consumer(CountDownLatch startSignal, CountDownLatch doneSignal, Q input, LatencyEstimation lat) {
        super(startSignal, doneSignal, input);
        _lat = lat;
    }

    @Override
    public void onEvent(TestData data) throws Exception {
        _lat.recordLatency(data.iteration, data.start);
    }
}
