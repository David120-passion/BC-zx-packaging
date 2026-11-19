package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaserSettingsPanelTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Test
    void 打标等待时间可微调并保存() throws Exception {
        ConfigService config = mock(ConfigService.class);
        when(config.get(ConfigKeys.LASER_SDK_DIRECTORY)).thenReturn("");
        when(config.get(ConfigKeys.LASER_MARK_TIMEOUT_MS)).thenReturn("120000");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = new LaserSettingsPanel(config, new UiLogService()).build();
                TextField input = (TextField) root.lookupAll(".text-field").stream()
                        .filter(node -> node.getParent().getParent() instanceof javafx.scene.layout.HBox row
                                && row.getChildren().get(0) instanceof javafx.scene.control.Label label
                                && label.getText().equals("打标等待上限"))
                        .findFirst().orElseThrow();
                Button up = root.lookupAll(".stepper-button").stream()
                        .map(Button.class::cast).filter(button -> button.getText().equals("▲"))
                        .findFirst().orElseThrow();
                up.fire();
                up.fire();
                assertEquals("122000", input.getText());
                root.lookupAll(".button").stream().map(Button.class::cast)
                        .filter(button -> button.getText().equals("保存打标设置"))
                        .findFirst().orElseThrow().fire();
                verify(config).saveAll(Map.of(
                        ConfigKeys.LASER_SDK_DIRECTORY, "",
                        ConfigKeys.LASER_MARK_TIMEOUT_MS, "122000"));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }
}
