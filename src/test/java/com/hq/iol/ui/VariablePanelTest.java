package com.hq.iol.ui;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.config.DbInitializer;
import com.hq.iol.label.TemplateVariable;
import com.hq.iol.label.VariableRepository;
import com.hq.iol.service.VariableService;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariablePanelTest {

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
    void 变量和备注可保存且运行时值可刷新显示() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("variables.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DbInitializer initializer = new DbInitializer(jdbc);
        VariableService service = new VariableService(new VariableRepository(jdbc, initializer));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Parent root = new VariablePanel(service, new UiLogService()).build();
                new javafx.scene.Scene(root);
                root.applyCss();
                input(root, "变量名").setText("serialNo");
                input(root, "备注").setText("序列号");
                button(root, "添加变量").fire();
                assertEquals(java.util.List.of(new TemplateVariable("serialNo", "序列号")), service.list());
                assertTrue(input(root, "变量名").isDisable());

                input(root, "备注").setText("产品唯一序列号");
                button(root, "保存备注").fire();
                assertEquals("产品唯一序列号", service.list().get(0).remark());
                service.setValue("serialNo", "SN001");
                button(root, "刷新").fire();
                assertEquals("SN001", input(root, "运行时值").getText());
                button(root, "清空以新增").fire();
                assertFalse(input(root, "变量名").isDisable());
                assertEquals("", input(root, "变量名").getText());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    private static TextField input(Parent root, String caption) {
        return (TextField) root.lookupAll(".text-field").stream()
                .filter(node -> node.getParent() instanceof GridPane grid
                        && grid.getChildren().stream().anyMatch(sibling -> sibling instanceof Label label
                        && caption.equals(label.getText())
                        && java.util.Objects.equals(GridPane.getRowIndex(label), GridPane.getRowIndex(node))))
                .findFirst().orElseThrow(() -> new IllegalStateException("未找到输入框: " + caption));
    }

    private static Button button(Parent root, String caption) {
        return root.lookupAll(".button").stream().map(Button.class::cast)
                .filter(button -> caption.equals(button.getText())).findFirst().orElseThrow();
    }
}
