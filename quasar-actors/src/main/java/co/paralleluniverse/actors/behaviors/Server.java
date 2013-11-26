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
package co.paralleluniverse.actors.behaviors;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ActorBuilder;
import co.paralleluniverse.actors.ActorRef;
import static co.paralleluniverse.actors.behaviors.RequestReplyHelper.from;
import static co.paralleluniverse.actors.behaviors.RequestReplyHelper.makeId;
import co.paralleluniverse.fibers.Joinable;
import co.paralleluniverse.fibers.SuspendExecution;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An interface to a {@link ServerActor}.
 *
 * @author pron
 */
public class Server<CallMessage, V, CastMessage> extends Behavior {
    /**
     * If {@code actor} is known to be a {@link ServerActor}, creates a new {@link Server} interface to it.
     *
     * @param actor a {@link ServerActor}
     */
    public Server(ActorRef<Object> actor) {
        super(actor);
    }

    /**
     * Sends a synchronous request to the actor, and awaits a response.
     * <p/>
     * This method may be safely called by actors and non-actor strands alike.
     *
     * @param m the request
     * @return the value sent as a response from the actor
     * @throws RuntimeException if the actor encountered an error while processing the request
     */
    public final V call(CallMessage m) throws InterruptedException, SuspendExecution {
        try {
            return call(m, 0, null);
        } catch (TimeoutException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * Sends a synchronous request to the actor, and awaits a response, but no longer than the given timeout.
     * <p/>
     * This method may be safely called by actors and non-actor strands alike.
     *
     * @param m       the request
     * @param timeout the maximum duration to wait for a response.
     * @param unit    the time unit of the timeout
     * @return the value sent as a response from the actor
     * @throws RuntimeException if the actor encountered an error while processing the request
     * @throws TimeoutException if the timeout expires before a response has been received.
     */
    public final V call(CallMessage m, long timeout, TimeUnit unit) throws TimeoutException, InterruptedException, SuspendExecution {
        final V res = RequestReplyHelper.call(ref, new ServerRequest(from(), null, MessageType.CALL, m), timeout, unit);
        return res;
    }

    /**
     * Sends an asynchronous request to the actor and returns immediately (may block until there's room available in the actor's mailbox).
     *
     * @param m the request
     */
    public final void cast(CastMessage m) throws SuspendExecution {
        ref.send(new ServerRequest(ActorRef.self(), makeId(), MessageType.CAST, m));
    }

//    public static void cast(ActorRef server, Object m) throws SuspendExecution {
//        server.send(new ServerRequest(ActorRef.self(), makeId(), MessageType.CAST, m));
//    }
    enum MessageType {
        CALL, CAST
    };

    static class ServerRequest extends RequestMessage {
        private final MessageType type;
        private final Object message;

        public ServerRequest(ActorRef sender, Object id, MessageType type, Object message) {
            super(sender, id);
            this.type = type;
            this.message = message;
        }

        public MessageType getType() {
            return type;
        }

        public Object getMessage() {
            return message;
        }
    }

    static final class Local<CallMessage, V, CastMessage> extends Server<CallMessage, V, CastMessage> implements LocalBehavior<Server<CallMessage, V, CastMessage>> {
        Local(ActorRef<Object> actor) {
            super(actor);
        }

        @Override
        public Server<CallMessage, V, CastMessage> writeReplace() throws java.io.ObjectStreamException {
            return new Server<>(ref);
        }

        @Override
        public Actor<Object, Void> build() {
            return ((ActorBuilder<Object, Void>) ref).build();
        }

        @Override
        public void join() throws ExecutionException, InterruptedException {
            ((Joinable<Void>) ref).join();
        }

        @Override
        public void join(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
            ((Joinable<Void>) ref).join(timeout, unit);
        }

        @Override
        public Void get() throws ExecutionException, InterruptedException {
            return ((Joinable<Void>) ref).get();
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
            return ((Joinable<Void>) ref).get(timeout, unit);
        }

        @Override
        public boolean isDone() {
            return ((Joinable<Void>) ref).isDone();
        }
    }
}
