/*
 * Quasar: lightweight strands and actors for the JVM.
 * Copyright (c) 2013-2014, Parallel Universe Software Co. All rights reserved.
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

import co.paralleluniverse.common.util.Function2;
import co.paralleluniverse.common.util.Function3;
import co.paralleluniverse.common.util.Function4;
import co.paralleluniverse.common.util.Function5;
import co.paralleluniverse.strands.queues.ArrayQueue;
import co.paralleluniverse.strands.queues.BasicQueue;
import co.paralleluniverse.strands.queues.BasicSingleConsumerDoubleQueue;
import co.paralleluniverse.strands.queues.BasicSingleConsumerFloatQueue;
import co.paralleluniverse.strands.queues.BasicSingleConsumerIntQueue;
import co.paralleluniverse.strands.queues.BasicSingleConsumerLongQueue;
import co.paralleluniverse.strands.queues.BoxQueue;
import co.paralleluniverse.strands.queues.CircularDoubleBuffer;
import co.paralleluniverse.strands.queues.CircularFloatBuffer;
import co.paralleluniverse.strands.queues.CircularIntBuffer;
import co.paralleluniverse.strands.queues.CircularLongBuffer;
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
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import java.util.Collection;
import java.util.List;

/**
 * A utility class for creating and manipulating channels.
 *
 * @author pron
 */
public final class Channels {
    /**
     * Determines how a channel behaves when its internal buffer (if it has one) overflows.
     */
    public enum OverflowPolicy {
        /**
         * The sender will get an exception (except if the channel is an actor's mailbox)
         */
        THROW,
        /**
         * The message will be silently dropped.
         */
        DROP,
        /**
         * The sender will block until there's a vacancy in the channel.
         */
        BLOCK,
        /**
         * The sender will block for some time, and retry.
         */
        BACKOFF,
        /**
         * The oldest message in the queue will be removed to make room for the new message.
         */
        DISPLACE
    }
    private static final OverflowPolicy defaultPolicy = OverflowPolicy.BLOCK;
    private static final boolean defaultSingleProducer = false;
    private static final boolean defaultSingleConsumer = true;

    /**
     * Creates a new channel with the given properties.
     * <p/>
     * Some combinations of properties are unsupported, and will throw an {@code IllegalArgumentException} if requested:
     *
     * <ul>
     * <li>unbounded channel with multiple consumers</li>
     * <li>a transfer channel with any overflow policy other than {@link OverflowPolicy#BLOCK BLOCK}</li>
     * <li>An overflow policy of {@link OverflowPolicy#DISPLACE DISPLACE} with multiple consumers.</li>
     * </ul>
     * An unbounded channel ignores its overflow policy as it never overflows.
     *
     *
     * @param <Message>      the type of messages that can be sent to this channel.
     * @param bufferSize     if positive, the number of messages that the channel can hold in an internal buffer;
     *                       {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                       {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy         the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @param singleProducer whether the channel will be used by a single producer strand.
     * @param singleConsumer whether the channel will be used by a single consumer strand.
     * @return The newly created channel
     */
    public static <Message> Channel<Message> newChannel(int bufferSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (bufferSize == 0) {
            if (policy != OverflowPolicy.BLOCK)
                throw new IllegalArgumentException("Cannot use policy " + policy + " for channel with size 0 (only BLOCK supported");
            return new TransferChannel<Message>();
        }

        final BasicQueue<Message> queue;
        if (bufferSize < 0) {
            if (!singleConsumer)
                throw new IllegalArgumentException("Unbounded queue with multiple consumers is unsupported");
            queue = new SingleConsumerLinkedArrayObjectQueue<Message>();
        } else if (bufferSize == 1)
            queue = new BoxQueue<Message>(policy == OverflowPolicy.DISPLACE, singleConsumer);
        else if (policy == OverflowPolicy.DISPLACE) {
            if (!singleConsumer)
                throw new IllegalArgumentException("Channel with DISPLACE policy configuration is not supported for multiple consumers");
            queue = new CircularObjectBuffer<Message>(bufferSize, singleProducer);
        } else if (singleConsumer)
            queue = new SingleConsumerArrayObjectQueue<Message>(bufferSize);
        else
            queue = new ArrayQueue<Message>(bufferSize);


        return new QueueObjectChannel(queue, policy, singleConsumer);
    }

