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

import co.paralleluniverse.common.monitoring.MonitorType;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A fiber scheduler that uses a given {@link Executor} for scheduling.
 *
 * @author pron
 */
public class FiberExecutorScheduler extends FiberScheduler {
    private final Executor executor;
    private final FiberTimedScheduler timer;

    /**
     * Creates a new fiber scheduler.
     *
     * @param name         the scheuler's name. This name is used in naming the scheduler's threads.
     * @param executor     an {@link Executor} used to schedule the fibers
     * @param monitorType  the {@link MonitorType} type to use for the scheduler.
     * @param detailedInfo whether detailed information about the fibers is collected by the fibers monitor.
     */
    public FiberExecutorScheduler(String name, Executor executor, MonitorType monitorType, boolean detailedInfo) {
        super(name, monitorType, detailedInfo);
        this.executor = executor;
        this.timer = new FiberTimedScheduler(this,
                new ThreadFactoryBuilder().setDaemon(true).setNameFormat("FiberTimedScheduler-" + getName()).build(),
                getMonitor());
    }

    @Override
    protected boolean isCurrentThreadInScheduler() {
        return false;
    }

    @Override
    protected int getQueueLength() {
        if (executor instanceof ThreadPoolExecutor)
            return ((ThreadPoolExecutor) executor).getQueue().size();
        return -1;
    }

    @Override
    protected Map<Thread, Fiber> getRunningFibers() {
        return null;
    }

    public Executor getExecutor() {
        return executor;
    }

    @Override
    Future<Void> schedule(Fiber<?> fiber, Object blocker, long delay, TimeUnit unit) {
        return timer.schedule(fiber, blocker, delay, unit);
    }

    @Override
    <V> FiberTask<V> newFiberTask(Fiber<V> fiber) {
        return new RunnableFiberTask<V>(fiber, executor);
    }

    @Override
    int getTimedQueueLength() {
        return timer.getQueueLength();
    }

    @Override
    void setCurrentFiber(Fiber target, Thread currentThread) {
        Fiber.currentFiber.set(target);
    }

    @Override
    void setCurrentTarget(Object target, Thread currentThread) {
        Fiber.currentFiber.set((Fiber) target);
    }

    @Override
    Object getCurrentTarget(Thread currentThread) {
        return Fiber.currentFiber.get();
    }
}
