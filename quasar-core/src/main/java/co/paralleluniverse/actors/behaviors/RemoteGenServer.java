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

import co.paralleluniverse.actors.RemoteActor;
import co.paralleluniverse.fibers.SuspendExecution;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author pron
 */
class RemoteGenServer<CallMessage, V, CastMessage> extends RemoteBasicGenBehavior implements GenServer<CallMessage, V, CastMessage> {
    public RemoteGenServer(RemoteActor<Object> actor) {
        super(actor);
    }

    @Override
    public final V call(CallMessage m) throws InterruptedException, SuspendExecution {
        return GenServerHelper.call(this, m);
    }

    @Override
    public final V call(CallMessage m, long timeout, TimeUnit unit) throws TimeoutException, InterruptedException, SuspendExecution {
        return GenServerHelper.call(this, m, timeout, unit);
    }

    @Override
    public final void cast(CastMessage m) throws SuspendExecution {
        GenServerHelper.cast(this, m);
    }
}
