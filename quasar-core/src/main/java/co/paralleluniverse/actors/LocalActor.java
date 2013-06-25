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

import co.paralleluniverse.common.util.Objects;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.Joinable;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.remote.RemoteProxyFactoryService;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.Stranded;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.channels.Mailbox;
import co.paralleluniverse.strands.channels.ReceiveChannel;
import co.paralleluniverse.strands.queues.QueueCapacityExceededException;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jsr166e.ConcurrentHashMapV8;

/**
 *
 * @author pron
 */
public abstract class LocalActor<Message, V> extends ActorImpl<Message> implements SuspendableCallable<V>, Joinable<V>, Stranded, ReceiveChannel<Message>, ActorBuilder<Message, V> {
    private static final ThreadLocal<LocalActor> currentActor = new ThreadLocal<LocalActor>();
    private Strand strand;
    private final MailboxConfig mailboxConfig;
    private final Set<LifecycleListener> lifecycleListeners = Collections.newSetFromMap(new ConcurrentHashMapV8<LifecycleListener, Boolean>());
    private volatile V result;
    private volatile RuntimeException exception;
    private volatile Throwable deathCause;
    private volatile Object globalId;
    private ActorMonitor monitor;
    private ActorSpec<?, Message, V> spec;

    public LocalActor(Object name, MailboxConfig mailboxConfig) {
        super(name,
                Mailbox.create(mailboxConfig != null ? mailboxConfig.getMailboxSize() : -1));
        this.mailboxConfig = mailboxConfig;
    }

    public LocalActor(Strand strand, String name, MailboxConfig mailboxConfig) {
        this(name, mailboxConfig);
        if (strand != null)
            setStrand(strand);
    }

    public static <T extends LocalActor<Message, V>, Message, V> T newActor(Class<T> clazz, Object... params) {
        return newActor(ActorSpec.of(clazz, params));
    }

    public static <T extends LocalActor<Message, V>, Message, V> T newActor(ActorSpec<T, Message, V> spec) {
        return spec.build();
    }

    @Override
    public final LocalActor<Message, V> build() {
        if (!isDone())
            throw new IllegalStateException("Actor " + this + " isn't dead. Cannot build a copy");

        final LocalActor newInstance = reinstantiate();

        if (newInstance.getName() == null)
            newInstance.setName(this.getName());
        newInstance.strand = null;
        newInstance.setMonitor(this.monitor);
        monitor.setActor(newInstance);
        if (getName() != null && ActorRegistry.getActor(getName()) == this)
            newInstance.register();
        return newInstance;
    }

    /**
     * '
     * Returns a "clone" of this actor, used by a {@link co.paralleluniverse.actors.behaviors.Supervisor supervisor} to restart this actor if it dies.
     * <p/>
     * If this actor is supervised by a {@link co.paralleluniverse.actors.behaviors.Supervisor supervisor} and was not created with the
     * {@link #newActor(co.paralleluniverse.actors.ActorSpec) newActor} factory method, then this method should be overridden.
     *
     * @return A new LocalActor instance that's a clone of this.
     */
    protected LocalActor<Message, V> reinstantiate() {
        if (spec != null)
            return newActor(spec);
        else if (getClass().isAnonymousClass() && getClass().getSuperclass().equals(LocalActor.class))
            return newActor(createSpecForAnonymousClass());
        else
            throw new RuntimeException("Actor " + this + " cannot be reinstantiated");
    }

    private ActorSpec<LocalActor<Message, V>, Message, V> createSpecForAnonymousClass() {
        assert getClass().isAnonymousClass() && getClass().getSuperclass().equals(LocalActor.class);
        Constructor<LocalActor<Message, V>> ctor = (Constructor<LocalActor<Message, V>>) getClass().getDeclaredConstructors()[0];
        Object[] params = new Object[ctor.getParameterTypes().length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = ctor.getParameterTypes()[i];
            if (String.class.equals(type))
                params[i] = getName();
            if (Integer.TYPE.equals(type))
                params[i] = mailbox().capacity();
            else
                params[i] = type.isPrimitive() ? 0 : null;
        }
        return new ActorSpec<LocalActor<Message, V>, Message, V>(ctor, params);
    }

    void setSpec(ActorSpec<?, Message, V> spec) {
        this.spec = spec;
    }

