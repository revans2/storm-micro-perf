package testing;

public class LatencyEstimationImpl extends PctEstimation implements LatencyEstimation {
    public LatencyEstimationImpl(double percentage, int iterations) {
        super(percentage, (int)(percentage * iterations));
    }

    public long getStart(int iteration) {
        return System.nanoTime();
    } 

    public void recordLatency(int iteration, long start) {
        recordValue(iteration, System.nanoTime() - start);
    }
}
