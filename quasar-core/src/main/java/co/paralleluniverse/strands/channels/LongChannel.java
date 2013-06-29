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
import co.paralleluniverse.strands.queues.SingleConsumerArrayLongQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayLongQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLongQueue;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public class LongChannel extends PrimitiveChannel<Long> implements LongSendPort, LongReceivePort {
    public static LongChannel create(Object owner, int mailboxSize, OverflowPolicy policy) {
        return new LongChannel(owner,
                mailboxSize > 0
                ? new SingleConsumerArrayLongQueue(mailboxSize)
                : new SingleConsumerLinkedArrayLongQueue(),
                policy);
    }

    public static LongChannel create(Object owner, int mailboxSize) {
        return create(owner, mailboxSize, OverflowPolicy.THROW);
    }

    public static LongChannel create(int mailboxSize, OverflowPolicy policy) {
        return create(null, mailboxSize, policy);
    }

    public static LongChannel create(int mailboxSize) {
        return create(null, mailboxSize, OverflowPolicy.THROW);
    }

    private LongChannel(Object owner, SingleConsumerQueue<Long, ?> queue, OverflowPolicy policy) {
        super(owner, queue, policy);
    }

    @Override
    public long receiveLong() throws SuspendExecution, InterruptedException {
        if (isClosed())
            throw new EOFException();
        final Object n = receiveNode();
        final long m = queue().longValue(n);
        queue.deq(n);
        signalSenders();
        return m;
    }

    @Override
    public long receiveLong(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
        if (isClosed())
            throw new EOFException();
        final Object n = receiveNode(timeout, unit);
        if (n == null)
            throw new TimeoutException();
        final long m = queue().longValue(n);
        queue.deq(n);
        signalSenders();
        return m;
    }

    @Override
    public void send(long message) {
        if (isSendClosed())
            return;
        queue().enq(message);
        signalReceiver();
    }

    @Override
    public boolean trySend(long message) {
        if (isSendClosed())
            return true;
        if (queue().enq(message)) {
            signalReceiver();
            return true;
        } else
            return false;
    }

    public void sendSync(long message) {
        if (isSendClosed())
            return;
        queue.enq(message);
        signalAndTryToExecNow();
    }

    private SingleConsumerLongQueue<Object> queue() {
        return (SingleConsumerLongQueue<Object>) queue;
    }
}
