/*
 * Galaxy
 * Copyright (C) 2012 Parallel Universe Software Co.
 * 
 * This file is part of Galaxy.
 *
 * Galaxy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 3 of 
 * the License, or (at your option) any later version.
 *
 * Galaxy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public 
 * License along with Galaxy. If not, see <http://www.gnu.org/licenses/>.
 */
package co.paralleluniverse.galaxy.example;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.BasicActor;
import co.paralleluniverse.actors.LifecycleMessage;
import co.paralleluniverse.actors.LocalActor;
import co.paralleluniverse.actors.MailboxConfig;
import co.paralleluniverse.actors.behaviors.AbstractServer;
import co.paralleluniverse.actors.behaviors.EventHandler;
import co.paralleluniverse.actors.behaviors.GenEvent;
import co.paralleluniverse.actors.behaviors.GenServer;
import co.paralleluniverse.actors.behaviors.Initializer;
import co.paralleluniverse.actors.behaviors.LocalGenEvent;
import co.paralleluniverse.actors.behaviors.LocalGenServer;
import co.paralleluniverse.actors.behaviors.Server;
import co.paralleluniverse.common.util.Exceptions;
import co.paralleluniverse.concurrent.util.ThreadUtil;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.DelayedVal;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import jsr166e.ForkJoinPool;

/**
 *
 * @author pron
 */
public class PeerTKB {
    private final int i;

    public PeerTKB(String name, int i) throws InterruptedException, IOException {
        System.out.println("STARTING PEER " + i);
        final URL peerXml = PeerTKB.class.getClassLoader().getResource("config/peer.xml");
        System.setProperty("galaxy.nodeId", Integer.toString(i));
        System.setProperty("galaxy.port", Integer.toString(7050 + i));
        System.setProperty("galaxy.slave_port", Integer.toString(8050 + i));
        System.setProperty("galaxy.multicast.address", "225.0.0.1");
        System.setProperty("galaxy.multicast.port", Integer.toString(7050));
        System.setProperty("co.paralleluniverse.galaxy.configFile", peerXml.getPath());
        System.setProperty("co.paralleluniverse.galaxy.autoGoOnline", "true");
        this.i = i;
    }