    @Override
    public String toString() {
        String className = getClass().getSimpleName();
        if (className.isEmpty())
            className = getClass().getName().substring(getClass().getPackage().getName().length() + 1);
        return className + "@"
                + (getName() != null ? getName() : Integer.toHexString(System.identityHashCode(this)))
                + "[owner: " + systemToStringWithSimpleName(strand) + ']';
    }

    private static String systemToStringWithSimpleName(Object obj) {
        return (obj == null ? "null" : obj.getClass().getSimpleName() + "@" + Objects.systemObjectId(obj));
    }

    @Override
    public void interrupt() {
        getStrand().interrupt();
    }

    public final ActorMonitor monitor() {
        if (monitor != null)
            return monitor;
        final String name = getName().toString().replaceAll(":", "");
        this.monitor = new JMXActorMonitor(name);
        monitor.setActor(this);
        return monitor;
    }

    public final void setMonitor(ActorMonitor monitor) {
        if (this.monitor == monitor)
            return;
        if (this.monitor != null)
            throw new RuntimeException("actor already has a monitor");
        this.monitor = monitor;
        monitor.setActor(this);
    }

    public final void stopMonitor() {
        if (monitor != null) {
            monitor.shutdown();
            this.monitor = null;
        }
    }

    public final ActorMonitor getMonitor() {
        return monitor;
    }

    public static LocalActor self() {
        final Fiber currentFiber = Fiber.currentFiber();
        if (currentFiber == null)
            return currentActor.get();
        final SuspendableCallable target = currentFiber.getTarget();
        if (target == null || !(target instanceof Actor))
            return null;
        return (LocalActor) target;
    }

    @Override
    public final void setStrand(Strand strand) {
        if (strand == this.strand)
            return;
        if (this.strand != null)
            throw new IllegalStateException("Strand already set to " + strand);
        this.strand = strand;
        if (getName() == null)
            setName(strand.getName());
        mailbox().setStrand(strand);
    }

    @Override
    public final Strand getStrand() {
        return strand;
    }

    //<editor-fold desc="Mailbox methods">
    /////////// Mailbox methods ///////////////////////////////////
    public final int getQueueLength() {
        return mailbox().getQueueLength();
    }

    @Override
    protected Mailbox<Object> mailbox() {
        return (Mailbox<Object>) super.mailbox();
    }

    @Override
    protected boolean isBackpressure() {
        return (mailboxConfig != null && mailboxConfig.getPolicy() == MailboxConfig.OverflowPolicy.BACKPRESSURE);
    }

    @Override
    protected void internalSend(Object message) {
        record(1, "Actor", "send", "Sending %s -> %s", message, this);
        if (mailbox().isOwnerAlive())
            mailbox().send(message);
        else
            record(1, "Actor", "send", "Message dropped. Owner not alive.");
    }

    @Override
    protected void internalSendNonSuspendable(Object message) {
        internalSend(message);
    }

    @Override
    public final void sendSync(Message message) throws SuspendExecution {
        try {
            record(1, "Actor", "sendSync", "Sending sync %s -> %s", message, this);
            if (mailbox().isOwnerAlive())
                mailbox().sendSync(message);
            else
                record(1, "Actor", "sendSync", "Message dropped. Owner not alive.");
        } catch (QueueCapacityExceededException e) {
            onMailboxFull(message, e);
        }
    }

    @Override
    public final Message receive() throws SuspendExecution, InterruptedException {
        for (;;) {
            checkThrownIn();
            record(1, "Actor", "receive", "%s waiting for a message", this);
            Object m = mailbox().receive();
            record(1, "Actor", "receive", "Received %s <- %s", this, m);
            monitorAddMessage();
            Message msg = filterMessage(m);
            if (msg != null)
                return msg;
        }
    }