    /**
     * Creates a new channel with the given mailbox size and {@link OverflowPolicy}, with other properties set to their default values.
     * Specifically, {@code singleProducer} will be set to {@code false}, while {@code singleConsumer} will be set to {@code true}.
     *
     * @param <Message>  the type of messages that can be sent to this channel.
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy     the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @return The newly created channel
     * @see #newChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static <Message> Channel<Message> newChannel(int bufferSize, OverflowPolicy policy) {
        return newChannel(bufferSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    /**
     * Creates a new channel with the given mailbox size with other properties set to their default values.
     * Specifically, the {@link OverflowPolicy} will be set to {@link OverflowPolicy#BLOCK BLOCK},
     * {@code singleProducer} will be set to {@code false}, and {@code singleConsumer} will be set to {@code true}.
     *
     * @param <Message>  the type of messages that can be sent to this channel.
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @return The newly created channel
     * @see #newChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static <Message> Channel<Message> newChannel(int bufferSize) {
        return newChannel(bufferSize, bufferSize == 0 ? OverflowPolicy.BLOCK : defaultPolicy);
    }

    /**
     * Creates a new primitive {@code int} channel with the given properties.
     * <p/>
     * Some combinations of properties are unsupported, and will throw an {@code IllegalArgumentException} if requested:
     *
     * <ul>
     * <li>multiple consumers</li>
     * <li>a transfer channel with any overflow policy other than {@link OverflowPolicy#BLOCK BLOCK}</li>
     * <li>An overflow policy of {@link OverflowPolicy#DISPLACE DISPLACE} with multiple consumers.</li>
     * </ul>
     * An unbounded channel ignores its overflow policy as it never overflows.
     *
     * @param bufferSize     if positive, the number of messages that the channel can hold in an internal buffer;
     *                       {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                       {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy         the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @param singleProducer whether the channel will be used by a single producer strand.
     * @param singleConsumer whether the channel will be used by a single consumer strand. Currently primitive channels only support a single
     *                       consumer, so this argument must be set to {@code false}.
     * @return The newly created channel
     */
    public static IntChannel newIntChannel(int bufferSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (!singleConsumer)
            throw new IllegalArgumentException("Primitive queue with multiple consumers is unsupported");

        final BasicSingleConsumerIntQueue queue;
        if (bufferSize < 0) {
            queue = new SingleConsumerLinkedArrayIntQueue();
        } else if (policy == OverflowPolicy.DISPLACE) {
            queue = new CircularIntBuffer(bufferSize, singleProducer);
        } else
            queue = new SingleConsumerArrayIntQueue(bufferSize);

        return new QueueIntChannel(queue, policy);
    }

    /**
     * Creates a new primitive {@code int} channel with the given mailbox size and {@link OverflowPolicy}, with other properties set to their default values.
     * Specifically, {@code singleProducer} will be set to {@code false}, while {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy     the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @return The newly created channel
     * @see #newIntChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static IntChannel newIntChannel(int bufferSize, OverflowPolicy policy) {
        return newIntChannel(bufferSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    /**
     * Creates a new primitive {@code int} channel with the given mailbox size with other properties set to their default values.
     * Specifically, the {@link OverflowPolicy} will be set to {@link OverflowPolicy#BLOCK BLOCK},
     * {@code singleProducer} will be set to {@code false}, and {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @return The newly created channel
     * @see #newIntChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static IntChannel newIntChannel(int bufferSize) {
        return newIntChannel(bufferSize, defaultPolicy);
    }

    ///
    /**
     * Creates a new primitive {@code long} channel with the given properties.
     * <p/>
     * Some combinations of properties are unsupported, and will throw an {@code IllegalArgumentException} if requested:
     *
     * <ul>
     * <li>multiple consumers</li>
     * <li>a transfer channel with any overflow policy other than {@link OverflowPolicy#BLOCK BLOCK}</li>
     * <li>An overflow policy of {@link OverflowPolicy#DISPLACE DISPLACE} with multiple consumers.</li>
     * </ul>
     * An unbounded channel ignores its overflow policy as it never overflows.
     *
     * @param bufferSize     if positive, the number of messages that the channel can hold in an internal buffer;
     *                       {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                       {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy         the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @param singleProducer whether the channel will be used by a single producer strand.
     * @param singleConsumer whether the channel will be used by a single consumer strand. Currently primitive channels only support a single
     *                       consumer, so this argument must be set to {@code false}.
     * @return The newly created channel
     */
    public static LongChannel newLongChannel(int bufferSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (!singleConsumer)
            throw new IllegalArgumentException("Primitive queue with multiple consumers is unsupported");

        final BasicSingleConsumerLongQueue queue;
        if (bufferSize < 0) {
            queue = new SingleConsumerLinkedArrayLongQueue();
        } else if (policy == OverflowPolicy.DISPLACE) {
            queue = new CircularLongBuffer(bufferSize, singleProducer);
        } else
            queue = new SingleConsumerArrayLongQueue(bufferSize);

        return new QueueLongChannel(queue, policy);
    }

