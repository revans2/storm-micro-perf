package testing;

import java.util.Map;

public interface Test {
    public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) throws Exception;
    public void runTest(int iterations) throws Exception;
    public void cleanup() throws Exception;
    public String description();
}
