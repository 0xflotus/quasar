/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package co.paralleluniverse.concurrent.lwthreads;

import static org.junit.Assert.*;
import org.junit.Test;


/**
 *
 * @author Matthias Mann
 */
public class NullTest implements SuspendableRunnable {

    Object result = "b";
    
    @Test
    public void testNull() {
        LightweightThread co = new LightweightThread(this);
        int count = 1;
        while(!co.exec())
            count++;

        assertEquals(2, count);
        assertEquals("a", result);
    }
    
    @Override
    public void run() throws SuspendExecution {
        result = getProperty();
    }
    
    private Object getProperty() throws SuspendExecution {
        Object x = null;
        
        Object y = getProtery("a");
        if(y != null) {
            x = y;
        }
        
        return x;
    }

    private Object getProtery(String string) throws SuspendExecution {
        LightweightThread.yield();
        return string;
    }

}
