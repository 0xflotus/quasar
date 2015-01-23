/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
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
package co.paralleluniverse.strands.channels.transfer;

import co.paralleluniverse.common.test.Matchers;
import co.paralleluniverse.common.util.ConcurrentSet;
import co.paralleluniverse.common.util.Debug;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberForkJoinScheduler;
import co.paralleluniverse.fibers.FiberScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.SuspendableRunnable;
import co.paralleluniverse.strands.channels.Channel;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.Channels.OverflowPolicy;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 *
 * @author circlespainter
 */
@RunWith(Parameterized.class)
public class PipelineTest {
    @Rule
    public TestName name = new TestName();
    @Rule
    public TestRule watchman = new TestWatcher() {
        @Override
        protected void starting(Description desc) {
            if (Debug.isDebug()) {
                System.out.println("STARTING TEST " + desc.getMethodName());
                Debug.record(0, "STARTING TEST " + desc.getMethodName());
            }
        }

        @Override
        public void failed(Throwable e, Description desc) {
            System.out.println("FAILED TEST " + desc.getMethodName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            if (Debug.isDebug() && !(e instanceof OutOfMemoryError)) {
                Debug.record(0, "EXCEPTION IN THREAD " + Thread.currentThread().getName() + ": " + e + " - " + Arrays.toString(e.getStackTrace()));
                Debug.dumpRecorder("~/quasar.dump");
            }
        }

        @Override
        protected void succeeded(Description desc) {
            Debug.record(0, "DONE TEST " + desc.getMethodName());
        }
    };

    private final int mailboxSize;
    private final OverflowPolicy policy;
    private final boolean singleConsumer;
    private final boolean singleProducer;
    private final FiberScheduler scheduler;
    private final int parallelism;

    public PipelineTest(final int mailboxSize, final OverflowPolicy policy, final boolean singleConsumer, final boolean singleProducer, final int parallelism) {
        scheduler = new FiberForkJoinScheduler("test", 4, null, false);
        this.mailboxSize = mailboxSize;
        this.policy = policy;
        this.singleConsumer = singleConsumer;
        this.singleProducer = singleProducer;
        this.parallelism = parallelism;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {5, OverflowPolicy.THROW, true, false, 0},
            {5, OverflowPolicy.THROW, false, false, 0},
            {5, OverflowPolicy.BLOCK, true, false, 0},
            {5, OverflowPolicy.BLOCK, false, false, 0},
            {1, OverflowPolicy.BLOCK, false, false, 0},
            {-1, OverflowPolicy.THROW, true, false, 0},
            {5, OverflowPolicy.DISPLACE, true, false, 0},
            {0, OverflowPolicy.BLOCK, false, false, 0},

            {5, OverflowPolicy.THROW, true, false, 2},
            {5, OverflowPolicy.THROW, false, false, 2},
            {5, OverflowPolicy.BLOCK, true, false, 2},
            {5, OverflowPolicy.BLOCK, false, false, 2},
            {1, OverflowPolicy.BLOCK, false, false, 2},
            {-1, OverflowPolicy.THROW, true, false, 2},
            {5, OverflowPolicy.DISPLACE, true, false, 2},
            {0, OverflowPolicy.BLOCK, false, false, 2},
        });
    }

    private <Message> Channel<Message> newChannel() {
        return Channels.newChannel(mailboxSize, policy, singleProducer, singleConsumer);
    }

    @Test
    public void testPipeline() throws Exception {
        final Channel<Integer> i = newChannel();
        final Channel<Integer> o = newChannel();
        final Set<Fiber> transformers = new ConcurrentSet<>(new ConcurrentHashMap<Fiber, Object>());
        final Pipeline<Integer, Integer> p = new Pipeline<Integer, Integer>(i, o, parallelism) {
            @Override
            public Integer transform(Integer input) throws SuspendExecution, InterruptedException {
                transformers.add(Fiber.currentFiber());
                assertThat(getRunningParallelWorkers(), Matchers.lessOrEqual(parallelism));
                return input + 1;
            }
        };
        final Fiber<Long> pf = new Fiber("pipeline", scheduler, p).start();

        final Fiber receiver = new Fiber("receiver", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                final Integer m1 = o.receive();
                final Integer m2 = o.receive();
                final Integer m3 = o.receive();
                final Integer m4 = o.receive();
                assertThat(m1, notNullValue());
                assertThat(m2, notNullValue());
                assertThat(m3, notNullValue());
                assertThat(m4, notNullValue());
                assertThat(ImmutableSet.of(m1, m2, m3, m4), equalTo(ImmutableSet.of(2, 3, 4, 5)));
                try {
                    pf.join();
                } catch (ExecutionException ex) {
                    // It should never happen
                    throw new AssertionError(ex);
                }
                assertNull(o.tryReceive()); // This is needed, else `isClosed` could return false
                assertTrue(o.isClosed()); // Can be used reliably only in owner (receiver)
            }
        }).start();

        i.send(1);
        i.send(2);
        i.send(3);
        i.send(4);

        i.close();

        long transferred = pf.get(); // Join pipeline
        assertThat(transferred, equalTo(p.getTransferred()));
        assertThat(transferred, equalTo(4l));
        assertThat(transformers.size(), equalTo(parallelism == 0 ? 1 : 4));

        receiver.join();
    }
}
