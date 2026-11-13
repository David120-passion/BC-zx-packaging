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
        FxTestToolkit.start();
    }

    @AfterAll
    static void stopFx() {
        // Toolkit 生命周期由 FxTestToolkit 统一管理
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

    @Test
    void clearKeepsConsistentHeight() throws Exception {
        AtomicReference<ResultBanner> ref = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        Platform.runLater(() -> { ref.set(new ResultBanner()); created.countDown(); });
        assertTrue(created.await(5, TimeUnit.SECONDS));
        Label label = (Label) ref.get().node();
        ref.get().success("校验通过");
        double populatedMin = label.getMinHeight();
        ref.get().clear();
        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        assertTrue(populatedMin > 0, "横幅应有显式最小高度，空闲时保持占位");
        assertEquals(populatedMin, label.getMinHeight(), "clear() 不得改变横幅高度");
    }
}
