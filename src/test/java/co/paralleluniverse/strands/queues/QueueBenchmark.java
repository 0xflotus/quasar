/*
 * Copyright 2012 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.paralleluniverse.strands.queues;

import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Martin Thompson
 * @autor pron
 */
public class QueueBenchmark {
    public static final int QUEUE_CAPACITY = 32 * 1024;
    public static final int REPETITIONS = 50 * 1000 * 1000;
    public static final Integer TEST_VALUE = Integer.valueOf(111);

    public static void main(String[] args) throws Exception {
        for (int type = 1; type <= 10; type++) {
            final Queue<Integer> queue = createQueue(type);

            System.out.println("===== " + queue.getClass().getSimpleName() + " ===");
            for (int i = 0; i < 5; i++) {
                System.gc();
                System.gc();
                performanceRun(i, queue);
            }
        }
    }

    private static Queue<Integer> createQueue(int type) {
        switch (type) {
            case 1:
                return new SingleConsumerArrayObjectQueue<Integer>(QUEUE_CAPACITY);
            case 2:
                return new SingleConsumerLinkedObjectQueue<Integer>();
            case 3:
                return new SingleConsumerLinkedArrayObjectQueue<Integer>();
            case 4:
                return new SingleConsumerArrayIntQueue(QUEUE_CAPACITY);
            case 5:
                return new SingleConsumerLinkedIntQueue();
            case 6:
                return new SingleConsumerLinkedArrayIntQueue();
            case 7:
                return new java.util.concurrent.ArrayBlockingQueue<Integer>(QUEUE_CAPACITY);
            case 8:
                return new java.util.concurrent.LinkedBlockingQueue<Integer>(QUEUE_CAPACITY);
            case 9:
                return new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
            case 10:
                return new java.util.concurrent.LinkedTransferQueue<Integer>();

            default:
                throw new IllegalArgumentException("Invalid option: " + type);
        }
    }

    private static void performanceRun(final int runNumber, final Queue<Integer> queue) throws Exception {
        final long start = System.nanoTime();
        final Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                int i = REPETITIONS;
                do {
                    while (!queue.offer(TEST_VALUE))
                        Thread.yield();
                } while (0 != --i);
            }
        });
        
        producer.start();

        Integer result;
        int i = REPETITIONS;
        do {
            while (null == (result = queue.poll()))
                Thread.yield();
        } while (0 != --i);

        producer.join();

        final long duration = System.nanoTime() - start;
        final long ops = (REPETITIONS * TimeUnit.SECONDS.toNanos(1)) / duration;
        System.out.format("%d - ops/sec=%,d - %s result=%d\n",
                Integer.valueOf(runNumber), Long.valueOf(ops),
                queue.getClass().getSimpleName(), result);
    }
}
