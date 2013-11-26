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
import co.paralleluniverse.actors.ActorRef;
import co.paralleluniverse.actors.ActorUtil;
import co.paralleluniverse.actors.ExitMessage;
import co.paralleluniverse.actors.LifecycleMessage;
import co.paralleluniverse.actors.MailboxConfig;
import co.paralleluniverse.actors.MessageProcessor;
import co.paralleluniverse.actors.SelectiveReceiveHelper;
import co.paralleluniverse.common.util.Exceptions;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.channels.Channels.OverflowPolicy;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class contains static methods that implement a request-reply pattern with actors. These methods can be used to communicate with
 * actors by other actors, or even by non-actor strands.
 *
 * @author pron
 */
public final class RequestReplyHelper {
    private static final ThreadLocal<Long> defaultTimeout = new ThreadLocal<Long>();

    /**
     * Generates a random, probably unique, message identifier. This method simply calls {@link ActorUtil#randtag() }.
     *
     * @return a newly allocated, probably unique, message identifier. 
     */
    public static Object makeId() {
        return ActorUtil.randtag();
    }

    /**
     * Sets a default timeout for non-timed {@link #call(ActorRef, RequestMessage) call}s on this strand. Non-timed calls that take longer
     * than the default timeout, will throw a {@link TimeoutException} wrapped in a {@link RuntimeException}.
     * <p/>
     * This method only affects the current strand.
     *
     * @param timeout the timeout duration
     * @param unit    the time unit of the timeout, or {@code null} to unset.
     */
    public static void setDefaultTimeout(long timeout, TimeUnit unit) {
        if (unit == null)
            defaultTimeout.remove();
        else
            defaultTimeout.set(unit.toNanos(timeout));
    }

    /**
     * Returns an {@link ActorRef} that should be used as the <i>from</i> property of a {@link RequestMessage}. If called
     * from an actor strand, this method returns the current actor. If not, it creates a temporary faux-actor that will be used internally
     * to receive the response, even if the current strand is not running an actor.
     *
     * @param <Message>
     * @return an {@link ActorRef} that should be used as the <i>from</i> property of a request, even if not called from within an actor.
     */
    public static <Message> ActorRef<Message> from() {
        return getCurrentActor();
    }

    /**
     * Sends a request message to an actor, awaits a response value and returns it.
     * This method can be called by any code, even non-actor code.
     * If the actor responds with an error message, a {@link RuntimeException} will be thrown by this method.
     * <br/>
     * The message's {@code id} and {@code from} properties may be left unset.
     * <p/>
     * This method should be used as in the following example (assuming a {@code String} return value:
     * <pre> {@code
     * String res = call(actor, new MyRequest());
     * }</pre>
     * In the example, {@code MyRequest} extends {@link RequestMessage}. Note how the result of the {@link #from() from} method is passed to the
     * request's constructor, but the message ID isn't.
     *
     * @param <V>
     * @param actor the actor to which the request is sent
     * @param m     the {@link RequestMessage}, whose {@code id} and {@code from} properties may be left unset.
     * @return the value sent by the actor as a response
     * @throws RuntimeException     if the actor responds with an error message, its contained exception will be thrown, possibly wrapped by a {@link RuntimeException},
     *                              or if a {@link #setDefaultTimeout(long, TimeUnit) default timeout} has been set and has expired.
     * @throws InterruptedException
     */
    public static <V> V call(ActorRef actor, RequestMessage m) throws InterruptedException, SuspendExecution {
        Long timeout = null;
        try {
            timeout = defaultTimeout.get();
            if (timeout != null)
                return call(actor, m, timeout, TimeUnit.NANOSECONDS);
            else
                return call(actor, m, 0, null);
        } catch (TimeoutException ex) {
            if (timeout != null)
                throw new RuntimeException(ex);
            else
                throw new AssertionError(ex);
        }
    }

