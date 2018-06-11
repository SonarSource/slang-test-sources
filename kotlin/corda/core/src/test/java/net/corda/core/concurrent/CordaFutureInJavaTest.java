package net.corda.core.concurrent;

import net.corda.core.internal.concurrent.OpenFuture;
import org.junit.Test;

import java.io.EOFException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.corda.core.internal.concurrent.CordaFutureImplKt.doneFuture;
import static net.corda.core.internal.concurrent.CordaFutureImplKt.openFuture;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CordaFutureInJavaTest {
    @Test
    public void methodsAreNotTooAwkwardToUse() throws InterruptedException, ExecutionException {
        {
            CordaFuture<Number> f = openFuture();
            f.cancel(false);
            assertTrue(f.isCancelled());
        }
        {
            CordaFuture<Number> f = openFuture();
            assertThatThrownBy(() -> f.get(1, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }
        {
            CordaFuture<Number> f = doneFuture(100);
            assertEquals(100, f.get());
        }
        {
            Future<Integer> f = doneFuture(100);
            assertEquals(Integer.valueOf(100), f.get());
        }
        {
            OpenFuture<Number> f = openFuture();
            OpenFuture<Number> g = openFuture();
            f.then(done -> {
                try {
                    return g.set(done.get());
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            });
            f.set(100);
            assertEquals(100, g.get());
        }
    }

    @Test
    public void toCompletableFutureWorks() throws InterruptedException, ExecutionException {
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            f.set(100);
            assertEquals(100, g.get());
        }
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            EOFException e = new EOFException();
            f.setException(e);
            assertThatThrownBy(g::get).hasCause(e);
        }
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            f.cancel(false);
            assertTrue(g.isCancelled());
        }
    }

    @Test
    public void toCompletableFutureDoesNotHaveThePowerToAffectTheUnderlyingFuture() {
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            g.complete(100);
            assertFalse(f.isDone());
        }
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            g.completeExceptionally(new EOFException());
            assertFalse(f.isDone());
        }
        {
            OpenFuture<Number> f = openFuture();
            CompletableFuture<Number> g = f.toCompletableFuture();
            g.cancel(false);
            // For now let's do the most conservative thing i.e. nothing:
            assertFalse(f.isDone());
        }
    }
}
