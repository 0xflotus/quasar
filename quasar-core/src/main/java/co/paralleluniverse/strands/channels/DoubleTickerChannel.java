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
import co.paralleluniverse.strands.queues.SingleProducerCircularDoubleBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public class DoubleTickerChannel extends TickerChannel<Double> implements DoubleSendChannel, DoubleReceiveChannel {
    public static DoubleTickerChannel create(Object owner, int size) {
        return new DoubleTickerChannel(owner, size);
    }

    public static DoubleTickerChannel create(int size) {
        return new DoubleTickerChannel(size);
    }

    public DoubleTickerChannel(Object owner, int size) {
        super(owner, new SingleProducerCircularDoubleBuffer(size));
    }

    private DoubleTickerChannel(int size) {
        super(new SingleProducerCircularDoubleBuffer(size));
    }

    @Override
    public void send(double message) {
        if (isSendClosed())
            return;
        buffer().enq(message);
        signal();
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

    private SingleProducerCircularDoubleBuffer buffer() {
        return (SingleProducerCircularDoubleBuffer) buffer;
    }

    public static class TickerChannelDoubleConsumer extends TickerChannelConsumer<Double> implements DoubleReceiveChannel {
        public TickerChannelDoubleConsumer(DoubleTickerChannel channel) {
            super(channel);
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

        private SingleProducerCircularDoubleBuffer.DoubleConsumer consumer() {
            return (SingleProducerCircularDoubleBuffer.DoubleConsumer) consumer;
        }
    }
}
