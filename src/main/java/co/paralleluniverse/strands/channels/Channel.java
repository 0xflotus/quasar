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
import co.paralleluniverse.strands.OwnedSynchronizer;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.Stranded;
import co.paralleluniverse.strands.queues.QueueCapacityExceededException;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pron
 */
public abstract class Channel<Message> implements SendChannel<Message>, ReceiveChannel<Message>, Stranded {
    private Object owner;
    private volatile OwnedSynchronizer sync;
    final SingleConsumerQueue<Message, Object> queue;
    private OwnedSynchronizer mySync;

    Channel(Object owner, SingleConsumerQueue<Message, ?> queue) {
        this.queue = (SingleConsumerQueue<Message, Object>) queue;
        this.owner = owner;
        this.sync = OwnedSynchronizer.create(owner);
    }

    Channel(SingleConsumerQueue<Message, ?> queue) {
        this.queue = (SingleConsumerQueue<Message, Object>) queue;
    }

    public int capacity() {
        return queue.capacity();
    }

    public Object getOwner() {
        return owner;
    }

    public boolean isOwnerAlive() {
        return sync.isOwnerAlive();
    }

    @Override
    public void setStrand(Strand strand) {
        if (owner != null && strand != owner)
            throw new IllegalStateException("Channel " + this + " is already owned by " + owner);
        this.owner = strand;
        this.mySync = OwnedSynchronizer.create(owner);
        this.sync = mySync;
    }

    protected void maybeSetCurrentStrandAsOwner() {
        if (owner == null)
            setStrand(Strand.currentStrand());
        else {
            if (sync != mySync)
                sync = mySync;
            sync.verifyOwner();
        }
    }

    protected OwnedSynchronizer sync() {
        verifySync();
        return sync;
    }

    void setSync(OwnedSynchronizer sync) {
        this.sync = sync;
    }

    @Override
    public Strand getStrand() {
        return (Strand) owner;
    }

    protected void signal() {
        if (sync != null && sync.isOwnerAlive())
            sync.signal();
    }

    protected void signalAndTryToExecNow() {
        if (sync != null && sync.isOwnerAlive())
            sync.signalAndTryToExecNow();
    }

    @Override
    public void send(Message message) {
        if (!queue.enq(message))
            throw new QueueCapacityExceededException();
        signal();
    }

    public void sendSync(Message message) {
        if (!queue.enq(message))
            throw new QueueCapacityExceededException();
        signalAndTryToExecNow();
    }

    Object receiveNode() throws SuspendExecution, InterruptedException {
        maybeSetCurrentStrandAsOwner();
        Object n;
        sync.lock();
        while ((n = queue.pk()) == null)
            sync.await();
        sync.unlock();

        return n;
    }

    Object tryReceiveNode() {
        return queue.pk();
    }

    Object receiveNode(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (timeout <= 0 || unit == null)
            return receiveNode();

        maybeSetCurrentStrandAsOwner();
        Object n;

        final long start = System.nanoTime();
        long left = unit.toNanos(timeout);

        sync.lock();
        try {
            while ((n = queue.pk()) == null) {
                sync.await(left, TimeUnit.NANOSECONDS);

                left = start + unit.toNanos(timeout) - System.nanoTime();
                if (left <= 0)
                    return null;
            }
        } finally {
            sync.unlock();
        }
        return n;
    }

    public boolean isMessageAvailable() {
        return queue.pk() != null;
    }

    public Message tryReceive() {
        final Object n = tryReceiveNode();
        if (n == null)
            return null;
        final Message m = queue.value(n);
        queue.deq(n);
        return m;
    }

    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        final Object n = receiveNode();
        final Message m = queue.value(n);
        queue.deq(n);
        return m;
    }
    
    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        final Object n = receiveNode(timeout, unit);
        if (n == null)
            return null; // timeout
        final Message m = queue.value(n);
        queue.deq(n);
        return m;
    }

    public Message receiveFromThread() throws InterruptedException {
        try {
            return receive();
        } catch (SuspendExecution ex) {
            throw new AssertionError(ex);
        }
    }

    public Message receiveFromThread(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return receive(timeout, unit);
        } catch (SuspendExecution ex) {
            throw new AssertionError(ex);
        }
    }

    private void verifySync() {
        if (sync == null)
            throw new IllegalStateException("Owning strand has not been set");
    }

    public int getQueueLength() {
        return queue.size();
    }
}