    /**
     * Sends a request message to an actor, awaits a response value (but no longer than the given timeout) and returns it.
     * This method can be called by any code, even non-actor code.
     * If the actor responds with an error message, a {@link RuntimeException} will be thrown by this method.
     * <br/>
     * The message's {@code id} and {@code from} properties may be left unset.
     * <p/>
     * This method should be used as in the following example (assuming a {@code String} return value:
     * <pre> {@code
     * String res = call(actor, new MyRequest());
     * }</pre>
     * In the example, {@code MyRequest} extends {@link RequestMessage}. Note how the result of the {@link #from() from} method is passed to the
     * request's constructor, but the message ID isn't.
     *
     * @param <V>
     * @param actor   the actor to which the request is sent
     * @param timeout the maximum duration to wait for a response
     * @param unit    the time unit of the timeout
     * @return the value sent by the actor as a response
     * @throws RuntimeException     if the actor responds with an error message, its contained exception will be thrown, possibly wrapped by a {@link RuntimeException}.
     * @throws TimeoutException     if the timeout expires before a response is received from the actor.
     * @throws InterruptedException
     */
    public static <V> V call(final ActorRef actor, RequestMessage m, long timeout, TimeUnit unit) throws TimeoutException, InterruptedException, SuspendExecution {
        assert !actor.equals(ActorRef.self()) : "Can't \"call\" self - deadlock guaranteed";

        if (m.getFrom() == null)
            m.setFrom(from());

        final Actor currentActor;
        if (m.getFrom() instanceof TempActor)
            currentActor = ((TempActor<?>) m.getFrom()).actor.get();
        else
            currentActor = Actor.currentActor();

        assert currentActor != null;

        final Object watch = currentActor.watch(actor);

        if (m.getId() == null)
            m.setId(watch);

        final Object id = m.getId();

        final SelectiveReceiveHelper<Object> helper = new SelectiveReceiveHelper<Object>(currentActor) {
            @Override
            protected void handleLifecycleMessage(LifecycleMessage m) {
                if (m instanceof ExitMessage) {
                    final ExitMessage exit = (ExitMessage) m;
                    if (Objects.equals(exit.getActor(), actor) && exit.getWatch() == watch)
                        throw Exceptions.rethrow(exit.getCause());
                }
                super.handleLifecycleMessage(m);
            }
        };
        try {
            actor.sendSync(m);
            final ResponseMessage response = (ResponseMessage) helper.receive(timeout, unit, new MessageProcessor<Object, Object>() {
                @Override
                public Object process(Object m) throws SuspendExecution, InterruptedException {
                    return (m instanceof ResponseMessage && id.equals(((ResponseMessage) m).getId())) ? m : null;
                }
            });
            currentActor.unwatch(actor, watch); // no need to unwatch in case of receiver death, so not doen in finally block

            if (response instanceof ErrorResponseMessage)
                throw Exceptions.rethrow(((ErrorResponseMessage) response).getError());
            return ((ValueResponseMessage<V>) response).getValue();
        } finally {
            if (m.getFrom() instanceof TempActor)
                ((TempActor) m.getFrom()).done();
        }
    }

    /**
     * Replies with a result to a {@link RequestMessage}.
     * If the request has been sent by a call to {@link #call(ActorRef, RequestMessage) call}, the
     * {@code result} argument will be the value returned by {@link #call(ActorRef, RequestMessage) call}.
     * This method should only be called by an actor.
     * <p/>
     * Internally this method uses a {@link ValueResponseMessage} to send the reply.
     *
     * @param req    the request we're responding to
     * @param result the result of the request
     */
    public static <V> void reply(RequestMessage req, V result) throws SuspendExecution {
        req.getFrom().send(new ValueResponseMessage<V>(req.getId(), result));
    }

    /**
     * Replies with an exception to a {@link RequestMessage}.
     * If the request has been sent by a call to {@link #call(ActorRef, RequestMessage) call}, the
     * {@code e} argument will be the exception thrown by {@link #call(ActorRef, RequestMessage) call} (possibly wrapped by a {@link RuntimeException}).
     * This method should only be called by an actor.
     * <p/>
     * Internally this method uses an {@link ErrorResponseMessage} to send the reply.
     *
     * @param req the request we're responding to
     * @param e   the error the request has caused
     */
    public static <V> void replyError(RequestMessage req, Throwable e) throws SuspendExecution {
        req.getFrom().send(new ErrorResponseMessage(req.getId(), e));
    }

    private static ActorRef getCurrentActor() {
        ActorRef actorRef = ActorRef.self();
        if (actorRef == null) {
            // create a "dummy actor" on the current strand
            Actor actor = new Actor(Strand.currentStrand(), null, new MailboxConfig(5, OverflowPolicy.THROW)) {
                @Override
                protected Object doRun() throws InterruptedException, SuspendExecution {
                    throw new AssertionError();
                }
            };
            actorRef = new TempActor(actor);
        }
        return actorRef;
    }

    private static class TempActor<Message> extends ActorRef<Message> {
        private WeakReference<Actor<Message, Void>> actor;
        private volatile boolean done = false;

        public TempActor(Actor actor) {
            this.actor = new WeakReference<Actor<Message, Void>>(actor);
        }

        public void done() {
            this.actor = null;
            this.done = true;
        }

        private ActorRef getActor() {
            ActorRef a = null;
            if (actor != null)
                a = actor.get().ref();
            return a;
        }

        private ActorRef actor() {
            final ActorRef a = getActor();
            if (a == null)
                throw new RuntimeException("Temporary actor is out of scope");
            return a;
        }

        @Override
        public String getName() {
            return actor().getName();
        }

        @Override
        public void interrupt() {
            final ActorRef a = getActor();
            if (a != null)
                a.interrupt();
        }

        @Override
        public void send(Message message) throws SuspendExecution {
            final ActorRef a = getActor();
            if (a != null)
                a.send(message);
        }

        @Override
        public void sendSync(Message message) throws SuspendExecution {
            final ActorRef a = getActor();
            if (a != null)
                a.sendSync(message);
        }

        @Override
        public boolean send(Message message, long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
            final ActorRef a = getActor();
            if (a != null)
                return a.send(message, timeout, unit);
            return true;
        }

        @Override
        public boolean trySend(Message message) {
            final ActorRef a = getActor();
            if (a != null)
                return a.trySend(message);
            return true;
        }
    }

    private RequestReplyHelper() {
    }
}
