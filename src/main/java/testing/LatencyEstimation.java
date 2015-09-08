package testing;

public interface LatencyEstimation {
    public long getStart(int iteration);
    public void recordLatency(int iteration, long start);
    public double getMin();
    public double get50th();
    public double get90th();
    public double get99th();
    public double getMax();
}
