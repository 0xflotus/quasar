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
import co.paralleluniverse.strands.queues.SingleConsumerArrayIntQueue;
import co.paralleluniverse.strands.queues.SingleConsumerIntQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayIntQueue;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pron
 */
public class IntChannel extends Channel<Integer> {
    public static IntChannel create(Object owner, int mailboxSize) {
        return new IntChannel(owner, mailboxSize > 0 ? new SingleConsumerArrayIntQueue(mailboxSize) : new SingleConsumerLinkedArrayIntQueue());
    }

    public static IntChannel create(int mailboxSize) {
        return new IntChannel(mailboxSize > 0 ? new SingleConsumerArrayIntQueue(mailboxSize) : new SingleConsumerLinkedArrayIntQueue());
    }

    private IntChannel(Object owner, SingleConsumerQueue<Integer, ?> queue) {
        super(owner, queue);
    }

    private IntChannel(SingleConsumerQueue<Integer, ?> queue) {
        super(queue);
    }

    public int receiveInt() throws SuspendExecution, InterruptedException {
        final Object n = receiveNode();
        final int m = ((SingleConsumerIntQueue<Object>) queue).intValue(n);
        queue.deq(n);
        return m;
    }

    public int receiveInt(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        final Object n = receiveNode(timeout, unit);
        final int m = ((SingleConsumerIntQueue<Object>) queue).intValue(n);
        queue.deq(n);
        return m;
    }

    public void send(int message) {
        queue.enq(message);
        signal();
    }

    public void sendSync(int message) {
        queue.enq(message);
        signalAndTryToExecNow();
    }
}
