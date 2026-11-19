package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.service.LaserService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaserPanelTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Test
    void 打标页面读取软件内模板且默认测试模式() throws Exception {
        LaserService service = mock(LaserService.class);
        when(service.sdkDirectory()).thenReturn(Path.of("sdk"));
        ConfigService config = mock(ConfigService.class);
        when(config.get(ConfigKeys.LASER_TEMPLATE)).thenReturn("打标模板A");
        TemplateRepository repo = mock(TemplateRepository.class);
        when(repo.findAll()).thenReturn(List.of(template()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = new LaserPanel(service, config, new UiLogService(), repo).build();
                new javafx.scene.Scene(root);
                root.applyCss();
                CheckBox testMode = (CheckBox) root.lookup(".check-box");
                assertTrue(testMode.isSelected());
                assertTrue(button(root, "停止打标/红光").isDisabled());
                assertFalse(button(root, "开始打标").isDisabled());
                assertFalse(button(root, "红光指示").isDisabled());
                @SuppressWarnings("unchecked")
                ComboBox<LabelTemplate> box = (ComboBox<LabelTemplate>) root.lookup(".combo-box");
                assertTrue(box.getValue().name().equals("打标模板A"));
                assertTrue(root.lookupAll(".text-field").stream().anyMatch(node ->
                        ((TextField) node).getPromptText().contains("code")));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
        verify(service, never()).markDesign(anyString(), anyMap(), anyBoolean());
    }

    @Test
    void 打标超时后仍可停止且停止成功后恢复操作() throws Exception {
        LaserService service = mock(LaserService.class);
        when(service.sdkDirectory()).thenReturn(Path.of("sdk"));
        ConfigService config = mock(ConfigService.class);
        when(config.get(ConfigKeys.LASER_TEMPLATE)).thenReturn("打标模板A");
        TemplateRepository repo = mock(TemplateRepository.class);
        when(repo.findAll()).thenReturn(List.of(template()));
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            attempted.countDown();
            throw new java.io.IOException("激光设备响应超时");
        }).when(service).markDesign(anyString(), anyMap(), anyBoolean());
        org.mockito.Mockito.doAnswer(invocation -> {
            stopped.countDown();
            return null;
        }).when(service).stopMarking();
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                root.set(new LaserPanel(service, config, new UiLogService(), repo).build());
                button(root.get(), "开始打标").fire();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        assertTrue(attempted.await(10, TimeUnit.SECONDS));
        CountDownLatch stopEnabled = new CountDownLatch(1);
        Platform.runLater(() -> awaitButtonState(root, "停止打标/红光", false, stopEnabled));
        assertTrue(stopEnabled.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> button(root.get(), "停止打标/红光").fire());
        assertTrue(stopped.await(10, TimeUnit.SECONDS));
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> awaitButtonState(root, "停止打标/红光", true, done));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    @Test
    void 红光按钮按当前模板和变量执行指示() throws Exception {
        LaserService service = mock(LaserService.class);
        when(service.sdkDirectory()).thenReturn(Path.of("sdk"));
        ConfigService config = mock(ConfigService.class);
        when(config.get(ConfigKeys.LASER_TEMPLATE)).thenReturn("打标模板A");
        TemplateRepository repo = mock(TemplateRepository.class);
        when(repo.findAll()).thenReturn(List.of(template()));
        CountDownLatch requested = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            requested.countDown();
            return null;
        }).when(service).redLightDesign(anyString(), anyMap());

        Platform.runLater(() -> button(
                new LaserPanel(service, config, new UiLogService(), repo).build(), "红光指示").fire());
        assertTrue(requested.await(10, TimeUnit.SECONDS));
        verify(service).redLightDesign(anyString(), anyMap());
        verify(service, never()).markDesign(anyString(), anyMap(), anyBoolean());
    }

    @Test
    void 连续红光再次点击按钮可关闭() throws Exception {
        LaserService service = mock(LaserService.class);
        when(service.sdkDirectory()).thenReturn(Path.of("sdk"));
        ConfigService config = mock(ConfigService.class);
        when(config.get(ConfigKeys.LASER_TEMPLATE)).thenReturn("打标模板A");
        TemplateRepository repo = mock(TemplateRepository.class);
        when(repo.findAll()).thenReturn(List.of(template()));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(service).redLightDesign(anyString(), anyMap());
        org.mockito.Mockito.doAnswer(invocation -> {
            stopped.countDown();
            return null;
        }).when(service).stopMarking();
        AtomicReference<Parent> root = new AtomicReference<>();
        Platform.runLater(() -> {
            root.set(new LaserPanel(service, config, new UiLogService(), repo).build());
            button(root.get(), "红光指示").fire();
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> button(root.get(), "关闭红光").fire());
        assertTrue(stopped.await(10, TimeUnit.SECONDS));
        release.countDown();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> awaitButtonText(root, "红光指示", done));
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    private static LabelTemplate template() {
        return new LabelTemplate(1, "打标模板A", "{\"widthMm\":100,\"heightMm\":80,\"pen\":0,"
                + "\"elements\":[{\"id\":\"text_1\",\"type\":\"text\",\"x\":5,\"y\":5,"
                + "\"widthMm\":50,\"heightMm\":8,\"contentMode\":\"variable\",\"variableKey\":\"code\","
                + "\"content\":\"\",\"fontSizeMm\":3}]}", false, "");
    }

    private static void awaitButtonText(AtomicReference<Parent> root, String text, CountDownLatch ready) {
        boolean found = root.get().lookupAll(".button").stream().map(Button.class::cast)
                .anyMatch(button -> text.equals(button.getText()));
        if (found) {
            ready.countDown();
            return;
        }
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(20));
        pause.setOnFinished(event -> awaitButtonText(root, text, ready));
        pause.play();
    }

    private static void awaitButtonState(AtomicReference<Parent> root, String text,
                                         boolean disabled, CountDownLatch ready) {
        if (button(root.get(), text).isDisabled() == disabled) {
            ready.countDown();
            return;
        }
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(20));
        pause.setOnFinished(event -> awaitButtonState(root, text, disabled, ready));
        pause.play();
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream().map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }
}