    @Override
    public final Message receive(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException {
        if (unit == null)
            return receive();
        if (timeout <= 0)
            return tryReceive();

        final long start = System.nanoTime();
        long now;
        long left = unit.toNanos(timeout);

        for (;;) {
            if (flightRecorder != null)
                record(1, "Actor", "receive", "%s waiting for a message. millis left: ", this, TimeUnit.MILLISECONDS.convert(left, TimeUnit.NANOSECONDS));
            checkThrownIn();
            Object m = mailbox().receive(left, TimeUnit.NANOSECONDS);
            if (m != null) {
                record(1, "Actor", "receive", "Received %s <- %s", this, m);
                monitorAddMessage();
            }

            Message msg = filterMessage(m);
            if (msg != null)
                return msg;

            now = System.nanoTime();
            left = start + unit.toNanos(timeout) - now;
            if (left <= 0) {
                record(1, "Actor", "receive", "%s timed out.", this);
                return null;
            }
        }
    }

    @Override
    public final Message tryReceive() {
        for (;;) {
            checkThrownIn();
            Object m = mailbox().tryReceive();
            if (m == null)
                return null;
            record(1, "Actor", "tryReceive", "Received %s <- %s", this, m);
            monitorAddMessage();

            Message msg = filterMessage(m);
            if (msg != null)
                return msg;
        }
    }

    protected Message filterMessage(Object m) {
        if (m instanceof LifecycleMessage) {
            handleLifecycleMessage((LifecycleMessage) m);
            return null;
        }
        return (Message) m;
    }
    //</editor-fold>

    //<editor-fold desc="Strand helpers">
    /////////// Strand helpers ///////////////////////////////////
    public final LocalActor<Message, V> start() {
        record(1, "Actor", "start", "Starting actor %s", this);
        strand.start();
        return this;
    }

    @Override
    public final V get() throws InterruptedException, ExecutionException {
        if (strand instanceof Fiber)
            return ((Fiber<V>) strand).get();
        else {
            strand.join();
            return result;
        }
    }

    @Override
    public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (strand instanceof Fiber)
            return ((Fiber<V>) strand).get(timeout, unit);
        else {
            strand.join(timeout, unit);
            return result;
        }
    }

    @Override
    public final void join() throws ExecutionException, InterruptedException {
        strand.join();
    }

    @Override
    public final void join(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
        strand.join(timeout, unit);
    }

    @Override
    public final boolean isDone() {
        return strand.isTerminated();
    }

    protected final void verifyInActor() {
        if (!isInActor())
            throw new ConcurrencyException("Operation not called from within the actor (" + this + ")");
    }

    protected final boolean isInActor() {
        return (self() == this);
    }
    //</editor-fold>

    //<editor-fold desc="Lifecycle">
    /////////// Lifecycle ///////////////////////////////////
    @Override
    public final V run() throws InterruptedException, SuspendExecution {
        if (strand == null)
            setStrand(Strand.currentStrand());
        if (!(strand instanceof Fiber))
            currentActor.set(this);
        try {
            result = doRun();
            die(null);
            return result;
        } catch (InterruptedException e) {
            checkThrownIn();
            die(e);
            throw e;
        } catch (Throwable t) {
            die(t);
            throw t;
        } finally {
            record(1, "Actor", "die", "Actor %s is now dead of %s", this, deathCause);
            if (!(strand instanceof Fiber))
                currentActor.set(null);
        }
    }

    protected abstract V doRun() throws InterruptedException, SuspendExecution;

    protected void handleLifecycleMessage(LifecycleMessage m) {
        record(1, "Actor", "handleLifecycleMessage", "%s got LifecycleMessage %s", this, m);
        if (m instanceof ExitMessage && ((ExitMessage) m).getWatch() == null)
            throw new LifecycleException(m);
    }

    @Override
    protected final void addLifecycleListener(LifecycleListener listener) {
        if (isDone()) {
            listener.dead(this, deathCause);
            return;
        }
        lifecycleListeners.add(listener);
        if (isDone())
            listener.dead(this, deathCause);
    }

    @Override
    protected final void removeLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    protected final Throwable getDeathCause() {
        return deathCause;
    }

    public final boolean isRegistered() {
        return globalId != null;
    }

    Object getGlobalId() {
        return globalId;
    }

    @Override
    public final void throwIn(RuntimeException e) {
        record(1, "Actor", "throwIn", "Exception %s thrown into actor %s", e, this);
        this.exception = e; // last exception thrown in wins
        strand.interrupt();
    }

    final void checkThrownIn() {
        if (exception != null) {
            record(1, "Actor", "checkThrownIn", "%s detected thrown in exception %s", this, exception);
            exception.setStackTrace(new Throwable().getStackTrace());
            throw exception;
        }
    }

