/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
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

/**
 *
 * @author pron
 */
public class ValuedContinuation<S extends Suspend, T, Out, In> extends Continuation<S, T> {
    private Out pauseOut;
    private In pauseIn;

    public ValuedContinuation(Callable<T> target, Class<S> scope, int stackSize) {
        super(target, scope, stackSize);
    }

    public ValuedContinuation(Callable<T> target, Class<S> scope) {
        super(target, scope);
    }

    @Override
    protected void prepare() {
        pauseOut = null;
    }
    
    public void run(In value) {
        pauseIn = value;
        run();
    }
    
    public static <S extends Suspend, In> In pause(S scope) {
        ValuedContinuation<S, ?, ?, In> c = (ValuedContinuation<S, ?, ?, In>) suspend(scope);
        In res = c.pauseIn;
        c.pauseIn = null;
        return res;
    }

    public static <S extends Suspend, Out, In> In pause(S scope, final Out value) {
        ValuedContinuation<S, ?, Out, In> c = (ValuedContinuation<S, ?, Out, In>) suspend(scope, new CalledCC() {
            @Override
            public void suspended(Continuation c) {
                ((ValuedContinuation<S, ?, Out, In>) c).pauseOut = value;
            }
        });
        In res = c.pauseIn;
        c.pauseIn = null;
        return res;
    }

    public static <S extends Suspend, Out, In> In pause(S scope, final Callable<Out> f) {
        ValuedContinuation<S, ?, Out, In> c = (ValuedContinuation<S, ?, Out, In>) suspend(scope, new CalledCC() {
            @Override
            public void suspended(Continuation c) {
                ((ValuedContinuation<S, ?, Out, In>) c).pauseOut = f.call();
            }
        });
        In res = c.pauseIn;
        c.pauseIn = null;
        return res;
    }

    public Out getProductionResult() {
        return pauseOut;
    }
}
