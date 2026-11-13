package com.hq.iol.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.service.PrintService;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 标签设计器：左模板列表 / 中画布（预览 + 拖拽）/ 右属性表单。
 * 拖拽只改 x/y，松手后重新渲染预览；保存写 design JSON 回仓储。
 */
@Component
public class DesignerPanel {

    private static final double PX_PER_MM = 4.0;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository repo;
    private final PrintService printService;
    private final UiLogService uiLog;

    private final ListView<String> templateList = new ListView<>();
    private final Canvas canvas = new Canvas();
    private final VBox propsBox = new VBox(6);
    private final Label varsLabel = new Label();

    private ObjectNode design;
    private String currentName;
    private JsonNode selected;

    public DesignerPanel(TemplateRepository repo, PrintService printService, UiLogService uiLog) {
        this.repo = repo;
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Button newBtn = new Button("新建");
        newBtn.setOnAction(e -> newTemplate());
        Button delBtn = new Button("删除");
        delBtn.setOnAction(e -> deleteTemplate());
        Button saveBtn = new Button("保存");
        saveBtn.setOnAction(e -> save());
        Button printBtn = new Button("测试打印");
        printBtn.setOnAction(e -> testPrint());
        HBox actions = new HBox(6, newBtn, delBtn, saveBtn, printBtn);

        templateList.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, name) -> loadTemplate(name));
        reloadList();

        canvas.setOnMousePressed(e -> selectAt(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> dragTo(e.getX(), e.getY()));
        canvas.setOnMouseReleased(e -> renderPreview());

        VBox left = new VBox(6, templateList, actions);
        VBox right = new VBox(6, new Label("属性"), propsBox, new Label("变量"), varsLabel);

        BorderPane root = new BorderPane(canvas);
        root.setLeft(left);
        root.setRight(right);
        return root;
    }

    private void reloadList() {
        templateList.getItems().setAll(
                repo.findAll().stream().map(t -> t.name()).toList());
    }

    private void loadTemplate(String name) {
        if (name == null) {
            return;
        }
        try {
            currentName = name;
            design = (ObjectNode) MAPPER.readTree(repo.findByName(name).designJson());
            canvas.setWidth(design.path("widthMm").asDouble() * PX_PER_MM);
            canvas.setHeight(design.path("heightMm").asDouble() * PX_PER_MM);
            selected = null;
            renderPreview();
            rebuildProps();
            varsLabel.setText(String.join(", ", com.hq.iol.label.VariableSupport
                    .extract(design.toString())));
        } catch (Exception e) {
            uiLog.error("模板加载失败: " + e.getMessage());
        }
    }

    private void renderPreview() {
        if (design == null) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        try {
            Image img = SwingFXUtils.toFXImage(
                    LabelRenderer.renderPreview(design.toString(), Map.of()), null);
            g.drawImage(img, 0, 0);
        } catch (Exception e) {
            uiLog.error("预览渲染失败: " + e.getMessage());
        }
        if (selected != null) {
            g.setStroke(javafx.scene.paint.Color.RED);
            g.strokeRect(selected.path("x").asDouble() * PX_PER_MM,
                    selected.path("y").asDouble() * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM);
        }
    }

    private void selectAt(double px, double py) {
        if (design == null) {
            return;
        }
        ArrayNode elements = (ArrayNode) design.path("elements");
        selected = null;
        for (JsonNode el : elements) {
            double ex = el.path("x").asDouble() * PX_PER_MM;
            double ey = el.path("y").asDouble() * PX_PER_MM;
            if (px >= ex && px <= ex + 40 && py >= ey && py <= ey + 40) {
                selected = el;
            }
        }
        rebuildProps();
        renderPreview();
    }

    private void dragTo(double px, double py) {
        if (selected == null) {
            return;
        }
        ((ObjectNode) selected).put("x", Math.round(px / PX_PER_MM * 2) / 2.0);
        ((ObjectNode) selected).put("y", Math.round(py / PX_PER_MM * 2) / 2.0);
        renderPreview();
    }

    private void rebuildProps() {
        propsBox.getChildren().clear();
        if (selected == null) {
            propsBox.getChildren().add(new Label("未选中元素"));
            return;
        }
        propsBox.getChildren().add(new Label("类型: " + selected.path("type").asText()));
        addNumberField("x", selected.path("x").asDouble());
        addNumberField("y", selected.path("y").asDouble());
        selected.fieldNames().forEachRemaining(f -> {
            if (!"id".equals(f) && !"type".equals(f) && !"x".equals(f) && !"y".equals(f)) {
                JsonNode v = selected.path(f);
                TextField tf = new TextField(v.isNumber() ? String.valueOf(v.asDouble())
                        : v.asText());
                tf.setOnAction(e -> applyProp(f, tf.getText()));
                propsBox.getChildren().addAll(new Label(f), tf);
            }
        });
    }

    private void addNumberField(String field, double value) {
        TextField tf = new TextField(String.valueOf(value));
        tf.setOnAction(e -> applyProp(field, tf.getText()));
        propsBox.getChildren().addAll(new Label(field), tf);
    }

    private void applyProp(String field, String raw) {
        if (selected == null) {
            return;
        }
        try {
            double d = Double.parseDouble(raw);
            ((ObjectNode) selected).put(field, d);
        } catch (NumberFormatException e) {
            ((ObjectNode) selected).put(field, raw);
        }
        renderPreview();
    }

    private void save() {
        if (design == null || currentName == null) {
            uiLog.error("无打开的模板");
            return;
        }
        runAsync(() -> {
            repo.update(currentName, design.toString());
            uiLog.info("模板已保存: " + currentName);
        });
    }

    private void newTemplate() {
        TextInputDialog dialog = new TextInputDialog("新模板");
        dialog.setHeaderText("模板名称");
        dialog.showAndWait().ifPresent(name -> runAsync(() -> {
            repo.insert(name, "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,"
                    + "\"elements\":[]}", false);
            javafx.application.Platform.runLater(this::reloadList);
            uiLog.info("模板已创建: " + name);
        }));
    }

    private void deleteTemplate() {
        String name = templateList.getSelectionModel().getSelectedItem();
        if (name == null) {
            return;
        }
        runAsync(() -> {
            repo.delete(name);
            javafx.application.Platform.runLater(this::reloadList);
            uiLog.info("模板已删除: " + name);
        });
    }

    private void testPrint() {
        if (currentName == null) {
            uiLog.error("请先打开模板");
            return;
        }
        runAsync(() -> {
            printService.print(currentName, Map.of());
            uiLog.info("测试打印已下发: " + currentName + "（变量无值时保留原样）");
        });
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage() != null ? ex.getMessage() : ex.toString());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
