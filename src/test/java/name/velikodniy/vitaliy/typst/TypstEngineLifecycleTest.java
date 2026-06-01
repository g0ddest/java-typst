package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TypstEngineLifecycleTest {

    /**
     * The engine is documented as shareable across threads, and the Cleaner may
     * run cleanup on its own thread. Closing from a thread other than the one
     * that built the engine must therefore succeed (a confined Arena would throw
     * WrongThreadException here).
     */
    @Test
    void closeFromDifferentThreadSucceeds() throws Exception {
        TypstEngine engine = TypstEngine.builder().build();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> closed = pool.submit(engine::close);
            assertDoesNotThrow(() -> closed.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
