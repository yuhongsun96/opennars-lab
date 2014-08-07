package nars.perf;

import java.util.Collection;
import nars.core.DefaultNARBuilder;
import nars.core.NAR;
import nars.test.core.NALTest;
import static nars.perf.NALTestPerf.perfNAL;

/**
 * Runs NALTestPerf continuously, for profiling
 */
public class NALPerfLoop {
    
    public static void main(String[] args) {
       
        int repeats = 1;
        int warmups = 0;
        int extraCycles = 128;
        
        NAR n = new DefaultNARBuilder().build();
        
        Collection c = NALTest.params();
        while (true) {
            for (Object o : c) {
                String examplePath = (String)((Object[])o)[0];
                perfNAL(n, examplePath,extraCycles,repeats,warmups,false);
            }
        }        
    }
}