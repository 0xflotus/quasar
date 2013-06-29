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

import co.paralleluniverse.common.util.Objects;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.remote.RemoteProxyFactoryService;
import co.paralleluniverse.strands.Condition;
import co.paralleluniverse.strands.OwnedSynchronizer;
import co.paralleluniverse.strands.SimpleConditionSynchronizer;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.queues.QueueCapacityExceededException;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pron
 */
public abstract class QueueChannel<Message> implements Channel<Message>, SelectableReceive, SelectableSend, java.io.Serializable {
    public enum OverflowPolicy {
        THROW, DROP, BLOCK, BACKOFF
    }
    private static final int MAX_SEND_RETRIES = 10;
    private Strand owner;
    private final OwnedSynchronizer sync;
    private final SimpleConditionSynchronizer sendersSync;
    protected final SingleConsumerQueue<Message, Object> queue;
    private final OverflowPolicy overflowPolicy;
    private volatile boolean sendClosed;
    private boolean receiveClosed;

    protected QueueChannel(Strand owner, SingleConsumerQueue<Message, ?> queue, OverflowPolicy overflowPolicy) {
        this.queue = (SingleConsumerQueue<Message, Object>) queue;
        this.owner = owner;
        this.sync = new OwnedSynchronizer();
        this.overflowPolicy = overflowPolicy;
        this.sendersSync = overflowPolicy == OverflowPolicy.BLOCK ? new SimpleConditionSynchronizer() : null;
    }

    protected QueueChannel(SingleConsumerQueue<Message, ?> queue, OverflowPolicy overflowPolicy) {
        this(null, queue, overflowPolicy);
    }

    public int capacity() {
        return queue.capacity();
    }

    public Object getOwner() {
        return owner;
    }

    public boolean isOwnerAlive() {
        return owner.isAlive();
    }

    public void setStrand(Strand strand) {
        if (owner != null && strand != owner)
            throw new IllegalStateException("Channel " + this + " is already owned by " + owner);
        this.owner = strand;
    }

    protected void maybeSetCurrentStrandAsOwner() {
        if (owner == null)
            setStrand(Strand.currentStrand());
        else {
            assert Strand.equals(owner, Strand.currentStrand()) : "This method has been called by a different strand (thread or fiber) than that owning this object";
        }
    }

    protected OwnedSynchronizer sync() {
        verifySync();
        return sync;
    }

    protected void signalReceivers() {
        if (sync != null)
            sync.signal();
    }

    protected void signalAndTryToExecNow() {
        if (sync != null)
            sync.signalAndTryToExecNow();
    }

    void signalSenders() {
        if (overflowPolicy == OverflowPolicy.BLOCK)
            sendersSync.signal();
    }

    @Override
    public Condition sendSelector() {
        return sendersSync;
    }

    @Override
    public Condition receiveSelector() {
        return sync;
    }

    public void sendNonSuspendable(Message message) throws QueueCapacityExceededException {
        if (isSendClosed())
            return;
        if (!queue.enq(message))
            throw new QueueCapacityExceededException();
        signalReceivers();
    }

    @Override
    public void send(Message message) throws SuspendExecution {
        send0(message, false);
    }

    @Override
    public boolean trySend(Message message) {
        if (isSendClosed())
            return true;
        if (queue.enq(message)) {
            signalReceivers();
            return true;
        } else
            return false;
    }

    protected void sendSync(Message message) throws SuspendExecution {
        send0(message, true);
    }

    public void send0(Message message, boolean sync) throws SuspendExecution {
        if (isSendClosed())
            return;
        if (overflowPolicy == OverflowPolicy.BLOCK)
            sendersSync.register();
        try {
            int i = 0;
            while (!queue.enq(message)) {
                onQueueFull(i++);
            }
        } catch (InterruptedException e) {
            Strand.currentStrand().interrupt();
            return;
        } finally {
            if (overflowPolicy == OverflowPolicy.BLOCK)
                sendersSync.unregister();
        }
        if (sync)
            signalAndTryToExecNow();
        else
            signalReceivers();
    }

    private void onQueueFull(int iter) throws SuspendExecution, InterruptedException {
        switch (overflowPolicy) {
            case DROP:
                return;
            case THROW:
                throw new QueueCapacityExceededException();
            case BLOCK:
                sendersSync.await();
                break;
            case BACKOFF:
                if (iter > MAX_SEND_RETRIES)
                    throw new QueueCapacityExceededException();
                if (iter > 5)
                    Strand.sleep((iter - 5) * 5);
                else if (iter > 4)
                    Strand.yield();
        }
    }

    @Override
    public void close() {
        if (!sendClosed) {
            sendClosed = true;
            signalReceivers();
        }
    }

    /**
     * This method must only be called by the channel's owner (the receiver)
     *
     * @return
     */
    @Override
    public boolean isClosed() {
        return receiveClosed;
    }

    boolean isSendClosed() {
        return sendClosed;
    }

    private void setReceiveClosed() {
        this.receiveClosed = true;
    }

    Object receiveNode() throws SuspendExecution, InterruptedException {
        maybeSetCurrentStrandAsOwner();
        Object n;
        sync.register();
        while ((n = queue.pk()) == null) {
            if (sendClosed) {
                setReceiveClosed();
                throw new EOFException();
            }
            sync.await();
        }
        sync.unregister();

        return n;
    }

    Object tryReceiveNode() {
        return queue.pk();
    }

    Object receiveNode(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (unit == null)
            return receiveNode();
        if (timeout <= 0)
            return tryReceiveNode();

        maybeSetCurrentStrandAsOwner();
        Object n;

        final long start = System.nanoTime();
        long left = unit.toNanos(timeout);

        sync.register();
        try {
            while ((n = queue.pk()) == null) {
                if (sendClosed) {
                    setReceiveClosed();
                    throw new EOFException();
                }
                sync.await(left, TimeUnit.NANOSECONDS);

                left = start + unit.toNanos(timeout) - System.nanoTime();
                if (left <= 0)
                    return null;
            }
        } finally {
            sync.unregister();
        }
        return n;
    }

    public boolean isMessageAvailable() {
        return queue.pk() != null;
    }

    @Override
    public Message tryReceive() {
        final Object n = tryReceiveNode();
        if (n == null)
            return null;
        final Message m = queue.value(n);
        queue.deq(n);
        signalSenders();
        return m;
    }

    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        if (receiveClosed)
            return null;
        try {
            final Object n = receiveNode();
            final Message m = queue.value(n);
            queue.deq(n);
            signalSenders();
            return m;
        } catch (EOFException e) {
            return null;
        }
    }

    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (receiveClosed)
            return null;
        try {
            final Object n = receiveNode(timeout, unit);
            if (n == null)
                return null; // timeout
            final Message m = queue.value(n);
            queue.deq(n);
            signalSenders();
            return m;
        } catch (EOFException e) {
            return null;
        }
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

    @Override
    public String toString() {
        return "Channel{" + "owner: " + owner + ", sync: " + sync + ", queue: " + Objects.systemToString(queue) + '}';
    }

    protected Object writeReplace() throws java.io.ObjectStreamException {
        return RemoteProxyFactoryService.create(this, null);
    }
}
