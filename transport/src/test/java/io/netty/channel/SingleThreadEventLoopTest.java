/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.channel.local.LocalChannel;
import io.netty.util.concurrent.EventExecutor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

public class SingleThreadEventLoopTest {

    private static final Runnable NOOP = new Runnable() {
        @Override
        public void run() { }
    };

    private SingleThreadEventLoopA loopA;
    private SingleThreadEventLoopB loopB;

    @Before
    public void newEventLoop() {
        loopA = new SingleThreadEventLoopA();
        loopB = new SingleThreadEventLoopB();
    }

    @After
    public void stopEventLoop() {
        if (!loopA.isShuttingDown()) {
            loopA.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }
        if (!loopB.isShuttingDown()) {
            loopB.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        }

        while (!loopA.isTerminated()) {
            try {
                loopA.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        assertEquals(1, loopA.cleanedUp.get());

        while (!loopB.isTerminated()) {
            try {
                loopB.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shutdownBeforeStart() throws Exception {
        loopA.shutdown();
        assertRejection(loopA);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shutdownAfterStart() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        loopA.execute(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });

        // Wait for the event loop thread to start.
        latch.await();

        // Request the event loop thread to stop.
        loopA.shutdown();
        assertRejection(loopA);

        assertTrue(loopA.isShutdown());

        // Wait until the event loop is terminated.
        while (!loopA.isTerminated()) {
            loopA.awaitTermination(1, TimeUnit.DAYS);
        }
    }

    private static void assertRejection(EventExecutor loop) {
        try {
            loop.execute(NOOP);
            fail("A task must be rejected after shutdown() is called.");
        } catch (RejectedExecutionException e) {
            // Expected
        }
    }

    @Test
    public void scheduleTaskA() throws Exception {
        testScheduleTask(loopA);
    }

    @Test
    public void scheduleTaskB() throws Exception {
        testScheduleTask(loopB);
    }

    private static void testScheduleTask(EventLoop loopA) throws InterruptedException, ExecutionException {
        long startTime = System.nanoTime();
        final AtomicLong endTime = new AtomicLong();
        loopA.schedule(new Runnable() {
            @Override
            public void run() {
                endTime.set(System.nanoTime());
            }
        }, 500, TimeUnit.MILLISECONDS).get();
        assertTrue(endTime.get() - startTime >= TimeUnit.MILLISECONDS.toNanos(500));
    }

    @Test
    public void scheduleTaskAtFixedRateA() throws Exception {
        testScheduleTaskAtFixedRate(loopA);
    }

    @Test
    public void scheduleTaskAtFixedRateB() throws Exception {
        testScheduleTaskAtFixedRate(loopB);
    }

    private static void testScheduleTaskAtFixedRate(EventLoop loopA) throws InterruptedException {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loopA.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                timestamps.add(System.nanoTime());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(550);
        assertTrue(f.cancel(true));
        assertEquals(5, timestamps.size());

        // Check if the task was run without a lag.
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            assertTrue(t.longValue() - previousTimestamp.longValue() >= TimeUnit.MILLISECONDS.toNanos(90));
            previousTimestamp = t;
        }
    }

    @Test
    public void scheduleLaggyTaskAtFixedRateA() throws Exception {
        testScheduleLaggyTaskAtFixedRate(loopA);
    }

    @Test
    public void scheduleLaggyTaskAtFixedRateB() throws Exception {
        testScheduleLaggyTaskAtFixedRate(loopB);
    }

    private static void testScheduleLaggyTaskAtFixedRate(EventLoop loopA) throws InterruptedException {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loopA.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                boolean empty = timestamps.isEmpty();
                timestamps.add(System.nanoTime());
                if (empty) {
                    try {
                        Thread.sleep(401);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(550);
        assertTrue(f.cancel(true));
        assertEquals(5, timestamps.size());

        // Check if the task was run with lag.
        int i = 0;
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            long diff = t.longValue() - previousTimestamp.longValue();
            if (i == 0) {
                assertTrue(diff >= TimeUnit.MILLISECONDS.toNanos(400));
            } else {
                assertTrue(diff <= TimeUnit.MILLISECONDS.toNanos(10));
            }
            previousTimestamp = t;
            i ++;
        }
    }

    @Test
    public void scheduleTaskWithFixedDelayA() throws Exception {
        testScheduleTaskWithFixedDelay(loopA);
    }

    @Test
    public void scheduleTaskWithFixedDelayB() throws Exception {
        testScheduleTaskWithFixedDelay(loopB);
    }

    private static void testScheduleTaskWithFixedDelay(EventLoop loopA) throws InterruptedException {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loopA.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                timestamps.add(System.nanoTime());
                try {
                    Thread.sleep(51);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(500);
        assertTrue(f.cancel(true));
        assertEquals(3, timestamps.size());

        // Check if the task was run without a lag.
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            assertTrue(t.longValue() - previousTimestamp.longValue() >= TimeUnit.MILLISECONDS.toNanos(150));
            previousTimestamp = t;
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void shutdownWithPendingTasks() throws Exception {
        final int NUM_TASKS = 3;
        final AtomicInteger ranTasks = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                ranTasks.incrementAndGet();
                while (latch.getCount() > 0) {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        // Ignored
                    }
                }
            }
        };

        for (int i = 0; i < NUM_TASKS; i ++) {
            loopA.execute(task);
        }

        // At this point, the first task should be running and stuck at latch.await().
        while (ranTasks.get() == 0) {
            Thread.yield();
        }
        assertEquals(1, ranTasks.get());

        // Shut down the event loop to test if the other tasks are run before termination.
        loopA.shutdown();

        // Let the other tasks run.
        latch.countDown();

        // Wait until the event loop is terminated.
        while (!loopA.isTerminated()) {
            loopA.awaitTermination(1, TimeUnit.DAYS);
        }

        // Make sure loop.shutdown() above triggered wakeup().
        assertEquals(NUM_TASKS, ranTasks.get());
    }

    @Test(timeout = 10000)
    @SuppressWarnings("deprecation")
    public void testRegistrationAfterShutdown() throws Exception {
        loopA.shutdown();
        ChannelFuture f = loopA.register(new LocalChannel());
        f.awaitUninterruptibly();
        assertFalse(f.isSuccess());
        assertThat(f.cause(), is(instanceOf(RejectedExecutionException.class)));
    }

    @Test(timeout = 10000)
    @SuppressWarnings("deprecation")
    public void testRegistrationAfterShutdown2() throws Exception {
        loopA.shutdown();
        final CountDownLatch latch = new CountDownLatch(1);
        Channel ch = new LocalChannel();
        ChannelPromise promise = ch.newPromise();
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                latch.countDown();
            }
        });

        ChannelFuture f = loopA.register(ch, promise);
        f.awaitUninterruptibly();
        assertFalse(f.isSuccess());
        assertThat(f.cause(), is(instanceOf(RejectedExecutionException.class)));

        // Ensure the listener was notified.
        assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test(timeout = 5000)
    public void testGracefulShutdownQuietPeriod() throws Exception {
        loopA.shutdownGracefully(1, Integer.MAX_VALUE, TimeUnit.SECONDS);
        // Keep Scheduling tasks for another 2 seconds.
        for (int i = 0; i < 20; i ++) {
            Thread.sleep(100);
            loopA.execute(NOOP);
        }

        long startTime = System.nanoTime();

        assertThat(loopA.isShuttingDown(), is(true));
        assertThat(loopA.isShutdown(), is(false));

        while (!loopA.isTerminated()) {
            loopA.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
        }

        assertTrue(System.nanoTime() - startTime >= TimeUnit.SECONDS.toNanos(1));
    }

    @Test(timeout = 5000)
    public void testGracefulShutdownTimeout() throws Exception {
        loopA.shutdownGracefully(2, 2, TimeUnit.SECONDS);
        // Keep Scheduling tasks for another 3 seconds.
        // Submitted tasks must be rejected after 2 second timeout.
        for (int i = 0; i < 10; i ++) {
            Thread.sleep(100);
            loopA.execute(NOOP);
        }

        try {
            for (int i = 0; i < 20; i ++) {
                Thread.sleep(100);
                loopA.execute(NOOP);
            }
            fail("shutdownGracefully() must reject a task after timeout.");
        } catch (RejectedExecutionException e) {
            // Expected
        }

        assertThat(loopA.isShuttingDown(), is(true));
        assertThat(loopA.isShutdown(), is(true));
    }

    private static class SingleThreadEventLoopA extends SingleThreadEventLoop {

        final AtomicInteger cleanedUp = new AtomicInteger();

        SingleThreadEventLoopA() {
            super(null, Executors.defaultThreadFactory(), true);
        }

        @Override
        protected void run() {
            for (;;) {
                Runnable task = takeTask();
                if (task != null) {
                    task.run();
                    updateLastExecutionTime();
                }

                if (confirmShutdown()) {
                    break;
                }
            }
        }

        @Override
        protected void cleanup() {
            cleanedUp.incrementAndGet();
        }
    }

    private static class SingleThreadEventLoopB extends SingleThreadEventLoop {

        SingleThreadEventLoopB() {
            super(null, Executors.defaultThreadFactory(), false);
        }

        @Override
        protected void run() {
            for (;;) {
                try {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(delayNanos(System.nanoTime())));
                } catch (InterruptedException e) {
                    // Waken up by interruptThread()
                }

                runAllTasks();

                if (confirmShutdown()) {
                    break;
                }
            }
        }

        @Override
        protected void wakeup(boolean inEventLoop) {
            interruptThread();
        }
    }
}