    /**
     * Creates a new primitive {@code long} channel with the given mailbox size and {@link OverflowPolicy}, with other properties set to their default values.
     * Specifically, {@code singleProducer} will be set to {@code false}, while {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy     the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @return The newly created channel
     * @see #newLongChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static LongChannel newLongChannel(int bufferSize, OverflowPolicy policy) {
        return newLongChannel(bufferSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    /**
     * Creates a new primitive {@code long} channel with the given mailbox size with other properties set to their default values.
     * Specifically, the {@link OverflowPolicy} will be set to {@link OverflowPolicy#BLOCK BLOCK},
     * {@code singleProducer} will be set to {@code false}, and {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @return The newly created channel
     * @see #newLongChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static LongChannel newLongChannel(int bufferSize) {
        return newLongChannel(bufferSize, defaultPolicy);
    }

    ///
    /**
     * Creates a new primitive {@code float} channel with the given properties.
     * <p/>
     * Some combinations of properties are unsupported, and will throw an {@code IllegalArgumentException} if requested:
     *
     * <ul>
     * <li>multiple consumers</li>
     * <li>a transfer channel with any overflow policy other than {@link OverflowPolicy#BLOCK BLOCK}</li>
     * <li>An overflow policy of {@link OverflowPolicy#DISPLACE DISPLACE} with multiple consumers.</li>
     * </ul>
     * An unbounded channel ignores its overflow policy as it never overflows.
     *
     * @param bufferSize     if positive, the number of messages that the channel can hold in an internal buffer;
     *                       {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                       {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy         the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @param singleProducer whether the channel will be used by a single producer strand.
     * @param singleConsumer whether the channel will be used by a single consumer strand. Currently primitive channels only support a single
     *                       consumer, so this argument must be set to {@code false}.
     * @return The newly created channel
     */
    public static FloatChannel newFloatChannel(int bufferSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (!singleConsumer)
            throw new IllegalArgumentException("Primitive queue with multiple consumers is unsupported");

        final BasicSingleConsumerFloatQueue queue;
        if (bufferSize < 0) {
            queue = new SingleConsumerLinkedArrayFloatQueue();
        } else if (policy == OverflowPolicy.DISPLACE) {
            queue = new CircularFloatBuffer(bufferSize, singleProducer);
        } else
            queue = new SingleConsumerArrayFloatQueue(bufferSize);

        return new QueueFloatChannel(queue, policy);
    }

    /**
     * Creates a new primitive {@code float} channel with the given mailbox size and {@link OverflowPolicy}, with other properties set to their default values.
     * Specifically, {@code singleProducer} will be set to {@code false}, while {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy     the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @return The newly created channel
     * @see #newFloatChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static FloatChannel newFloatChannel(int bufferSize, OverflowPolicy policy) {
        return newFloatChannel(bufferSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    /**
     * Creates a new primitive {@code float} channel with the given mailbox size with other properties set to their default values.
     * Specifically, the {@link OverflowPolicy} will be set to {@link OverflowPolicy#BLOCK BLOCK},
     * {@code singleProducer} will be set to {@code false}, and {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @return The newly created channel
     * @see #newFloatChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static FloatChannel newFloatChannel(int bufferSize) {
        return newFloatChannel(bufferSize, defaultPolicy);
    }

    ///
    /**
     * Creates a new primitive {@code double} channel with the given properties.
     * <p/>
     * Some combinations of properties are unsupported, and will throw an {@code IllegalArgumentException} if requested:
     *
     * <ul>
     * <li>multiple consumers</li>
     * <li>a transfer channel with any overflow policy other than {@link OverflowPolicy#BLOCK BLOCK}</li>
     * <li>An overflow policy of {@link OverflowPolicy#DISPLACE DISPLACE} with multiple consumers.</li>
     * </ul>
     * An unbounded channel ignores its overflow policy as it never overflows.
     *
     * @param bufferSize     if positive, the number of messages that the channel can hold in an internal buffer;
     *                       {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                       {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy         the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @param singleProducer whether the channel will be used by a single producer strand.
     * @param singleConsumer whether the channel will be used by a single consumer strand. Currently primitive channels only support a single
     *                       consumer, so this argument must be set to {@code false}.
     * @return The newly created channel
     */
    public static DoubleChannel newDoubleChannel(int bufferSize, OverflowPolicy policy, boolean singleProducer, boolean singleConsumer) {
        if (!singleConsumer)
            throw new IllegalArgumentException("Primitive queue with multiple consumers is unsupported");

        final BasicSingleConsumerDoubleQueue queue;
        if (bufferSize < 0) {
            queue = new SingleConsumerLinkedArrayDoubleQueue();
        } else if (policy == OverflowPolicy.DISPLACE) {
            queue = new CircularDoubleBuffer(bufferSize, singleProducer);
        } else
            queue = new SingleConsumerArrayDoubleQueue(bufferSize);

        return new QueueDoubleChannel(queue, policy);
    }

