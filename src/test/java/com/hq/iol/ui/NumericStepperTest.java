package com.hq.iol.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.FxTestToolkit;
import com.hq.iol.label.DesignElements;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericStepperTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Test
    void 数字属性和档位均可上下调节且不会越界() throws Exception {
        ObjectNode design = new ObjectMapper().createObjectNode();
        design.put("widthMm", 100);
        design.put("heightMm", 100);
        design.put("pen", 0);
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(
                        changes::incrementAndGet, () -> { }, message -> { });
                Parent root = panel.build();
                panel.setLaserMode(true);
                panel.setDesign(design);
                ObjectNode text = DesignElements.create("text", 1);
                panel.setElement(text);
                new javafx.scene.Scene(root);
                root.applyCss();

                step(root, "宽度 (mm)", true);
                assertEquals(100.5, design.path("widthMm").asDouble());
                step(root, "宽度 (mm)", false);
                assertEquals(100.0, design.path("widthMm").asDouble());
                step(root, "X 坐标 (mm)", true);
                assertEquals(7.5, text.path("x").asDouble());
                step(root, "字号 (mm)", true);
                assertEquals(3.5, text.path("fontSizeMm").asDouble());
                step(root, "加工笔号", true);
                assertEquals(1, design.path("pen").asInt());
                step(root, "旋转", true);
                assertEquals(90, text.path("rotation").asInt());
                step(root, "旋转", false);
                assertEquals(0, text.path("rotation").asInt());

                ObjectNode table = DesignElements.create("table", 2);
                panel.setElement(table);
                root.applyCss();
                step(root, "行数", true);
                assertEquals(4, table.path("rows").asInt());
                step(root, "边框 (mm)", true);
                assertEquals(0.4, table.path("thicknessMm").asDouble(), 0.0001);
                input(root, "行数").setText("20");
                step(root, "行数", true);
                assertEquals(20, table.path("rows").asInt());
                step(root, "行数", false);
                assertEquals(19, table.path("rows").asInt());

                panel.setElement(text);
                root.applyCss();
                input(root, "X 坐标 (mm)").setText("not-a-number");
                step(root, "X 坐标 (mm)", true);
                assertEquals(8.0, text.path("x").asDouble());
                assertTrue(changes.get() > 0);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    private static void step(Parent root, String caption, boolean increase) {
        Button[] buttons = row(root, caption).lookupAll(".stepper-button").toArray(Button[]::new);
        buttons[increase ? 0 : 1].fire();
    }

    private static javafx.scene.control.TextField input(Parent root, String caption) {
        return (javafx.scene.control.TextField) row(root, caption).lookup(".text-field");
    }

    private static HBox row(Parent root, String caption) {
        return Stream.concat(root.lookupAll(".text-field").stream(), root.lookupAll(".combo-box").stream())
                .filter(node -> node.getParent().getParent() instanceof HBox outer
                        && outer.getChildren().get(0) instanceof Label label
                        && caption.equals(label.getText()))
                .map(node -> (HBox) node.getParent().getParent()).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到属性行: " + caption));
    }
}
