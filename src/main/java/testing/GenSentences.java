package testing;

import java.util.Random;
import java.util.Map;

public class GenSentences {
    public static Test TEST = new Test(){
        private GenSentences _gen;
        private LatencyEstimation _lat;

        @Override
        public void prepare(LatencyEstimation lat, Map<String, String> conf, int iterations) {
            _gen = new GenSentences();
            _lat = lat;
        }

        @Override
        public void runTest(int iterations) {
            GenSentences gen = _gen;
            LatencyEstimation lat = _lat;
            for (int i = 0; i < iterations; i++) {
                long start = lat.getStart(i);
                gen.getNext();
                lat.recordLatency(i, start);
            }
        }

        @Override
        public void cleanup() {
            //Empty
        }

        @Override
        public String description() {
            return "Generate random sentences (used with word count)";
        }
    };
    
    private static final String[] CHOICES = {
        "marry had a little lamb whos fleese was white as snow",
        "and every where that marry went the lamb was sure to go",
        "one two three four five six seven eight nine ten",
        "this is a test of the emergency broadcast system this is only a test",
        "peter piper picked a peck of pickeled peppers"
    };
    private Random _rnd = new Random();


    String getNext() {
        return CHOICES[_rnd.nextInt(CHOICES.length)];
    }
}
