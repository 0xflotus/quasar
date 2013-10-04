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
import co.paralleluniverse.common.monitoring.MonitorType;
import co.paralleluniverse.concurrent.forkjoin.MonitoredForkJoinPool;
import co.paralleluniverse.concurrent.forkjoin.NamingForkJoinWorkerFactory;
import jsr166e.ForkJoinPool;

/**
 *
 * @author pron
 */
public class DefaultFiberScheduler {
    private static final String PROPERTY_PARALLELISM = "co.paralleluniverse.fibers.DefaultFiberPool.parallelism";
    private static final String PROPERTY_EXCEPTION_HANDLER = "co.paralleluniverse.fibers.DefaultFiberPool.exceptionHandler";
    private static final String PROPERTY_THREAD_FACTORY = "co.paralleluniverse.fibers.DefaultFiberPool.threadFactory";
    private static final String PROPERTY_MONITOR_TYPE = "co.paralleluniverse.fibers.DefaultFiberPool.monitor";
    private static final String PROPERTY_DETAILED_FIBER_INFO = "co.paralleluniverse.fibers.DefaultFiberPool.detailedFiberInfo";
    private static final int MAX_CAP = 0x7fff;  // max #workers - 1
    private static final FiberScheduler instance;

    static {
        final String name = "default-fiber-pool";
        int par = 0;
        Thread.UncaughtExceptionHandler handler = null;
        ForkJoinPool.ForkJoinWorkerThreadFactory fac = new NamingForkJoinWorkerFactory(name);
        MonitorType monitorType = MonitorType.JMX;
        boolean detailedFiberInfo = true;

        try {
            String pp = System.getProperty(PROPERTY_PARALLELISM);
            String hp = System.getProperty(PROPERTY_EXCEPTION_HANDLER);
            String fp = System.getProperty(PROPERTY_THREAD_FACTORY);
            if (fp != null)
                fac = ((ForkJoinPool.ForkJoinWorkerThreadFactory) ClassLoader.getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((Thread.UncaughtExceptionHandler) ClassLoader.getSystemClassLoader().loadClass(hp).newInstance());
            if (pp != null)
                par = Integer.parseInt(pp);
        } catch (Exception ignore) {
        }

        if (par <= 0)
            par = Runtime.getRuntime().availableProcessors();
        if (par > MAX_CAP)
            par = MAX_CAP;

        String mt = System.getProperty(PROPERTY_MONITOR_TYPE);
        if (mt != null)
            monitorType = MonitorType.valueOf(mt.toUpperCase());

        MonitoredForkJoinPool pool = new MonitoredForkJoinPool(name, par, fac, handler, true);
        final ForkJoinPoolMonitor fjpMonitor = FiberScheduler.createForkJoinPoolMonitor(name, pool, monitorType);
        pool.setMonitor(fjpMonitor);
        
        String dfis = System.getProperty(PROPERTY_DETAILED_FIBER_INFO);
        if(dfis != null)
            detailedFiberInfo = Boolean.valueOf(dfis);
        
        instance = new FiberScheduler(pool, detailedFiberInfo);
    }

    public static FiberScheduler getInstance() {
        return instance;
    }
}
