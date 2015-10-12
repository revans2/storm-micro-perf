package testing;

import org.HdrHistogram.*;

public class HdrLatencyEstimationImpl implements LatencyEstimation {
    private Histogram _histo;

    public HdrLatencyEstimationImpl() {
        _histo = new Histogram(3600000000000L, 3);
    }

    public long getStart(int iteration) {
        return System.nanoTime();
    } 

    public synchronized void recordLatency(int iteration, long start) {
        long end = System.nanoTime();
        long time = end - start;
        if (time < 0) {
            System.out.println("TIME < 0 "+end+" "+start);
            time = 0;
        }
        if (time > 3600000000000L) {
            System.out.println("TIME > MAX "+end+" "+start);
            time = 3600000000000L;
        }
        _histo.recordValue(time);
    }

    public synchronized double getPct(double pct) {
        return _histo.getValueAtPercentile(pct * 100);
    }

    public synchronized double getMin() {
        return _histo.getMinValue();
    }

    public synchronized double getMax() {
        return _histo.getMaxValue();
    }

    public double get50th() {
        return getPct(0.5);
    }

    public double get90th() {
        return getPct(0.9);
    }

    public double get99th() {
        return getPct(0.99);
    }

    public String toString() {
        return get50th()+"\t"+get90th()+"\t"+get99th();
    }
}
