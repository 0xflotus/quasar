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
import co.paralleluniverse.remote.RemoteChannelProxyFactoryService;
import co.paralleluniverse.strands.Condition;
import co.paralleluniverse.strands.OwnedSynchronizer;
import co.paralleluniverse.strands.SimpleConditionSynchronizer;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.Synchronization;
import co.paralleluniverse.strands.channels.Channels.OverflowPolicy;
import co.paralleluniverse.strands.queues.BasicQueue;
import co.paralleluniverse.strands.queues.CircularBuffer;
import co.paralleluniverse.strands.queues.QueueCapacityExceededException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public abstract class QueueChannel<Message> implements Channel<Message>, Selectable<Message>, Synchronization, java.io.Serializable {
    private static final int MAX_SEND_RETRIES = 10;
    final Condition sync;
    final Condition sendersSync;
    final BasicQueue<Message> queue;
    final OverflowPolicy overflowPolicy;
    private volatile boolean sendClosed;
    private boolean receiveClosed;

    protected QueueChannel(BasicQueue<Message> queue, OverflowPolicy overflowPolicy, boolean singleConsumer) {
        this.queue = queue;
        if (!singleConsumer || queue instanceof CircularBuffer)
            this.sync = new SimpleConditionSynchronizer(this);
        else
            this.sync = new OwnedSynchronizer(this);

        this.overflowPolicy = overflowPolicy;
        this.sendersSync = overflowPolicy == OverflowPolicy.BLOCK ? new SimpleConditionSynchronizer(this) : null;
    }

    public int capacity() {
        return queue.capacity();
    }

    protected Condition sync() {
        verifySync();
        return sync;
    }

    protected void signalReceivers() {
        sync.signalAll();
    }

    protected void signalAndWait() throws SuspendExecution, InterruptedException {
        if (sync instanceof OwnedSynchronizer)
            ((OwnedSynchronizer) sync).signalAndWait();
        else
            sync.signalAll();
    }

    void signalSenders() {
        if (overflowPolicy == OverflowPolicy.BLOCK)
            sendersSync.signal();
    }

    @Override
    public Object register(SelectAction<Message> action) {
        if (action.isData()) {
            if (sendersSync != null)
                sendersSync.register();
        } else
            sync.register();
        return action;
    }

    @Override
    public Object register() {
        // for queues, a simple registration is always a receive
        return sync.register();
    }

    @Override
    public boolean tryNow(Object token) {
        SelectAction<Message> action = (SelectAction<Message>) token;
        if (!action.lease())
            return false;
        boolean res;
        if (action.isData()) {
            res = trySend(action.message());
            if (res)
                action.setItem(null);
        } else {
            Message m = tryReceive();
            action.setItem(m);
            if (m == null)
                res = isClosed();
            else
                res = true;
        }
        if (res)
            action.won();
        else
            action.returnLease();
        return res;
    }

    @Override
    public void unregister(Object token) {
        if (token == null)
            return;
        SelectAction<Message> action = (SelectAction<Message>) token;
        if (action.isData()) {
            if (sendersSync != null)
                sendersSync.unregister(null);
        } else
            sync.unregister(null);
    }

    public void sendNonSuspendable(Message message) throws QueueCapacityExceededException {
        if (isSendClosed())
            return;
        if (!queue.enq(message))
            throw new QueueCapacityExceededException();
        signalReceivers();
    }

    @Override
    public void send(Message message) throws SuspendExecution, InterruptedException {
        send0(message, false, false, 0);
    }

    @Override
    public boolean send(Message message, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return send0(message, false, true, unit.toNanos(timeout));
    }

    @Override
    public boolean trySend(Message message) {
        if (message == null)
            throw new IllegalArgumentException("message is null");
        if (isSendClosed())
            return true;
        if (queue.enq(message)) {
            signalReceivers();
            return true;
        } else
            return false;
    }

    protected void sendSync(Message message) throws SuspendExecution {
        try {
            send0(message, true, false, 0);
        } catch (InterruptedException e) {
            Strand.currentStrand().interrupt();
        }
    }

    public boolean send0(Message message, boolean sync, boolean timed, long nanos) throws SuspendExecution, InterruptedException {
        if (message == null)
            throw new IllegalArgumentException("message is null");
        if (isSendClosed())
            return true;
        if (overflowPolicy == OverflowPolicy.BLOCK)
            sendersSync.register();
        try {
            int i = 0;

            final long deadline = timed ? System.nanoTime() : 0L;

            while (!queue.enq(message)) {
                if (isSendClosed())
                    return true;
                onQueueFull(i++, timed, nanos);

                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0)
                        throw new TimeoutException();
                }
            }
        } catch (TimeoutException e) {
            return false;
        } finally {
            if (overflowPolicy == OverflowPolicy.BLOCK)
                sendersSync.unregister(null);
        }
        if (sync)
            signalAndWait();
        else
            signalReceivers();
        return true;
    }

    void onQueueFull(int iter, boolean timed, long nanos) throws SuspendExecution, InterruptedException, TimeoutException {
        switch (overflowPolicy) {
            case DROP:
                return;
            case THROW:
                throw new QueueCapacityExceededException();
            case BLOCK:
                if (timed)
                    sendersSync.await(iter, nanos, TimeUnit.NANOSECONDS);
                else
                    sendersSync.await(iter);
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
            if (sendersSync != null)
                sendersSync.signalAll();
        }
    }

    /**
     * This method must only be called by the channel's owner (the receiver)
     */
    @Override
    public boolean isClosed() {
        return receiveClosed;
    }

    boolean isSendClosed() {
        return sendClosed;
    }

    void setReceiveClosed() {
        this.receiveClosed = true;
    }

    @Override
    public Message tryReceive() {
        if (receiveClosed)
            return null;
        boolean closed = isSendClosed();
        final Message m = queue.poll();
        if (m != null)
            signalSenders();
        else if (closed)
            setReceiveClosed();
        return m;
    }

    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        if (receiveClosed)
            return null;

        Message m;
        boolean closed;
        Object token = sync.register();
        for (int i = 0;; i++) {
            closed = isSendClosed(); // must be read BEFORE queue.poll()
            if ((m = queue.poll()) != null)
                break;
            if (closed) {
                setReceiveClosed();
                return null;
            }

            sync.await(i);
        }
        sync.unregister(token);

        assert m != null;
        signalSenders();
        return m;
    }

    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (receiveClosed)
            return null;
        if (unit == null)
            return receive();
        if (timeout <= 0)
            return tryReceive();

        long left = unit.toNanos(timeout);
        final long deadline = System.nanoTime() + left;

        Message m;
        boolean closed;
        Object token = sync.register();
        try {
            for (int i = 0;; i++) {
                closed = isSendClosed(); // must be read BEFORE queue.poll()
                if ((m = queue.poll()) != null)
                    break;
                if (closed) {
                    setReceiveClosed();
                    return null;
                }

                sync.await(i, left, TimeUnit.NANOSECONDS);

                left = deadline - System.nanoTime();
                if (left <= 0)
                    return null;
            }
        } finally {
            sync.unregister(token);
        }

        if (m != null)
            signalSenders();
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

    @Override
    public String toString() {
        return "Channel{" + "sync: " + sync + ", queue: " + Objects.systemToString(queue) + '}';
    }

    protected Object writeReplace() throws java.io.ObjectStreamException {
        return RemoteChannelProxyFactoryService.create(this, null);
    }
}
