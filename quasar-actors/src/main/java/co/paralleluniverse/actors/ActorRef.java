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
package co.paralleluniverse.actors;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.channels.Channels.OverflowPolicy;
import co.paralleluniverse.strands.channels.SendPort;
import java.util.concurrent.TimeUnit;

/**
 * An actor's external API (for use by code not part of the actor).
 *
 * @author pron
 */
public abstract class ActorRef<Message> implements SendPort<Message> {
    public abstract String getName();

    /**
     * Sends a message to the actor, possibly blocking until there's room available in the mailbox.
     *
     * If the channel is full, this method may block or silently drop the message.
     * The behavior is determined by the mailbox's {@link co.paralleluniverse.strands.channels.Channels.OverflowPolicy OverflowPolicy}, set at construction time.
     * However, unlike regular channels, this method never throws {@link co.paralleluniverse.strands.queues.QueueCapacityExceededException QueueCapacityExceededException}.
     * If the mailbox overflows, and has been configured with the {@link co.paralleluniverse.strands.channels.Channels.OverflowPolicy#THROW THROW} policy,
     * the exception will be thrown <i>into</i> the actor.
     *
     * @param message
     * @throws SuspendExecution
     */
    @Override
    public abstract void send(Message message) throws SuspendExecution;

    /**
     * Sends a message to the actor, and attempts to schedule the actor's strand for immediate execution.
     * This method may be called when a response message is expected from this actor; in this case, this method might provide
     * better latency than {@link #send(java.lang.Object)}.
     *
     * @param message
     * @throws SuspendExecution
     */
    public abstract void sendSync(Message message) throws SuspendExecution;

    /**
     * Sends a message to the channel, possibly blocking until there's room available in the channel, but never longer than the
     * specified timeout.
     *
     * If the channel is full, this method may block, throw an exception, silently drop the message, or displace an old message from
     * the channel. The behavior is determined by the channel's {@link OverflowPolicy OverflowPolicy}, set at construction time.
     *
     * @param msg     the message
     * @param timeout the maximum duration this method is allowed to wait.
     * @param unit    the timeout's time unit
     * @return {@code true} if the message has been sent successfully; {@code false} if the timeout has elapsed.
     * @throws SuspendExecution
     */
    @Override
    public abstract boolean send(Message msg, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException;

    /**
     * Sends a message to the channel if the channel has room available. This method never blocks.
     *
     * @param msg the message
     * @return {@code true} if the message has been sent; {@code false} otherwise.
     */
    @Override
    public abstract boolean trySend(Message msg);

    /**
     * This implementation throws {@code UnsupportedOperationException}.
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }

    /**
     * Interrupts the actor's strand
     */
    public abstract void interrupt();

    /**
     * Returns the {@code ActorRef} of the actor currently running in the current strand.
     *
     * @param <T>
     * @param <M>
     * @return The {@link ActorRef} of the current actor (caller of this method)
     */
    public static <T extends ActorRef<M>, M> T self() {
        final Actor a = Actor.currentActor();
        if (a == null)
            return null;
        return (T) a.ref();
    }

    @Override
    public String toString() {
        return "ActorRef{" + '}';
    }
}
