package testing;

public class NoopLatencyEstimation implements LatencyEstimation {
    public long getStart(int iteration) {
        return 0l;
    }

    public void recordLatency(int iteration, long start) {
        //NOOP
    }

    public double getMin() {
        return -1.0;
    }

    public double get50th() {
        return -1.0;
    }

    public double get90th() {
        return -1.0;
    }

    public double get99th() {
        return -1.0;
    }

    public double getMax() {
        return -1.0;
    }
}
