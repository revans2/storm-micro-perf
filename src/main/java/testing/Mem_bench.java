/**
 * Created by rfarivar on 9/1/15.
 */

import java.lang.Math;
import java.util.Date;

public class Mem_bench {
    static int mem_size = 128*1024*1024;
    static long[] array = new long[mem_size];
    static int[] pointers = new int[mem_size];


    public static void main(String [ ] args){
        for (int i = 0; i < mem_size; i++) {
            array[i] = i;
			//pick one of the following lines for random or serialized mem access
            pointers[i] = (int) (Math.random() * mem_size);
            pointers[i] = i;
        }
        Date date1 = new Date();
        Long time1 = (long) (((((date1.getHours() * 60) + date1.getMinutes())* 60 ) + date1.getSeconds()) * 1000);
        long x = bench();
        Date date2 = new Date();
        Long time2 = (long) (((((date2.getHours() * 60) + date2.getMinutes())* 60 ) + date2.getSeconds()) * 1000);
        System.out.println("Result is: " + x + " time spent is: " + (time2-time1));
    }

    static long bench () {
        long sum = 0;
        int pointer = 0;
        for (long i = 0; i < 1000*1024*1024; i++) {
            pointer = pointers[pointer];
            sum = sum + array[pointer] ;
        }
        return sum;
    }

}