    /**
     * Creates a new primitive {@code double} channel with the given mailbox size and {@link OverflowPolicy}, with other properties set to their default values.
     * Specifically, {@code singleProducer} will be set to {@code false}, while {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @param policy     the {@link OverflowPolicy} specifying how the channel (if bounded) will behave if its internal buffer overflows.
     * @return The newly created channel
     * @see #newDoubleChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static DoubleChannel newDoubleChannel(int bufferSize, OverflowPolicy policy) {
        return newDoubleChannel(bufferSize, policy, defaultSingleProducer, defaultSingleConsumer);
    }

    /**
     * Creates a new primitive {@code double} channel with the given mailbox size with other properties set to their default values.
     * Specifically, the {@link OverflowPolicy} will be set to {@link OverflowPolicy#BLOCK BLOCK},
     * {@code singleProducer} will be set to {@code false}, and {@code singleConsumer} will be set to {@code true}.
     *
     * @param bufferSize if positive, the number of messages that the channel can hold in an internal buffer;
     *                   {@code 0} for a <i>transfer</i> channel, i.e. a channel with no internal buffer.
     *                   {@code -1} for a channel with an unbounded (infinite) buffer.
     * @return The newly created channel
     * @see #newDoubleChannel(int, co.paralleluniverse.strands.channels.Channels.OverflowPolicy, boolean, boolean)
     */
    public static DoubleChannel newDoubleChannel(int bufferSize) {
        return newDoubleChannel(bufferSize, defaultPolicy);
    }

    ///
    /**
     * Creates a {@link ReceivePort} that can be used to receive messages from a a <i>ticker channel</i>:
     * a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * Each ticker consumer will yield monotonic messages, namely no message will be received more than once, and the messages will
     * be received in the order they're sent, but if the consumer is too slow, messages could be lost.
     *
     * @param <Message> the message type
     * @param channel   a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * @return a new {@link ReceivePort} which provides a view to the supplied ticker channel.
     */
    public static <Message> ReceivePort<Message> newTickerConsumerFor(Channel<Message> channel) {
        return TickerChannelConsumer.newFor((QueueChannel<Message>) channel);
    }

    /**
     * Creates an {@link IntReceivePort} that can be used to receive messages from a a <i>ticker channel</i>:
     * a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * Each ticker consumer will yield monotonic messages, namely no message will be received more than once, and the messages will
     * be received in the order they're sent, but if the consumer is too slow, messages could be lost.
     *
     * @param channel an {@code int} channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * @return a new {@link IntReceivePort} which provides a view to the supplied ticker channel.
     */
    public static IntReceivePort newTickerConsumerFor(IntChannel channel) {
        return TickerChannelConsumer.newFor((QueueIntChannel) channel);
    }

    /**
     * Creates a {@link LongReceivePort} that can be used to receive messages from a a <i>ticker channel</i>:
     * a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * Each ticker consumer will yield monotonic messages, namely no message will be received more than once, and the messages will
     * be received in the order they're sent, but if the consumer is too slow, messages could be lost.
     *
     * @param channel a {@code long} channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * @return a new {@link LongReceivePort} which provides a view to the supplied ticker channel.
     */
    public static LongReceivePort newTickerConsumerFor(LongChannel channel) {
        return TickerChannelConsumer.newFor((QueueLongChannel) channel);
    }

