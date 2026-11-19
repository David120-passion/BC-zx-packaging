package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.service.LaserService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
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

class LaserDesignPanelTest {

    @TempDir
    Path dir;

    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void 打标设计复用组件画布且与普通标签模板隔离() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("design.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        TemplateRepository laser = new TemplateRepository(jdbc, initializer, "laser_template");
        TemplateRepository labels = new TemplateRepository(jdbc, initializer);
        laser.insert("共用名称", "{\"widthMm\":100,\"heightMm\":100,\"pen\":0,\"elements\":[]}", false);
        labels.insert("共用名称", "{\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":[]}", false);
        LaserService service = mock(LaserService.class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = new LaserDesignPanel(laser, service, new UiLogService()).build();
                new javafx.scene.Scene(root);
                root.applyCss();
                assertTrue(root.lookupAll(".label").stream().map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("打标设置")));
                assertTrue(root.lookupAll(".label").stream().map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("加工笔号")));
                assertFalse(root.lookupAll(".label").stream().map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("DPI")));
                assertTrue(root.lookupAll(".button").stream().map(Button.class::cast)
                        .anyMatch(button -> button.getText().equals("对象预览")));
                assertTrue(root.lookupAll(".button").stream().map(Button.class::cast)
                        .anyMatch(button -> button.getText().equals("测试打标（不出光）")));
                var templates = (javafx.scene.control.ListView<?>) root.lookup(".list-view");
                org.junit.jupiter.api.Assertions.assertEquals(32, templates.getFixedCellSize());
                org.junit.jupiter.api.Assertions.assertEquals(324, templates.getPrefHeight());
                org.junit.jupiter.api.Assertions.assertEquals(324, templates.getMaxHeight());
                assertFalse(root.lookupAll(".label").stream().map(Label.class::cast)
                        .anyMatch(label -> label.getText().equals("模板变量")));
                assertFalse(labels.findByName("共用名称").designJson().contains("laser_template"));
                assertTrue(labels.findByName("共用名称").designJson().contains("600"));
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
}