    private ActorImpl getActorImpl(Actor actor) {
        if(actor instanceof ActorImpl)
            return (ActorImpl)actor;
        else if(actor instanceof ActorWrapper)
            return getActorImpl(((ActorWrapper)actor).getActor());
        else
            throw new ClassCastException("Actor " + actor + " is not an ActorImpl");
    }
    
    public final Actor link(Actor other1) {
        final ActorImpl other = getActorImpl(other1);
        record(1, "Actor", "link", "Linking actors %s, %s", this, other);
        if (this.isDone()) {
            other.getLifecycleListener().dead(this, getDeathCause());
        } else {
            addLifecycleListener(other.getLifecycleListener());
            other.addLifecycleListener(getLifecycleListener());
        }
        return this;
    }

    public final Actor unlink(Actor other1) {
        final ActorImpl other = getActorImpl(other1);
        record(1, "Actor", "unlink", "Uninking actors %s, %s", this, other);
        removeLifecycleListener(other.getLifecycleListener());
        other.removeLifecycleListener(getLifecycleListener());
        return this;
    }

    public final Object watch(Actor other1) {
        final Object id = randtag();

        final ActorImpl other = getActorImpl(other1);
        final LifecycleListener listener = new ActorLifecycleListener(this, id);
        record(1, "Actor", "watch", "Actor %s to watch %s (listener: %s)", this, other, listener);

        other.addLifecycleListener(listener);
        return id;
    }

    public final void unwatch(Actor other1, Object watchId) {
        final ActorImpl other = getActorImpl(other1);
        final LifecycleListener listener = new ActorLifecycleListener(this, watchId);
        record(1, "Actor", "unwatch", "Actor %s to stop watching %s (listener: %s)", this, other, listener);
        other.removeLifecycleListener(listener);
    }

    public final Actor register(Object name) {
        if (getName() != null && !name.equals(name))
            throw new RegistrationException("Cannot register actor named " + getName() + " under a different name (" + name + ")");
        setName(name);
        return register();
    }

    public final Actor register() {
        record(1, "Actor", "register", "Registering actor %s as %s", this, getName());
        this.globalId = ActorRegistry.register(this);
        return this;
    }

    public final Actor unregister() {
        if (!isRegistered())
            return this;
        record(1, "Actor", "unregister", "Unregistering actor %s (name: %s)", this, getName());
        if (getName() == null)
            throw new IllegalArgumentException("name is null");
        ActorRegistry.unregister(getName());
        if (monitor != null)
            this.monitor.setActor(null);
        this.globalId = null;
        return this;
    }

    private void die(Throwable cause) {
        record(1, "Actor", "die", "Actor %s is dying of cause %s", this, cause);
        this.deathCause = cause;
        monitorAddDeath(cause);
        if (isRegistered())
            unregister();
        for (LifecycleListener listener : lifecycleListeners) {
            record(1, "Actor", "die", "Actor %s notifying listener %s of death.", this, listener);
            try {
                listener.dead(this, cause);
            } catch (Exception e) {
                record(1, "Actor", "die", "Actor %s notifying listener %s of death failed with excetpion %s", this, listener, e);
            }
        }
        lifecycleListeners.clear(); // avoid memory leak
    }

    //</editor-fold>
    //<editor-fold defaultstate="collapsed" desc="Monitor delegates">
    /////////// Monitor delegates ///////////////////////////////////
    protected final void monitorAddDeath(Object reason) {
        if (monitor != null)
            monitor.addDeath(reason);
    }

    protected final void monitorAddMessage() {
        if (monitor != null)
            monitor.addMessage();
    }

    protected final void monitorSkippedMessage() {
        if (monitor != null)
            monitor.skippedMessage();
    }

    protected final void monitorResetSkippedMessages() {
        if (monitor != null)
            monitor.resetSkippedMessages();
    }
    //</editor-fold>

    //<editor-fold desc="Serialization">
    /////////// Serialization ///////////////////////////////////
    // If using Kryo, see what needs to be done: https://code.google.com/p/kryo/
    protected Object writeReplace() throws java.io.ObjectStreamException {
        return RemoteProxyFactoryService.create(this, globalId);
    }
    //</editor-fold>
}
