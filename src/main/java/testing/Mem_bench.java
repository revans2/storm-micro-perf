/**
 * Created by rfarivar on 9/1/15.
 */

import java.lang.Math;
import java.util.Date;

public class Mem_bench {
    static final int mem_size = 128*1024*1024;
    static final int num_iterations = 100 * 1024 * 1024;
    static final long[] array = new long[mem_size];
    static final int[] pointers = new int[mem_size];


    public static void main(String [ ] args){
        for (int i = 0; i < mem_size; i++) {
            array[i] = i;
			//pick one of the following lines for random or serialized mem access
            pointers[i] = (int) (Math.random() * mem_size);
            pointers[i] = i;
        }
        long time1 = System.nanoTime();;
        long x = bench();
        long time2 = System.nanoTime();
        System.out.printf("Result is: %d time spent is: %,d ns  freq: %,.2f /sec\n",x, time2-time1, num_iterations/((double)time2-time1) * 1000000000.0);
    }

    static long bench () {
        long sum = 0;
        int pointer = 0;
        for (long i = 0; i < num_iterations; i++) {
            pointer = pointers[pointer];
            sum = sum + array[pointer] ;
        }
        return sum;
    }

}
