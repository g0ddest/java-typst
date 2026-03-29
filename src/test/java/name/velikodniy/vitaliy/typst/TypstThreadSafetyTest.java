package name.velikodniy.vitaliy.typst;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class TypstThreadSafetyTest {

    @Test
    void concurrentRendering() throws Exception {
        try (var engine = TypstEngine.builder().build();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int idx = i;
                futures.add(executor.submit(() ->
                        engine.template("concurrent-" + idx, "= Document " + idx)
                                .renderPdf()));
            }

            for (Future<byte[]> future : futures) {
                byte[] pdf = future.get();
                PdfAssert.assertValidPdf(pdf);
            }
        }
    }

    @Test
    void concurrentRenderingSameTemplate() throws Exception {
        try (var engine = TypstEngine.builder().build();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            String source = "#let data = json(\"data.json\")\n= Hello, #data.name!";
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int idx = i;
                futures.add(executor.submit(() ->
                        engine.template("same-template", source)
                                .data("name", "User-" + idx)
                                .renderPdf()));
            }

            for (Future<byte[]> future : futures) {
                byte[] pdf = future.get();
                PdfAssert.assertValidPdf(pdf);
            }
        }
    }
}
