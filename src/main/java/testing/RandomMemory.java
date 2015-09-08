package testing;

import java.util.Random;
import java.util.Map;

public class RandomMemory implements Test {
    private LatencyEstimation _lat;
    private byte[] _array;

    public RandomMemory(int size) {
        _array = new byte[size];
    }

    @Override
    public void prepare(LatencyEstimation lat, Map<String, String> conf) {
        _lat = lat;
    }

    @Override
    public void runTest(int iterations) {
        Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        LatencyEstimation lat = _lat;
        byte val = 0;
        for (int i = 0; i < iterations; i++) {
            long start = lat.getStart(i);
            val += _array[rnd.nextInt(_array.length)];
            lat.recordLatency(i, start);
        }
    }

    @Override
    public void cleanup() {
        //Empty
    }

    @Override
    public String description() {
        return "Randomly access "+_array.length+" bytes";
    }
}
