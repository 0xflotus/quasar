/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.strands.channels;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SimpleConditionSynchronizer;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.queues.CircularBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This is a single-producer, multiple consumer channel.
 *
 * @author pron
 */
public abstract class TickerChannel<Message> implements Channel<Message> {
    private Object owner;
    final CircularBuffer<Message> buffer;
    private final SimpleConditionSynchronizer sync;
    private boolean sendClosed;
    final TickerChannelConsumer<Message> consumer;

    protected TickerChannel(Object owner, CircularBuffer<Message> buffer) {
        this.buffer = buffer;
        this.owner = owner;
        this.sync = new SimpleConditionSynchronizer();
        this.consumer = newConsumer();
    }

    protected TickerChannel(CircularBuffer<Message> buffer) {
        this(null, buffer);
    }

    public TickerChannelConsumer<Message> newConsumer() {
        return new TickerChannelConsumer<Message>(this);
    }

    private void setStrand(Strand strand) {
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

    @Override
    public void send(Message message) {
        maybeSetCurrentStrandAsOwner();
        if (isSendClosed())
            return;
        buffer.enq(message);
        signal();
    }

    @Override
    public boolean trySend(Message message) {
        send(message);
        return true;
    }

    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        return consumer.receive();
    }

    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        return consumer.receive(timeout, unit);
    }

    @Override
    public Message tryReceive() throws SuspendExecution, InterruptedException {
        return consumer.tryReceive();
    }

    @Override
    public boolean isClosed() {
        return consumer.isClosed();
    }
    
    @Override
    public void close() {
        maybeSetCurrentStrandAsOwner();
        sendClosed = true;
        signal();
    }

    boolean isSendClosed() {
        return sendClosed;
    }

    protected void signal() {
        sync.signalAll();
    }

    public static class TickerChannelConsumer<Message> implements ReceivePort<Message> {
        final TickerChannel<Message> channel;
        final CircularBuffer.Consumer consumer;
        private boolean receiveClosed;

        public TickerChannelConsumer(TickerChannel<Message> channel) {
            this.channel = channel;
            this.consumer = channel.buffer.newConsumer();
        }

        void setReceiveClosed() {
            this.receiveClosed = true;
        }

        void attemptReceive() throws SuspendExecution, InterruptedException {
            if (isClosed())
                throw new EOFException();
            final SimpleConditionSynchronizer sync = channel.sync;
            sync.lock();
            try {
                while (!consumer.hasNext()) {
                    if (channel.sendClosed) {
                        setReceiveClosed();
                        throw new EOFException();
                    }
                    sync.await();
                }
                consumer.poll0();
            } finally {
                sync.unlock();
            }
        }

        void attemptReceive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
            if (isClosed())
                throw new EOFException();

            final SimpleConditionSynchronizer sync = channel.sync;
            final long start = System.nanoTime();
            long left = unit.toNanos(timeout);

            sync.lock();
            try {
                while (!consumer.hasNext()) {
                    if (channel.sendClosed) {
                        setReceiveClosed();
                        throw new EOFException();
                    }
                    sync.await(left, TimeUnit.NANOSECONDS);

                    left = start + unit.toNanos(timeout) - System.nanoTime();
                    if (left <= 0)
                        throw new TimeoutException();
                }
                consumer.poll0();
            } finally {
                sync.unlock();
            }
        }

        public final long getLastIndexRead() {
            return consumer.lastIndexRead();
        }

        @Override
        public Message tryReceive() throws SuspendExecution, InterruptedException {
            if (!consumer.hasNext())
                return null;
            return (Message) consumer.poll();
        }

        @Override
        public Message receive() throws SuspendExecution, InterruptedException {
            try {
                attemptReceive();
                return (Message) consumer.getAndClearReadValue();
            } catch (EOFException e) {
                return null;
            }
        }

        @Override
        public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
            try {
                attemptReceive(timeout, unit);
                return (Message) consumer.getAndClearReadValue();
            } catch (EOFException e) {
                return null;
            } catch (TimeoutException e) {
                return null;
            }
        }

        @Override
        public void close() {
            setReceiveClosed();
        }

        @Override
        public boolean isClosed() {
            return receiveClosed;
        }
    }
}
