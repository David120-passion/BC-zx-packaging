package com.hq.iol.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.FxTestToolkit;
import com.hq.iol.label.DesignElements;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementPropertiesPanelTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Test
    void 所有组件的属性表单均可实际创建() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var design = mapper.createObjectNode();
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.put("dpi", 600);
        design.putArray("elements");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(() -> { }, () -> { }, message -> { });
                panel.build();
                panel.setDesign(design);
                for (String type : DesignElements.types()) {
                    panel.setElement(DesignElements.create(type, 1));
                }
                panel.setElement(null);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "属性表单应及时创建");
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    @Test
    void 清空选中后旧控件事件不会访问空对象() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var design = mapper.createObjectNode();
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.put("dpi", 600);
        design.putArray("elements");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(() -> { }, () -> { }, message -> { });
                var view = panel.build();
                panel.setDesign(design);
                for (String type : DesignElements.types()) {
                    panel.setElement(DesignElements.create(type, 1));
                    var textFields = new java.util.ArrayList<>(view.lookupAll(".text-field"));
                    var checkBoxes = new java.util.ArrayList<>(view.lookupAll(".check-box"));
                    var comboBoxes = new java.util.ArrayList<>(view.lookupAll(".combo-box"));
                    panel.setElement(null);
                    for (var node : textFields) {
                        var field = (javafx.scene.control.TextField) node;
                        if (field.getOnAction() != null) {
                            field.getOnAction().handle(new javafx.event.ActionEvent());
                        }
                    }
                    for (var node : checkBoxes) {
                        var checkBox = (javafx.scene.control.CheckBox) node;
                        checkBox.setSelected(!checkBox.isSelected());
                    }
                    for (var node : comboBoxes) {
                        ((javafx.scene.control.ComboBox<?>) node).getSelectionModel().selectFirst();
                    }
                }
                var rootTextFields = new java.util.ArrayList<>(view.lookupAll(".text-field"));
                var rootComboBoxes = new java.util.ArrayList<>(view.lookupAll(".combo-box"));
                panel.setDesign(null);
                for (var node : rootTextFields) {
                    var field = (javafx.scene.control.TextField) node;
                    if (field.getOnAction() != null) {
                        field.getOnAction().handle(new javafx.event.ActionEvent());
                    }
                }
                for (var node : rootComboBoxes) {
                    ((javafx.scene.control.ComboBox<?>) node).getSelectionModel().selectFirst();
                }
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "旧控件事件应及时处理");
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    @Test
    void 内容可切换固定值和变量且保留两种输入() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var design = mapper.createObjectNode();
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.put("dpi", 600);
        design.putArray("elements");
        var text = com.hq.iol.label.DesignElements.create("text", 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(() -> { }, () -> { }, message -> { },
                        () -> java.util.List.of(new com.hq.iol.label.TemplateVariable("serialNo", "序列号"),
                                new com.hq.iol.label.TemplateVariable("power", "光焦度")));
                Parent root = panel.build();
                panel.setDesign(design);
                panel.setElement(text);
                new javafx.scene.Scene(root);
                root.applyCss();
                TextField fixed = input(root, "固定值");
                fixed.setText("PCA81");
                fixed.getOnAction().handle(new javafx.event.ActionEvent());
                org.junit.jupiter.api.Assertions.assertEquals("PCA81", text.path("content").asText());

                javafx.scene.control.ComboBox<String> mode = choice(root, "内容来源");
                mode.getSelectionModel().select("变量");
                javafx.scene.control.ComboBox<com.hq.iol.label.TemplateVariable> variable = choice(root, "变量");
                variable.getSelectionModel().selectFirst();
                org.junit.jupiter.api.Assertions.assertEquals("variable", text.path("contentMode").asText());
                org.junit.jupiter.api.Assertions.assertEquals("serialNo", text.path("variableKey").asText());
                org.junit.jupiter.api.Assertions.assertEquals("PCA81", text.path("content").asText());
                variable.getSelectionModel().selectNext();
                org.junit.jupiter.api.Assertions.assertEquals("power", text.path("variableKey").asText());
                mode.getSelectionModel().select("固定值");
                org.junit.jupiter.api.Assertions.assertEquals("fixed", text.path("contentMode").asText());
                org.junit.jupiter.api.Assertions.assertEquals("power", text.path("variableKey").asText());
                org.junit.jupiter.api.Assertions.assertEquals("PCA81", input(root, "固定值").getText());

                panel.setElement(text);
                mode = choice(root, "内容来源");
                mode.getSelectionModel().select("变量");
                variable = choice(root, "变量");
                org.junit.jupiter.api.Assertions.assertEquals("power", variable.getValue().key());
                panel.setElement(null);
                mode.getSelectionModel().select("固定值");
                variable.getSelectionModel().selectFirst();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    @Test
    void GS1支持固定值与变量表达式且不再提供固定模板() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var design = mapper.createObjectNode();
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.put("dpi", 600);
        var matrix = com.hq.iol.label.DesignElements.create("datamatrix", 1);
        matrix.put("contentMode", "variable");
        matrix.put("content", "(01){{diNo}}");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(() -> { }, () -> { }, message -> { },
                        () -> java.util.List.of(new com.hq.iol.label.TemplateVariable("diNo", "产品 DI")));
                Parent root = panel.build();
                panel.setDesign(design);
                panel.setElement(matrix);
                new javafx.scene.Scene(root);
                root.applyCss();
                javafx.scene.control.CheckBox gs1 = (javafx.scene.control.CheckBox)
                        row(root, "GS1 模式").lookup(".check-box");
                gs1.setSelected(true);
                root.applyCss();
                org.junit.jupiter.api.Assertions.assertFalse(root.lookupAll(".label").stream()
                        .map(javafx.scene.control.Label.class::cast)
                        .anyMatch(label -> label.getText().equals("GS1 模板")));

                TextField expression = input(root, "变量表达式");
                expression.setText("(01){{diNo}}(11){{productionDate}}(17){{expiryDate}}"
                        + "(21){{serialNo}}(8200){{url}}");
                javafx.scene.control.ScrollPane viewport = new javafx.scene.control.ScrollPane(root);
                viewport.setFitToWidth(true);
                viewport.setPrefWidth(325);
                viewport.setPrefHeight(700);
                root.getScene().setRoot(viewport);
                viewport.applyCss();
                viewport.layout();
                org.junit.jupiter.api.Assertions.assertTrue(((javafx.scene.layout.Region) root).getWidth() <= 325,
                        "GS1 变量表达式不能撑出右侧属性栏");
                org.junit.jupiter.api.Assertions.assertTrue(row(root, "变量表达式").getWidth()
                        <= ((javafx.scene.layout.Region) root).getWidth(),
                        "变量表达式及插入变量区域必须限制在属性栏内");
                expression.setText("(01)");
                expression.positionCaret(expression.getText().length());
                expression.getOnAction().handle(new javafx.event.ActionEvent());
                javafx.scene.control.ComboBox<com.hq.iol.label.TemplateVariable> variable =
                        choice(root, "插入变量");
                variable.getSelectionModel().selectFirst();
                root.lookupAll(".button").stream().map(javafx.scene.control.Button.class::cast)
                        .filter(button -> button.getText().equals("插入")).findFirst().orElseThrow().fire();
                org.junit.jupiter.api.Assertions.assertEquals("(01){{diNo}}", matrix.path("content").asText());
                org.junit.jupiter.api.Assertions.assertEquals("variable", matrix.path("contentMode").asText());

                javafx.scene.control.ComboBox<String> mode = choice(root, "内容来源");
                mode.getSelectionModel().select("固定值");
                TextField fixed = input(root, "固定值");
                fixed.setText("(01)123456(02)123456");
                fixed.getOnAction().handle(new javafx.event.ActionEvent());
                org.junit.jupiter.api.Assertions.assertEquals("fixed", matrix.path("contentMode").asText());
                org.junit.jupiter.api.Assertions.assertEquals("(01)123456(02)123456",
                        matrix.path("content").asText());
                org.junit.jupiter.api.Assertions.assertFalse(matrix.has("gs1Mode"));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }

    private static TextField input(Parent root, String caption) {
        return (TextField) row(root, caption).lookup(".text-field");
    }

    private static <T> javafx.scene.control.ComboBox<T> choice(Parent root, String caption) {
        return (javafx.scene.control.ComboBox<T>) row(root, caption).lookup(".combo-box");
    }

    private static javafx.scene.layout.HBox row(Parent root, String caption) {
        return root.lookupAll(".field-label").stream().map(javafx.scene.control.Label.class::cast)
                .filter(label -> caption.equals(label.getText()))
                .map(label -> (javafx.scene.layout.HBox) label.getParent()).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到属性行: " + caption));
    }
}