    /**
     * Creates a {@link FloatReceivePort} that can be used to receive messages from a a <i>ticker channel</i>:
     * a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * Each ticker consumer will yield monotonic messages, namely no message will be received more than once, and the messages will
     * be received in the order they're sent, but if the consumer is too slow, messages could be lost.
     *
     * @param channel a {@code float} channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * @return a new {@link FloatReceivePort} which provides a view to the supplied ticker channel.
     */
    public static FloatReceivePort newTickerConsumerFor(FloatChannel channel) {
        return TickerChannelConsumer.newFor((QueueFloatChannel) channel);
    }

    /**
     * Creates a {@link DoubleReceivePort} that can be used to receive messages from a a <i>ticker channel</i>:
     * a channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * Each ticker consumer will yield monotonic messages, namely no message will be received more than once, and the messages will
     * be received in the order they're sent, but if the consumer is too slow, messages could be lost.
     *
     * @param channel a {@code double} channel of bounded capacity and the {@link OverflowPolicy#DISPLACE DISPLACE} overflow policy.
     * @return a new {@link DoubleReceivePort} which provides a view to the supplied ticker channel.
     */
    public static DoubleReceivePort newTickerConsumerFor(DoubleChannel channel) {
        return TickerChannelConsumer.newFor((QueueDoubleChannel) channel);
    }

    ////////////////////
    /**
     * Returns a {@link ReceivePort} that receives messages from a set of channels. Messages from all given channels are funneled into
     * the returned channel.
     *
     * @param <M>
     * @param channels
     * @return a {@link ReceivePort} that receives messages from {@code channels}.
     */
    public static <M> ReceivePort<M> group(ReceivePort<? extends M>... channels) {
        return new ReceivePortGroup<M>(channels);
    }

    /**
     * Returns a {@link ReceivePort} that receives messages from a set of channels. Messages from all given channels are funneled into
     * the returned channel.
     *
     * @param <M>
     * @param channels
     * @return a {@link ReceivePort} that receives messages from {@code channels}.
     */
    public static <M> ReceivePort<M> group(Collection<? extends ReceivePort<? extends M>> channels) {
        return new ReceivePortGroup<M>(channels);
    }

    /**
     * Returns a {@link ReceivePort} that filters messages that satisfy a predicate from a given channel.
     * All messages (even those not satisfying the predicate) will be consumed from the original channel; those that don't satisfy the predicate will be silently discarded.
     * <p/>
     * The returned {@code ReceivePort} has the same {@link Object#hashCode() hashCode} as {@code channel} and is {@link Object#equals(Object) equal} to it.
     *
     * @param <M>     the message type.
     * @param channel The channel to filter
     * @param pred    the filtering predicate
     * @return A {@link ReceivePort} that will receive all those messages from the original channel which satisfy the predicate (i.e. the predicate returns {@code true}).
     */
    public static <M> ReceivePort<M> filter(ReceivePort<M> channel, Predicate<M> pred) {
        return new FilteringReceivePort<M>(channel, pred);
    }

    /**
     * Returns a {@link ReceivePort} that receives messages that are transformed by a given mapping function from a given channel.
     * <p/>
     * The returned {@code ReceivePort} has the same {@link Object#hashCode() hashCode} as {@code channel} and is {@link Object#equals(Object) equal} to it.
     *
     * @param <S>     the message type of the source (given) channel.
     * @param <T>     the message type of the target (returned) channel.
     * @param channel the channel to transform
     * @param f       the mapping function
     * @return a {@link ReceivePort} that returns messages that are the result of applying the mapping function to the messages received on the given channel.
     */
    public static <S, T> ReceivePort<T> map(ReceivePort<S> channel, Function<S, T> f) {
        return new MappingReceivePort<S, T>(channel, f);
    }

    /**
     * Returns a {@link ReceivePort} that combines each vector of messages from a list of channels into a single combined message.
     *
     * @param <M> The type of the combined message
     * @param f   The combining function
     * @param cs  A vector of channels
     * @return A zipping {@link ReceivePort}
     */
    public static <M> ReceivePort<M> zip(List<? extends ReceivePort<?>> cs, Function<Object[], M> f) {
        return new ZippingReceivePort<M>(f, cs);
    }

