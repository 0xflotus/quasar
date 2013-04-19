/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.actors;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.TimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import jsr166e.ForkJoinPool;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import org.junit.Ignore;

/**
 *
 * @author pron
 */
public class ActorTest {
    static final int mailboxSize = 10;
    private ForkJoinPool fjPool;

    public ActorTest() {
        fjPool = new ForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
    }

    private <Message, V> Actor<Message, V> createActor(Actor<Message, V> actor) {
        new Fiber("actor", fjPool, actor).start();
        return actor;
    }

    @Test
    public void whenActorThrowsExceptionThenGetThrowsIt() throws Exception {

        Actor<Message, Integer> actor = createActor(new BasicActor<Message, Integer>(mailboxSize) {
            int counter;

            @Override
            protected Integer doRun() throws SuspendExecution, InterruptedException {
                throw new RuntimeException("foo");
            }
        });

        try {
            actor.get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
            assertThat(e.getCause().getMessage(), is("foo"));
        }
    }

    @Test
    public void whenActorReturnsValueThenGetReturnsIt() throws Exception {
        Actor<Message, Integer> actor = createActor(new BasicActor<Message, Integer>(mailboxSize) {
            int counter;

            @Override
            protected Integer doRun() throws SuspendExecution, InterruptedException {
                return 42;
            }
        });

        assertThat(actor.get(), is(42));
    }

    @Test
    public void testReceive() throws Exception {
        Actor<Message, Integer> actor = createActor(new BasicActor<Message, Integer>(mailboxSize) {
            int counter;

            @Override
            protected Integer doRun() throws SuspendExecution, InterruptedException {
                Message m = receive();
                return m.num;
            }
        });

        actor.send(new Message(15));

        assertThat(actor.get(), is(15));
    }

    @Test
    public void testReceiveAfterSleep() throws Exception {
        Actor<Message, Integer> actor = createActor(new BasicActor<Message, Integer>(mailboxSize) {
            int counter;

            @Override
            protected Integer doRun() throws SuspendExecution, InterruptedException {
                Message m1 = receive();
                Message m2 = receive();
                return m1.num + m2.num;
            }
        });

        actor.send(new Message(25));
        Thread.sleep(200);
        actor.send(new Message(17));

        assertThat(actor.get(), is(42));
    }

    @Test
    public void testSelectiveReceive() throws Exception {
        Actor<ComplexMessage, List<Integer>> actor = createActor(new BasicActor<ComplexMessage, List<Integer>>(mailboxSize) {
            @Override
            protected List<Integer> doRun() throws SuspendExecution, InterruptedException {
                final List<Integer> list = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    receive(new MessageProcessor<ComplexMessage>() {
                        public boolean process(ComplexMessage m) throws SuspendExecution, InterruptedException {
                            switch (m.type) {
                                case FOO:
                                    list.add(1);
                                    receive(new MessageProcessor<ComplexMessage>() {
                                        public boolean process(ComplexMessage m) throws SuspendExecution, InterruptedException {
                                            switch (m.type) {
                                                case BAZ:
                                                    list.add(3);
                                                    return true;
                                                default:
                                                    return false;
                                            }
                                        }
                                    });
                                    return true;
                                case BAR:
                                    list.add(2);
                                    return true;
                                case BAZ:
                                    fail();
                                default:
                                    return false;
                            }
                        }
                    });
                }
                return list;
            }
        });

        actor.send(new ComplexMessage(ComplexMessage.Type.FOO, 1));
        actor.send(new ComplexMessage(ComplexMessage.Type.BAR, 2));
        actor.send(new ComplexMessage(ComplexMessage.Type.BAZ, 3));

        assertThat(actor.get(), equalTo(Arrays.asList(1, 3, 2)));
    }

    @Test
    public void whenSimpleReceiveAndTimeoutThenReturnNull() throws Exception {
        Actor<Message, Void> actor = createActor(new BasicActor<Message, Void>(mailboxSize) {
            int counter;

            @Override
            protected Void doRun() throws SuspendExecution, InterruptedException {
                Message m;
                m = receive(50, TimeUnit.MILLISECONDS);
                assertThat(m.num, is(1));
                m = receive(50, TimeUnit.MILLISECONDS);
                assertThat(m.num, is(2));
                m = receive(50, TimeUnit.MILLISECONDS);
                assertThat(m, is(nullValue()));

                return null;
            }
        });

        actor.send(new Message(1));
        Thread.sleep(20);
        actor.send(new Message(2));
        Thread.sleep(100);
        actor.send(new Message(3));
        actor.join();
    }

    @Test
    public void testTimeoutException() throws Exception {
        Actor<Message, Void> actor = createActor(new BasicActor<Message, Void>(mailboxSize) {
            @Override
            protected Void doRun() throws SuspendExecution, InterruptedException {
                try {
                    receive(100, TimeUnit.MILLISECONDS, new MessageProcessor<Message>() {
                        public boolean process(Message m) throws SuspendExecution, InterruptedException {
                            fail();
                            return true;
                        }
                    });
                    fail();
                } catch (TimeoutException e) {
                }
                return null;
            }
        });

        Thread.sleep(150);
        actor.send(new Message(1));
        actor.join();
    }

    @Test
    public void testLink() throws Exception {
        Actor<Message, Void> actor1 = createActor(new BasicActor<Message, Void>(mailboxSize) {
            int counter;

            @Override
            protected Void doRun() throws SuspendExecution, InterruptedException {
                try {
                    Fiber.sleep(100);
                } catch (TimeoutException e) {
                }
                return null;
            }
        });

        Actor<Message, Void> actor2 = createActor(new BasicActor<Message, Void>(mailboxSize) {
            int counter;

            @Override
            protected Void doRun() throws SuspendExecution, InterruptedException {
                try {
                    for (;;) {
                        receive();
                    }
                } catch (TimeoutException e) {
                    fail();
                } catch (LifecycleException e) {
                }
                return null;
            }
        });

        actor1.link(actor2);

        actor1.join();
        actor2.join();
    }

    @Ignore
    @Test
    public void testMonitor() {
        fail("pending");
    }

    static class Message {
        final int num;

        public Message(int num) {
            this.num = num;
        }
    }

    static class ComplexMessage {
        enum Type {
            FOO, BAR, BAZ, WAT
        };
        final Type type;
        final int num;

        public ComplexMessage(Type type, int num) {
            this.type = type;
            this.num = num;
        }
    }
}