    public void run() throws ExecutionException, InterruptedException {
        switch (SCENARIO.pingPong) {
            case pingPong:
                if (i == 1) {
                    spawnActor(new BasicActor<RepliableMessage<String>, Void>() {
                        @Override
                        protected Void doRun() throws InterruptedException, SuspendExecution {
                            register("pong");
                            while (true) {
                                RepliableMessage<String> msg = receive();
                                if (msg.data.equals("finished"))
                                    break;
                                if (msg.data.equals("ping")) 
                                    msg.sender.send(new RepliableMessage("pong",this));                                
                            }
                            return null;
                        }
                    }).join();
                    System.out.println("pong finished");
                } else {
                    spawnActor(new BasicActor<RepliableMessage<String>, Void>() {
                        int i=3;
                        @Override
                        protected Void doRun() throws InterruptedException, SuspendExecution {
                            Actor pong = getActor("pong");
                            System.out.println("pong is "+pong);
                            while (i-- > 0) {
                                pong.send(new RepliableMessage("ping",this));
                                RepliableMessage<String> msg = receive();
                                System.out.println("ping received "+msg.data);
                            }
                            pong.send(new RepliableMessage("finished",null));
                            return null;
                        }
                    }).join();
                }
                break;

            case testGenServer:
                if (i == 1) {
                    spawnGenServer(new AbstractServer<Message, Integer, Message>() {
                        @Override
                        public void init() throws SuspendExecution {
                            super.init();
                            LocalGenServer.currentGenServer().register("myServer");
                        }

                        @Override
                        public Integer handleCall(Actor<Integer> from, Object id, Message m) {
                            return m.a + m.b;
                        }
                    }).join();
                } else {
                    Integer get = spawnActor(new BasicActor<Message, Integer>(new MailboxConfig(10, Channels.OverflowPolicy.THROW)) {
                        protected Integer doRun() throws SuspendExecution, InterruptedException {
                            final GenServer<Message, Integer, Message> gs = (GenServer) getActor("myServer");
                            return gs.call(new Message(3, 4));
                        }
                    }).get();
                    System.out.println("value is " + get);
                    assert get == 7;

                }
                break;
            case testGenEvent:
                if (i == 1) {
                    final DelayedVal<String> dv = new DelayedVal<>();
                    spawnGenEvent(new Initializer() {
                        @Override
                        public void init() throws SuspendExecution {
                            LocalGenEvent.currentGenEvent().register("myEventServer");
                            try {
                                LocalGenEvent<String> ge = LocalGenEvent.currentGenEvent();
                                ge.addHandler(new EventHandler<String>() {
                                    @Override
                                    public void handleEvent(String event) {
                                        dv.set(event);
                                        System.out.println("sout " + event);
                                        LocalGenEvent.currentGenEvent().shutdown();
                                    }
                                });
                            } catch (InterruptedException ex) {
                                System.out.println(ex);
                            }
                        }

                        @Override
                        public void terminate(Throwable cause) throws SuspendExecution {
                            System.out.println("terminated");
                        }
                    });
                    try {
                        String get = dv.get();
                        System.out.println("got msg " + get);
                        assert get.equals("hello world");
                    } catch (SuspendExecution ex) {
                    }
                } else {
                    spawnActor(new BasicActor<Message, Void>() {
                        protected Void doRun() throws SuspendExecution, InterruptedException {
                            final GenEvent<String> ge = (GenEvent) getActor("myEventServer");
                            ge.notify("hello world");
                            return null;
                        }
                    }).join();
                }
                break;
            case testMultiGetActor:
                if (i == 1) {
                    spawnGenEvent(new Initializer() {
                        AtomicInteger ai = new AtomicInteger();

                        @Override
                        public void init() throws SuspendExecution {
                            LocalGenEvent.currentGenEvent().register("myEventServer");
                            System.out.println("kkkvb " + LocalGenEvent.getActor("myEventServer"));
                            try {
                                LocalGenEvent<String> ge = LocalGenEvent.currentGenEvent();
                                ge.addHandler(new EventHandler<String>() {
                                    @Override
                                    public void handleEvent(String event) {
                                        System.out.println("msg no " + ai.incrementAndGet() + ": " + event);
                                    }
                                });
                            } catch (InterruptedException ex) {
                                System.out.println(ex);
                            }
                        }

                        @Override
                        public void terminate(Throwable cause) throws SuspendExecution {
                            System.out.println("terminated");
                        }
                    }).join();
                } else {
                    Queue<LocalActor> queue = new LinkedList<>();
                    for (int j = 0; j < 1000; j++) {
                        final BasicActor<Message, Void> actor = spawnActor(new BasicActor<Message, Void>("actor-" + j) {
                            protected Void doRun() throws SuspendExecution, InterruptedException {
                                try {
                                    final GenEvent<String> ge = (GenEvent) getActor("myEventServer");
                                    ge.notify("hwf " + getName());
                                } catch (Exception e) {
                                    System.out.println("error in " + getName());
                                    throw e;
                                }
                                return null;
                            }
                        });
                        queue.add(actor);
//                        actor.join();
                    }
                    for (LocalActor localActor : queue)
                        localActor.join();
                    Thread.sleep(500);

                }
                break;
            case testOrdering:
                if (i == 1) {
                    spawnGenEvent(new Initializer() {
                        AtomicInteger ai = new AtomicInteger();

                        @Override
                        public void init() throws SuspendExecution {
                            LocalGenEvent.currentGenEvent().register("myEventServer");
                            try {
                                LocalGenEvent<String> ge = LocalGenEvent.currentGenEvent();
                                ge.addHandler(new EventHandler<String>() {
                                    @Override
                                    public void handleEvent(String event) {
                                        System.out.println("msg no " + ai.incrementAndGet() + ": " + event);
                                    }
                                });
                            } catch (InterruptedException ex) {
                                System.out.println(ex);
                            }
                        }

                        @Override
                        public void terminate(Throwable cause) throws SuspendExecution {
                            System.out.println("terminated");
                        }
                    }).join();
                } else {
                    Queue<LocalActor> queue = new LinkedList<>();
                    for (int j = 0; j < 1; j++) {
                        final BasicActor<Message, Void> actor = spawnActor(new BasicActor<Message, Void>("actor-" + j) {
                            protected Void doRun() throws SuspendExecution, InterruptedException {
                                try {
                                    final GenEvent<String> ge = (GenEvent) getActor("myEventServer");
                                    for (int k = 0; k < 3000; k++)
                                        ge.notify("hw " + k + " f" + getName());
                                } catch (Exception e) {
                                    System.out.println("error in " + getName());
                                    throw e;
                                }
                                return null;
                            }
                        });
                        queue.add(actor);
//                        actor.join();
                    }
                    for (LocalActor localActor : queue)
                        localActor.join();
                    Thread.sleep(5000);

                }
                break;

            default:
        }
        System.out.println("finished");
        System.exit(0);
        while (true) {
            System.out.println("==================");
            ThreadUtil.dumpThreads();
            Thread.sleep(5000);
        }


    }

    enum SCENARIO {
        pingPong,
        testGenServer,
        testGenEvent,
        testMultiGetActor,
        testOrdering,
    }

    private LocalGenServer<Message, Integer, Message> spawnGenServer(Server<Message, Integer, Message> server) {
        return spawnActor(new LocalGenServer<>(server));
    }

    private LocalGenEvent<String> spawnGenEvent(Initializer initializer) {
        return spawnActor(new LocalGenEvent<String>(initializer));
    }
    static private ForkJoinPool fjPool = new ForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);

    private <T extends LocalActor<Message, V>, Message, V> T spawnActor(T actor) {
        Fiber fiber = new Fiber(fjPool, actor);
        fiber.setUncaughtExceptionHandler(new Fiber.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Fiber lwt, Throwable e) {
                e.printStackTrace();
                throw Exceptions.rethrow(e);
            }
        });
        fiber.start();
        return actor;


    }
    
    static class RepliableMessage<T> implements Serializable{
        T data;
        Actor sender;

        public RepliableMessage(T data, Actor sender) {
            this.data = data;
            this.sender = sender;
        }        
    }

    static class Message implements java.io.Serializable {
        final int a;
        final int b;

        public Message(int a, int b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 43 * hash + this.a;
            hash = 43 * hash + this.b;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            final Message other = (Message) obj;
            if (this.a != other.a)
                return false;
            if (this.b != other.b)
                return false;
            return true;
        }
    }
}
