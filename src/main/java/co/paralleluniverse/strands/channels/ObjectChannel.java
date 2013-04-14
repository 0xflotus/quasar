/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.strands.channels;

import co.paralleluniverse.actors.MessageProcessor;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.TimeoutException;
import co.paralleluniverse.strands.queues.SingleConsumerArrayObjectQueue;
import co.paralleluniverse.strands.queues.SingleConsumerLinkedObjectQueue;
import co.paralleluniverse.strands.queues.SingleConsumerQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pron
 */
public class ObjectChannel<Message> extends Channel<Message> {
    private Message currentMessage; // this works because channel is single-consumer
    
    public static <Message> ObjectChannel<Message> create(Object owner, int mailboxSize) {
        return new ObjectChannel(owner, mailboxSize > 0 ? new SingleConsumerArrayObjectQueue<Message>(mailboxSize) : new SingleConsumerLinkedObjectQueue<Message>());
    }

    public static <Message> ObjectChannel<Message> create(int mailboxSize) {
        return new ObjectChannel(mailboxSize > 0 ? new SingleConsumerArrayObjectQueue<Message>(mailboxSize) : new SingleConsumerLinkedObjectQueue<Message>());
    }

    private ObjectChannel(Object owner, SingleConsumerQueue<Message, ?> queue) {
        super(owner, queue);
    }

    private ObjectChannel(SingleConsumerQueue<Message, ?> queue) {
        super(queue);
    }

    /**
     *
     * @param proc
     * @param currentMessage
     * @param timeout
     * @param unit
     * @throws TimeoutException
     * @throws LwtInterruptedException
     */
    public Message receive(long timeout, TimeUnit unit, MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        maybeSetCurrentStrandAsOwner();

        final long start = timeout > 0 ? System.nanoTime() : 0;
        long now;
        long left = unit != null ? unit.toNanos(timeout) : 0;

        Object n = null;
        for (;;) {
            n = queue.succ(n);
            if (n != null) {
                final Object m = queue.value(n);
                if (m == currentMessage) {
                    queue.del(n);
                    continue;
                }

                try {
                    final Message msg = (Message)m;
                    currentMessage = msg;
                    if (proc.process(msg)) {
                        if (queue.value(n) == msg) // another call to receive from within the processor may have deleted n
                            queue.del(n);
                        return msg;
                    }
                } catch (Exception e) {
                    if (queue.value(n) == m) // another call to receive from within the processor may have deleted n
                        queue.del(n);
                    throw e;
                }
            } else {
                sync().lock();
                try {
                    if (timeout > 0) {
                        sync().await(this, left, TimeUnit.NANOSECONDS);

                        now = System.nanoTime();
                        left = start + unit.toNanos(timeout) - now;
                        if (left <= 0)
                            throw new TimeoutException();
                    } else
                        sync().await();
                } finally {
                    sync().unlock();
                }
            }
        }
    }

    public Message receive(MessageProcessor<Message> proc) throws SuspendExecution, InterruptedException {
        return receive(0, null, proc);
    }
}