    /**
     * Returns a {@link ReceivePort} that combines each vector of messages from a vector of channels into a single combined message.
     *
     * @param <M> The type of the combined message
     * @param f   The combining function
     * @return A zipping {@link ReceivePort}
     */
    public static <M, S1, S2> ReceivePort<M> zip(ReceivePort<S1> c1, ReceivePort<S2> c2, final Function2<S1, S2, M> f) {
        return new ZippingReceivePort<M>(c1, c2) {
            @Override
            protected M transform(Object[] ms) {
                return f.apply((S1) ms[0], (S2) ms[1]);
            }
        };
    }

    /**
     * Returns a {@link ReceivePort} that combines each vector of messages from a vector of channels into a single combined message.
     *
     * @param <M> The type of the combined message
     * @param f   The combining function
     * @return A zipping {@link ReceivePort}
     */
    public static <M, S1, S2, S3> ReceivePort<M> zip(ReceivePort<S1> c1, ReceivePort<S2> c2, ReceivePort<S3> c3, final Function3<S1, S2, S3, M> f) {
        return new ZippingReceivePort<M>(c1, c2, c3) {
            @Override
            protected M transform(Object[] ms) {
                return f.apply((S1) ms[0], (S2) ms[1], (S3) ms[2]);
            }
        };
    }

    /**
     * Returns a {@link ReceivePort} that combines each vector of messages from a vector of channels into a single combined message.
     *
     * @param <M> The type of the combined message
     * @param f   The combining function
     * @return A zipping {@link ReceivePort}
     */
    public static <M, S1, S2, S3, S4> ReceivePort<M> zip(ReceivePort<S1> c1, ReceivePort<S2> c2, ReceivePort<S3> c3, ReceivePort<S4> c4,
            final Function4<S1, S2, S3, S4, M> f) {
        return new ZippingReceivePort<M>(c1, c2, c3, c4) {
            @Override
            protected M transform(Object[] ms) {
                return f.apply((S1) ms[0], (S2) ms[1], (S3) ms[2], (S4) ms[3]);
            }
        };
    }

    /**
     * Returns a {@link ReceivePort} that combines each vector of messages from a vector of channels into a single combined message.
     *
     * @param <M> The type of the combined message
     * @param f   The combining function
     * @return A zipping {@link ReceivePort}
     */
    public static <M, S1, S2, S3, S4, S5> ReceivePort<M> zip(ReceivePort<S1> c1, ReceivePort<S2> c2, ReceivePort<S3> c3, ReceivePort<S4> c4, ReceivePort<S5> c5,
            final Function5<S1, S2, S3, S4, S5, M> f) {
        return new ZippingReceivePort<M>(c1, c2, c3, c4, c5) {
            @Override
            protected M transform(Object[] ms) {
                return f.apply((S1) ms[0], (S2) ms[1], (S3) ms[2], (S4) ms[3], (S5) ms[4]);
            }
        };
    }

    /**
     * Returns a {@link SendPort} that filters messages that satisfy a predicate before sending to a given channel.
     * Messages that don't satisfy the predicate will be silently discarded when sent.
     * <p/>
     * The returned {@code SendPort} has the same {@link Object#hashCode() hashCode} as {@code channel} and is {@link Object#equals(Object) equal} to it.
     *
     * @param <M>     the message type.
     * @param channel The channel to filter
     * @param pred    the filtering predicate
     * @return A {@link SendPort} that will send only those messages which satisfy the predicate (i.e. the predicate returns {@code true}) to the given channel.
     */
    public static <M> SendPort<M> filter(SendPort<M> channel, Predicate<M> pred) {
        return new FilteringSendPort<M>(channel, pred);
    }

    /**
     * Returns a {@link SendPort} that transforms messages by applying a given mapping function before sending them to a given channel.
     * <p/>
     * The returned {@code SendPort} has the same {@link Object#hashCode() hashCode} as {@code channel} and is {@link Object#equals(Object) equal} to it.
     *
     * @param <S>     the message type of the source (returned) channel.
     * @param <T>     the message type of the target (given) channel.
     * @param channel the channel to transform
     * @param f       the mapping function
     * @return a {@link SendPort} that passes messages to the given channel after transforming them by applying the mapping function.
     */
    public static <S, T> SendPort<S> map(SendPort<T> channel, Function<S, T> f) {
        return new MappingSendPort<S, T>(channel, f);
    }

    private Channels() {
    }
}
