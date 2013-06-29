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
import co.paralleluniverse.strands.queues.SingleProducerCircularIntBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
public class TickerIntChannel extends TickerChannel<Integer> implements IntSendPort, IntReceivePort {
    public static TickerIntChannel create(Object owner, int size) {
        return new TickerIntChannel(owner, size);
    }

    public static TickerIntChannel create(int size) {
        return new TickerIntChannel(size);
    }

    public TickerIntChannel(Object owner, int size) {
        super(owner, new SingleProducerCircularIntBuffer(size));
    }

    private TickerIntChannel(int size) {
        super(new SingleProducerCircularIntBuffer(size));
    }

    @Override
    public void send(int message) {
        if (isSendClosed())
            return;
        buffer().enq(message);
        signal();
    }

    @Override
    public boolean trySend(int message) {
        send(message);
        return true;
    }

    @Override
    public int receiveInt() throws SuspendExecution, InterruptedException {
        return ((TickerChannelIntConsumer) consumer).receiveInt();
    }

    @Override
    public int receiveInt(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
        return ((TickerChannelIntConsumer) consumer).receiveInt(timeout, unit);
    }

    @Override
    public TickerChannelIntConsumer newConsumer() {
        return new TickerChannelIntConsumer(this);
    }

    private SingleProducerCircularIntBuffer buffer() {
        return (SingleProducerCircularIntBuffer) buffer;
    }

    public static class TickerChannelIntConsumer extends TickerChannelConsumer<Integer> implements IntReceivePort {
        public TickerChannelIntConsumer(TickerIntChannel channel) {
            super(channel);
        }

        @Override
        public int receiveInt() throws SuspendExecution, InterruptedException {
            attemptReceive();
            return consumer().getIntValue();
        }

        @Override
        public int receiveInt(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException {
            attemptReceive(timeout, unit);
            return consumer().getIntValue();
        }

        private SingleProducerCircularIntBuffer.IntConsumer consumer() {
            return (SingleProducerCircularIntBuffer.IntConsumer) consumer;
        }
    }
}
