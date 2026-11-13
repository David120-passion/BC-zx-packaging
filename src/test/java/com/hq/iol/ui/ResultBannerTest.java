package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ResultBannerTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    void backgroundFailureUpdatesFxLabel() throws Exception {
        AtomicReference<ResultBanner> ref = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        Platform.runLater(() -> { ref.set(new ResultBanner()); created.countDown(); });
        assertTrue(created.await(5, TimeUnit.SECONDS));
        ref.get().fail("打印机未就绪");
        CountDownLatch updated = new CountDownLatch(1);
        Platform.runLater(updated::countDown);
        assertTrue(updated.await(5, TimeUnit.SECONDS));
        Label label = (Label) ref.get().node();
        assertEquals("✗ 打印机未就绪", label.getText());
        assertTrue(label.getStyleClass().contains("result-fail"));
        assertEquals("打印机未就绪", label.getTooltip().getText());
    }
}
