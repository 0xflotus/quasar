/*
 * Quasar: lightweight threads and actors for the JVM.
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
package co.paralleluniverse.remote.galaxy;

import co.paralleluniverse.actors.ActorRef;
import co.paralleluniverse.actors.LifecycleListener;
import co.paralleluniverse.actors.LifecycleListenerProxy;
import co.paralleluniverse.actors.RemoteActor;
import co.paralleluniverse.galaxy.cluster.NodeChangeListener;
import co.paralleluniverse.galaxy.quasar.Grid;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pron
 */
@MetaInfServices
public class GlxLifecycleListenerProxy extends LifecycleListenerProxy {
    private static final Logger LOG = LoggerFactory.getLogger(GlxLifecycleListenerProxy.class);
//    private static final ReferenceQueue<LifecycleListener> oldlistenerRefQueue = new ReferenceQueue<>();
    private final Grid grid;
//    private static final Map<Short, Set<RAPhantomReference>> map = new ConcurrentHashMap<>();
    private final static Set<RegistryRecord> listenerRegistry = Collections.newSetFromMap(new ConcurrentHashMap<RegistryRecord, Boolean>());

    public GlxLifecycleListenerProxy() {
        try {
            grid = new Grid(co.paralleluniverse.galaxy.Grid.getInstance());
            grid.cluster().addNodeChangeListener(new NodeChangeListener() {
                @Override
                public void nodeAdded(short id) {
                }

                @Override
                public void nodeSwitched(short id) {
                }

                @Override
                public void nodeRemoved(short id) {
                    for (Iterator<RegistryRecord> it = listenerRegistry.iterator(); it.hasNext();) {
                        RegistryRecord registryRecord = it.next();
                        if (registryRecord.getOwnerNodeId() == id) {
                            registryRecord.listener.dead(registryRecord.actor.ref(), new Throwable("cluster node removed"));
                            //TODO: remove listeners
                            it.remove();
                        }
                    }
                }
            });
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected static GlxRemoteActor getImpl(ActorRef<?> actor) {
        return (GlxRemoteActor) LifecycleListenerProxy.getImpl(actor);
    }

    @Override
    public void addLifecycleListener(RemoteActor actor, final LifecycleListener listener) {
        final GlxRemoteActor glxActor = (GlxRemoteActor) actor;
        final short nodeId = glxActor.getOwnerNodeId();
        if (!grid.cluster().getNodes().contains(nodeId)) {
            listener.dead(actor.ref(), null);
            return;
        }
        super.addLifecycleListener(actor, listener);
        listenerRegistry.add(new RegistryRecord(listener, glxActor));
    }

    @Override
    public void removeLifecycleListener(RemoteActor actor, LifecycleListener listener) {
        super.removeLifecycleListener(actor, listener);
        listenerRegistry.remove(new RegistryRecord(listener, (GlxRemoteActor) actor));
    }

    @Override
    public void removeLifecycleListeners(RemoteActor actor, ActorRef observer) {
        super.removeLifecycleListeners(actor, observer);
        for (Iterator<RegistryRecord> it = listenerRegistry.iterator(); it.hasNext();) {
            RegistryRecord registryRecord = it.next();
            if (registryRecord.actor.equals(actor))
                it.remove();
        }
    }

    final class RegistryRecord {
        final LifecycleListener listener;
        final GlxRemoteActor actor;

        public RegistryRecord(LifecycleListener listener, GlxRemoteActor actor) {
            this.listener = listener;
            this.actor = actor;
        }

        short getOwnerNodeId() {
            return actor.getOwnerNodeId();
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 79 * hash + Objects.hashCode(this.listener);
            hash = 79 * hash + Objects.hashCode(this.actor);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (!(obj instanceof RegistryRecord))
                return false;
            final RegistryRecord other = (RegistryRecord) obj;
            if (!Objects.equals(this.listener, other.listener))
                return false;
            if (!Objects.equals(this.actor, other.actor))
                return false;
            return true;
        }
    }
}
