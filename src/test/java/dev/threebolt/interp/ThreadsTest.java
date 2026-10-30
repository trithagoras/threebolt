package dev.threebolt.interp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Cancellation wakes threads by interrupting them, except inside an uninterruptible region (§13.4). */
class ThreadsTest {
    private static final Callbacks RT = (Callbacks) Proxy.newProxyInstance(Callbacks.class.getClassLoader(),
            new Class<?>[] {Callbacks.class}, (proxy, m, args) -> m.getReturnType() == boolean.class ? false : null);

    private final Threads threads = new Threads(RT);

    /** Whether the current thread is interrupted within a second ({@code cancel} sets the flag, then interrupts). */
    private static boolean interruptedSoon() {
        long until = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < until) {
            if (Thread.currentThread().isInterrupted()) return true;
            Thread.onSpinWait();
        }
        return false;
    }

    /** Runs {@code body} on a new thread, handing it a way to cancel that thread; returns what it reports. */
    private boolean onThread(java.util.function.BiFunction<CountDownLatch, AtomicBoolean, Boolean> body, boolean cancelWhenReady)
            throws Exception {
        var canceller = new CompletableFuture<Runnable>();
        var ready = new CountDownLatch(1);
        var result = new CompletableFuture<Boolean>();
        var flag = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            canceller.complete(threads.cancellerOfCurrent());
            result.complete(body.apply(ready, flag));
        });
        ready.await();
        if (cancelWhenReady) canceller.get().run();
        return result.get();
    }

    @Test
    void cancellingInterrupts() throws Exception {
        assertTrue(onThread((ready, f) -> {
            ready.countDown();
            while (!threads.currentCancelled()) Thread.onSpinWait();
            return interruptedSoon();
        }, true));
    }

    @Test
    void cancellingAShieldedThreadDoesNotInterruptIt() throws Exception {
        assertFalse(onThread((ready, interrupted) -> {
            boolean ran = threads.uninterruptibly(() -> {
                ready.countDown();
                while (!threads.currentCancelled()) Thread.onSpinWait();
                interrupted.set(interruptedSoon());
            });
            return !ran || interrupted.get();
        }, true));
    }

    @Test
    void aCancelledThreadDoesNotEnterTheRegion() throws Exception {
        assertFalse(onThread((ready, ran) -> {
            ready.countDown();
            while (!threads.currentCancelled()) Thread.onSpinWait();
            return threads.uninterruptibly(() -> ran.set(true)) || ran.get();
        }, true));
    }
}
