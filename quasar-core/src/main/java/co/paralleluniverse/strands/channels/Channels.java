/*
 * Quasar: lightweight strands and actors for the JVM.
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

import co.paralleluniverse.strands.queues.ArrayQueue;
import co.paralleluniverse.strands.queues.CircularBuffer;
import co.paralleluniverse.strands.queues.CircularObjectBuffer;
import co.paralleluniverse.strands.queues.SingleConsumerArrayDoubleQueue;
import co.paralleluniverse.strands.queues.SingleConsumerArrayFloatQueue;
import co.paralleluniverse.strands.queues.SingleConsumerArrayIntQueue;
import co.paralleluniverse.strands.queues.SingleConsumerArrayLongQueue;
import co.paralleluniverse.strands.queues.SingleConsumerArrayObjectQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayDoubleQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayFloatQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayIntQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayLongQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedArrayObjectQueue;

/**
 *
 * @author pron
 */
public final class Channels {
    public enum OverflowPolicy {
        THROW, DROP, BLOCK, BACKOFF, DISPLACE
    }
    private static final OverflowPolicy defaultPolicy = OverflowPolicy.THROW;
    private static final boolean defaultSingleProducer = false;
    private static final boolean defaultSingleConsumer = true;

    public static <Message> Channel<Message> newChannel(int mailboxSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (mailboxSize == 0 && policy == OverflowPolicy.BLOCK)
            return new TransferChannel<Message>();

        if (policy == OverflowPolicy.DISPLACE && mailboxSize > 0) {
            if (!singleConsumer)
                throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
            return new QueueObjectChannel<Message>(new CircularObjectBuffer<Message>(mailboxSize, singleProducer), policy, singleConsumer);
        }

        if (policy == OverflowPolicy.BLOCK && mailboxSize == 0)
            return new TransferChannel<Message>();

        if (!singleConsumer && mailboxSize <= 0)
            throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
        return new QueueObjectChannel(
                mailboxSize > 0
                ? (singleConsumer ? new SingleConsumerArrayObjectQueue<Message>(mailboxSize) : new ArrayQueue<Message>(mailboxSize))
                : new SingleConsumerLinkedArrayObjectQueue<Message>(),
                policy,
                singleConsumer);
    }

