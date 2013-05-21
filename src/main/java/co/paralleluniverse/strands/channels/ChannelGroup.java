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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * A group of channels allowing receiving from all member channels at once.
 * @author pron
 */
public class ChannelGroup<Message> implements ReceiveChannel<Message>, Stranded {
    private Object owner;
    private volatile OwnedSynchronizer sync;
    private final Channel<? extends Message>[] channels;

    /**
     * Creates a channel group
     * @param channels The member channels
     */
    public ChannelGroup(Channel<? extends Message>... channels) {
        this.channels = channels;
    }

    /**
     * Creates a channel group
     * @param channels The member channels
     */
    public ChannelGroup(Collection<? extends Message> channels) {
        this.channels = (Channel<? extends Message>[]) channels.toArray();
    }

    /**
     * Returns the member channels.
     * @return An unmodifiable collection of all channels in this group.
     */
    public Collection<Channel<? extends Message>> getChannels() {
        return Collections.unmodifiableList(Arrays.asList(channels));
    }

    public Object getOwner() {
        return owner;
    }

    @Override
    public void setStrand(Strand strand) {
        if (owner != null && strand != owner)
            throw new IllegalStateException("Channel " + this + " is already owned by " + owner);
        this.owner = strand;
        this.sync = OwnedSynchronizer.create(owner);
    }

    protected void maybeSetCurrentStrandAsOwner() {
        if (owner == null)
            setStrand(Strand.currentStrand());
        else
            sync.verifyOwner();
        setSync();
    }

    protected OwnedSynchronizer sync() {
        verifySync();
        return sync;
    }

    @Override
    public Strand getStrand() {
        return (Strand) owner;
    }

    private void verifySync() {
        if (sync == null)
            throw new IllegalStateException("Owning strand has not been set");
    }

    private void setSync() {
        for (Channel<?> c : channels)
            c.setSync(sync);
    }

    /**
     * Blocks until one of the member channels receives a message, and returns it.
     * @return A message received from one of the channels.
     * @throws SuspendExecution
     * @throws InterruptedException 
     */
    @Override
    public Message receive() throws SuspendExecution, InterruptedException {
        maybeSetCurrentStrandAsOwner();

        sync.lock();
        try {
            for (;;) {
                for (Channel<? extends Message> c : channels) {
                    Message m = c.tryReceive();
                    if (m != null)
                        return m;
                }
                sync.await();
            }
        } finally {
            sync.unlock();
        }
    }
    
    /**
     * Blocks up to a given timeout until one of the member channels receives a message, and returns it.
     * @param timeout
     * @param unit
     * @return A message received from one of the channels, or null if the timeout has expired.
     * @throws SuspendExecution
     * @throws InterruptedException 
     */
    @Override
    public Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (timeout <= 0 || unit == null)
            return receive();

        maybeSetCurrentStrandAsOwner();

        final long start = System.nanoTime();
        long left = unit.toNanos(timeout);

        sync.lock();
        try {
            for (;;) {
                for (Channel<? extends Message> c : channels) {
                    Message m = c.tryReceive();
                    if (m != null)
                        return m;
                }
                
                sync.await(left, TimeUnit.NANOSECONDS);
                left = start + unit.toNanos(timeout) - System.nanoTime();
                if (left <= 0)
                    return null;
            }
        } finally {
            sync.unlock();
        }
    }
}
