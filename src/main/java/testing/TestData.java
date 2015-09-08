package testing;

public class TestData {
    long start;
    int iteration;
    boolean allDone;

    public TestData(int iteration, long start, boolean allDone) {
        this.start = start;
        this.iteration = iteration;
        this.allDone = allDone;
    }
}
