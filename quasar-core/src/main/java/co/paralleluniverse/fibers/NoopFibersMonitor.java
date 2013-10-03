/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers;

import co.paralleluniverse.common.monitoring.ForkJoinPoolMonitor;

/**
 *
 * @author pron
 */
public class NoopFibersMonitor extends ForkJoinPoolMonitor implements FibersMonitor {

    public NoopFibersMonitor() {
        super(null, null);
    }

    @Override
    public void fiberStarted(Fiber fiber) {
    }

    @Override
    public void fiberResumed() {
    }

    @Override
    public void fiberSuspended() {
    }

    @Override
    public void fiberTerminated(Fiber fiber) {
    }

    @Override
    public void spuriousWakeup() {
    }

    @Override
    public void timedParkLatency(long ns) {
    }
}
