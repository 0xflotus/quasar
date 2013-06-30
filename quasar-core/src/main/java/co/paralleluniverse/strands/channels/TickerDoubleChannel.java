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
import co.paralleluniverse.strands.queues.CircularBuffer.Consumer;
import co.paralleluniverse.strands.queues.CircularDoubleBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public class TickerDoubleChannel extends TickerChannel<Double> implements DoubleChannel {
    public TickerDoubleChannel(int size, boolean singleProducer) {
        super(new CircularDoubleBuffer(size, singleProducer));
    }

    public TickerDoubleChannel(int size) {
        this(size, false);
    }

    @Override
    public void send(double message) {
        if (isSendClosed())
            return;
        buffer().enq(message);
        signal();
    }

    @Override
    public boolean trySend(double message) {
        send(message);
        return true;
    }

    @Override
    public double receiveDouble() throws SuspendExecution, InterruptedException {
        return ((TickerChannelDoubleConsumer) consumer).receiveDouble();
    }

    @Override
    public double receiveDouble(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
        return ((TickerChannelDoubleConsumer) consumer).receiveDouble(timeout, unit);
    }

    @Override
    public TickerChannelDoubleConsumer newConsumer() {
        return new TickerChannelDoubleConsumer(this);
    }

    @Override
    TickerChannelDoubleConsumer builtinConsumer() {
        return new TickerChannelDoubleConsumer(this, buffer.builtinConsumer());
    }

    public static TickerChannelDoubleConsumer newConsumer(DoubleChannel tickerChannel) {
        return ((TickerDoubleChannel) tickerChannel).newConsumer();
    }

    private CircularDoubleBuffer buffer() {
        return (CircularDoubleBuffer) buffer;
    }

    public static class TickerChannelDoubleConsumer extends TickerChannelConsumer<Double> implements DoubleReceivePort {
        public TickerChannelDoubleConsumer(TickerDoubleChannel channel) {
            super(channel);
        }

        public TickerChannelDoubleConsumer(TickerDoubleChannel channel, Consumer consumer) {
            super(channel, consumer);
        }

        @Override
        public double receiveDouble() throws SuspendExecution, InterruptedException {
            attemptReceive();
            return consumer().getDoubleValue();
        }

        @Override
        public double receiveDouble(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
            attemptReceive(timeout, unit);
            return consumer().getDoubleValue();
        }

        private CircularDoubleBuffer.DoubleConsumer consumer() {
            return (CircularDoubleBuffer.DoubleConsumer) consumer;
        }
    }
}