    public static <Message> Channel<Message> newChannel(int mailboxSize, OverflowPolicy policy) {
        return newChannel(mailboxSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    public static <Message> Channel<Message> newChannel(int mailboxSize) {
        return newChannel(mailboxSize, mailboxSize == 0 ? OverflowPolicy.BLOCK : defaultPolicy);
    }

    ///
    public static IntChannel newIntChannel(int mailboxSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (policy == OverflowPolicy.DISPLACE && mailboxSize >= 0) {
            if (!singleConsumer)
                throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
            return new TickerIntChannel(mailboxSize, singleProducer);
        }

        if (policy == OverflowPolicy.BLOCK && mailboxSize == 0)
            throw new UnsupportedOperationException("Primitive transfer channel is not supported");

        if (!singleConsumer)
            throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
        return new QueueIntChannel(
                mailboxSize > 0
                ? new SingleConsumerArrayIntQueue(mailboxSize)
                : new SingleConsumerLinkedArrayIntQueue(),
                policy);
    }

    public static IntChannel newIntChannel(int mailboxSize, OverflowPolicy policy) {
        return newIntChannel(mailboxSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    public static IntChannel newIntChannel(int mailboxSize) {
        return newIntChannel(mailboxSize, defaultPolicy);
    }

    ///
    public static LongChannel newLongChannel(int mailboxSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (policy == OverflowPolicy.DISPLACE && mailboxSize >= 0) {
            if (!singleConsumer)
                throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
            return new TickerLongChannel(mailboxSize, singleProducer);
        }

        if (policy == OverflowPolicy.BLOCK && mailboxSize == 0)
            throw new UnsupportedOperationException("Primitive transfer channel is not supported");

        if (!singleConsumer)
            throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
        return new QueueLongChannel(
                mailboxSize > 0
                ? new SingleConsumerArrayLongQueue(mailboxSize)
                : new SingleConsumerLinkedArrayLongQueue(),
                policy);
    }

    public static LongChannel newLongChannel(int mailboxSize, OverflowPolicy policy) {
        return newLongChannel(mailboxSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    public static LongChannel newLongChannel(int mailboxSize) {
        return newLongChannel(mailboxSize, defaultPolicy);
    }

    ///
    public static FloatChannel newFloatChannel(int mailboxSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (policy == OverflowPolicy.DISPLACE && mailboxSize >= 0) {
            if (!singleConsumer)
                throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
            return new TickerFloatChannel(mailboxSize, singleProducer);
        }

        if (policy == OverflowPolicy.BLOCK && mailboxSize == 0)
            throw new UnsupportedOperationException("Primitive transfer channel is not supported");

        if (!singleConsumer)
            throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
        return new QueueFloatChannel(
                mailboxSize > 0
                ? new SingleConsumerArrayFloatQueue(mailboxSize)
                : new SingleConsumerLinkedArrayFloatQueue(),
                policy);
    }

    public static FloatChannel newFloatChannel(int mailboxSize, OverflowPolicy policy) {
        return newFloatChannel(mailboxSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    public static FloatChannel newFloatChannel(int mailboxSize) {
        return newFloatChannel(mailboxSize, defaultPolicy);
    }

    ///
    public static DoubleChannel newDoubleChannel(int mailboxSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (policy == OverflowPolicy.DISPLACE && mailboxSize >= 0) {
            if (!singleConsumer)
                throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
            return new TickerDoubleChannel(mailboxSize, singleProducer);
        }

        if (policy == OverflowPolicy.BLOCK && mailboxSize == 0)
            throw new UnsupportedOperationException("Primitive transfer channel is not supported");

        if (!singleConsumer)
            throw new UnsupportedOperationException("Channel with given configuration is not supported for multiple consumers");
        return new QueueDoubleChannel(
                mailboxSize > 0
                ? new SingleConsumerArrayDoubleQueue(mailboxSize)
                : new SingleConsumerLinkedArrayDoubleQueue(),
                policy);
    }

    public static DoubleChannel newDoubleChannel(int mailboxSize, OverflowPolicy policy) {
        return newDoubleChannel(mailboxSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    public static DoubleChannel newDoubleChannel(int mailboxSize) {
        return newDoubleChannel(mailboxSize, defaultPolicy);
    }

    ///
    public static <Message> TickerChannel<Message> newTickerChannel(int bufferSize, boolean singleProducer) {
        return new TickerObjectChannel<Message>(bufferSize, singleProducer);
    }

    public static <Message> TickerChannel<Message> newTickerChannel(int bufferSize) {
        return new TickerObjectChannel<Message>(bufferSize, false);
    }

    public static TickerIntChannel newTickerIntChannel(int bufferSize, boolean singleProducer) {
        return new TickerIntChannel(bufferSize, singleProducer);
    }

    public static TickerIntChannel newTickerIntChannel(int bufferSize) {
        return new TickerIntChannel(bufferSize, false);
    }

    public static TickerLongChannel newTickerLongChannel(int bufferSize, boolean singleProducer) {
        return new TickerLongChannel(bufferSize, singleProducer);
    }

    public static TickerLongChannel newTickerLongChannel(int bufferSize) {
        return new TickerLongChannel(bufferSize, false);
    }

    public static TickerFloatChannel newTickerFloatChannel(int bufferSize, boolean singleProducer) {
        return new TickerFloatChannel(bufferSize, singleProducer);
    }

    public static TickerFloatChannel newTickerFloatChannel(int bufferSize) {
        return new TickerFloatChannel(bufferSize, false);
    }

    public static TickerDoubleChannel newTickerDoubleChannel(int bufferSize, boolean singleProducer) {
        return new TickerDoubleChannel(bufferSize, singleProducer);
    }

    public static TickerDoubleChannel newTickerDoubleChannel(int bufferSize) {
        return new TickerDoubleChannel(bufferSize, false);
    }

    private Channels() {
    }
}
