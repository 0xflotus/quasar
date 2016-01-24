/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.fibers.instrument.live.fibers.instrument;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.LiveInstrumentation;
import co.paralleluniverse.fibers.TestsHelper;
import co.paralleluniverse.fibers.instrument.live.LiveInstrumentationTest;
import co.paralleluniverse.strands.SuspendableRunnable;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 *
 * @author Matthias Mann
 */
//@Ignore
public class Merge3Test extends LiveInstrumentationTest implements SuspendableRunnable {
    public boolean a;
    public boolean b;
    
    @Override
    public final void run() {
        if(a) {
            final Object[] arr = new Object[2];
            System.out.println(Arrays.toString(arr));
        } else {
            final float[] arr = new float[3];
            System.out.println(Arrays.toString(arr));
        }
        blub();
        System.out.println();
    }
    
    private void blub() {
    }
    
    @Test
    public final void testMerge3() {
        Fiber c = new Fiber<>((String)null, null, new Merge3Test());
        TestsHelper.exec(c);
        assertThat(LiveInstrumentation.fetchRunCount(), equalTo(0L));
    }
}
