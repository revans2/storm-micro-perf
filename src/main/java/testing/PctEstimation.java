package testing;

import java.util.Arrays;

public class PctEstimation {
    private int _cuttoff;
    private double[] _data;
    private volatile int _offset;

    public PctEstimation(double percentage, int maxSamples) {
        _cuttoff = Math.max(1, (int)(percentage * 65536));
        _data = new double[maxSamples];
        _offset = -1;
    }

    public void recordValue(int iteration, double value) {
        if ((iteration  & 0xFFFF) < _cuttoff) {
            int myOffset = ++_offset;
            if (myOffset < _data.length) {
                _data[myOffset] = value;
            }
        }
    }
 
    public double getPct(double pct) {
        int length = Math.min(_offset, _data.length);
        if (length < 1) {
            return -1;
        }
        double[] copy = Arrays.copyOf(_data, length);
        Arrays.sort(copy);
        return copy[(int)(copy.length * pct)];
    }

    public double getMin() {
        int length = Math.min(_offset, _data.length);
        double min = _data[0];
        for (int i = 0; i < length; i++) {
            if (min > _data[i]) min = _data[i];
        } 
        return min;
    }

    public double getMax() {
        int length = Math.min(_offset, _data.length);
        double max = _data[0];
        for (int i = 0; i < length; i++) {
            if (max < _data[i]) max = _data[i];
        } 
        return max;
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
