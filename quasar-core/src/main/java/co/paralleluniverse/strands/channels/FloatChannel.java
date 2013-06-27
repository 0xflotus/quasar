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
package co.paralleluniverse.strands.channels;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.queues.SingleConsumerArrayFloatQueue;
import co.paralleluniverse.strands.queues.SingleConsumerFloatQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayFloatQueue;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public class FloatChannel extends PrimitiveChannel<Float> {
    public static FloatChannel create(Object owner, int mailboxSize) {
        return new FloatChannel(owner, mailboxSize > 0 ? new SingleConsumerArrayFloatQueue(mailboxSize) : new SingleConsumerLinkedArrayFloatQueue());
    }

    public static FloatChannel create(int mailboxSize) {
        return new FloatChannel(mailboxSize > 0 ? new SingleConsumerArrayFloatQueue(mailboxSize) : new SingleConsumerLinkedArrayFloatQueue());
    }

    private FloatChannel(Object owner, SingleConsumerQueue<Float, ?> queue) {
        super(owner, queue);
    }

    private FloatChannel(SingleConsumerQueue<Float, ?> queue) {
        super(queue);
    }

    public float receiveFloat() throws SuspendExecution, InterruptedException {
        if (isClosed())
            throw new EOFException();
        final Object n = receiveNode();
        final float m = queue().floatValue(n);
        queue.deq(n);
        return m;
    }

    public float receiveFloat(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
        if (isClosed())
            throw new EOFException();
        final Object n = receiveNode(timeout, unit);
        if (n == null)
            throw new TimeoutException();
        final float m = queue().floatValue(n);
        queue.deq(n);
        return m;
    }

    public void send(float message) {
        if (isSendClosed())
            return;
        queue.enq(message);
        signal();
    }

    public void sendSync(float message) {
        if (isSendClosed())
            return;
        queue.enq(message);
        signalAndTryToExecNow();
    }

    private SingleConsumerFloatQueue<Object> queue() {
        return (SingleConsumerFloatQueue<Object>) queue;
    }
}
