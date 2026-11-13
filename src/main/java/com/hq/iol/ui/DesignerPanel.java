package com.hq.iol.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.label.VariableSupport;
import com.hq.iol.service.PrintService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 标签设计器：模板列表、中央预览画布和右侧属性工作区。 */
@Component
public class DesignerPanel {

    private static final double PX_PER_MM = 4.0;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository repo;
    private final PrintService printService;
    private final UiLogService uiLog;

    private final ListView<LabelTemplate> templateList = new ListView<>();
    private final Canvas canvas = new Canvas();
    private final StackPane paper = new StackPane(canvas);
    private final Label emptyCanvas = new Label("暂无模板，请新建或选择模板");
    private final VBox propsBox = new VBox(8);
    private final Label varsLabel = new Label();
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final Button newButton = new Button("新建");
    private final Button deleteButton = new Button("删除");
    private final Button saveButton = new Button("保存模板");
    private final Button printButton = new Button("测试打印");

    private ObjectNode design;
    private String currentName;
    private boolean currentBuiltin;
    private JsonNode selected;

    public DesignerPanel(TemplateRepository repo, PrintService printService, UiLogService uiLog) {
        this.repo = repo;
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        configureTemplateList();
        configureActions();
        configureCanvas();

        Label listTitle = new Label("模板库");
        listTitle.getStyleClass().add("card-title");
        HBox listActions = new HBox(8, newButton, deleteButton);
        VBox left = new VBox(10, listTitle, templateList, listActions);
        left.getStyleClass().add("card");
        left.setPrefWidth(205);
        left.setMinWidth(180);
        VBox.setVgrow(templateList, Priority.ALWAYS);

        saveButton.getStyleClass().add("btn-primary");
        HBox toolbar = new HBox(8, saveButton, printButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox toolbarCard = new VBox(6, toolbar, banner.node());
        toolbarCard.getStyleClass().add("toolbar-card");

        emptyCanvas.getStyleClass().add("empty-state");
        StackPane canvasHost = new StackPane(paper, emptyCanvas);
        StackPane.setMargin(paper, new Insets(24));
        ScrollPane canvasScroll = new ScrollPane(canvasHost);
        canvasScroll.getStyleClass().add("designer-canvas");
        canvasScroll.setPannable(true);
        canvasScroll.setFitToWidth(true);
        canvasScroll.setFitToHeight(true);
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);
        VBox center = new VBox(10, toolbarCard, canvasScroll);
        center.setMinWidth(0);

        Label propsTitle = new Label("元素属性");
        propsTitle.getStyleClass().add("card-title");
        ScrollPane propsScroll = new ScrollPane(propsBox);
        propsScroll.setFitToWidth(true);
        propsScroll.getStyleClass().add("config-scroll");
        VBox.setVgrow(propsScroll, Priority.ALWAYS);
        VBox propsCard = new VBox(10, propsTitle, propsScroll);
        propsCard.getStyleClass().add("card");
        VBox.setVgrow(propsCard, Priority.ALWAYS);

        Label varsTitle = new Label("模板变量");
        varsTitle.getStyleClass().add("card-title");
        varsLabel.getStyleClass().add("hint");
        varsLabel.setWrapText(true);
        Label previewHint = new Label("画布中的 GS1 码使用示例数据，仅用于布局预览；测试打印使用已保存模板。");
        previewHint.getStyleClass().add("hint");
        previewHint.setWrapText(true);
        VBox varsCard = new VBox(8, varsTitle, varsLabel, previewHint);
        varsCard.getStyleClass().add("card");

        VBox right = new VBox(12, propsCard, varsCard);
        right.setPrefWidth(285);
        right.setMinWidth(250);

        BorderPane root = new BorderPane();
        root.setLeft(left);
        root.setCenter(center);
        root.setRight(right);
        BorderPane.setMargin(left, new Insets(0, 12, 0, 0));
        BorderPane.setMargin(right, new Insets(0, 0, 0, 12));

        reloadList();
        busy.addListener((obs, oldValue, newValue) -> updateActionState());
        return root;
    }

    private void configureTemplateList() {
        templateList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LabelTemplate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.name());
                name.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(name, Priority.ALWAYS);
                HBox row = new HBox(6, name);
                row.setAlignment(Pos.CENTER_LEFT);
                if (item.builtin()) {
                    Label badge = new Label("内置");
                    badge.getStyleClass().add("template-badge");
                    row.getChildren().add(badge);
                }
                setGraphic(row);
            }
        });
        templateList.setPlaceholder(new Label("暂无模板，请新建"));
        templateList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, template) -> loadTemplate(template));
    }

    private void configureActions() {
        newButton.setOnAction(e -> newTemplate());
        deleteButton.setOnAction(e -> deleteTemplate());
        saveButton.setOnAction(e -> save());
        printButton.setOnAction(e -> showPrintDialog());
        printButton.setTooltip(new javafx.scene.control.Tooltip("测试打印使用已保存模板，请先保存设计修改"));
    }

    private void configureCanvas() {
        paper.getStyleClass().add("designer-paper");
        canvas.setOnMousePressed(e -> selectAt(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> dragTo(e.getX(), e.getY()));
        canvas.setOnMouseReleased(e -> renderPreview());
    }

    private void reloadList() {
        List<LabelTemplate> templates = repo.findAll();
        templateList.getItems().setAll(templates);
        templates.stream().filter(t -> t.name().equals(currentName)).findFirst()
                .ifPresentOrElse(templateList.getSelectionModel()::select, () -> {
                    if (!templates.isEmpty()) {
                        templateList.getSelectionModel().selectFirst();
                    } else {
                        clearWorkspace();
                    }
                });
        updateActionState();
    }

    private void loadTemplate(LabelTemplate template) {
        if (template == null) {
            clearWorkspace();
            return;
        }
        try {
            currentName = template.name();
            currentBuiltin = template.builtin();
            design = (ObjectNode) MAPPER.readTree(template.designJson());
            double width = design.path("widthMm").asDouble() * PX_PER_MM;
            double height = design.path("heightMm").asDouble() * PX_PER_MM;
            canvas.setWidth(width);
            canvas.setHeight(height);
            paper.setMinSize(width, height);
            paper.setPrefSize(width, height);
            paper.setMaxSize(width, height);
            paper.setVisible(true);
            paper.setManaged(true);
            emptyCanvas.setVisible(false);
            emptyCanvas.setManaged(false);
            selected = null;
            renderPreview();
            rebuildProps();
            varsLabel.setText(String.join(", ", currentVariables()));
        } catch (Exception e) {
            banner.fail("模板加载失败：" + e.getMessage());
            uiLog.error("模板加载失败: " + e.getMessage());
        }
        updateActionState();
    }

    private void clearWorkspace() {
        design = null;
        currentName = null;
        currentBuiltin = false;
        selected = null;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        paper.setVisible(false);
        paper.setManaged(false);
        emptyCanvas.setVisible(true);
        emptyCanvas.setManaged(true);
        propsBox.getChildren().clear();
        propsBox.getChildren().add(emptyLabel("请选择画布中的元素"));
        varsLabel.setText("");
        updateActionState();
    }

    private void updateActionState() {
        boolean noTemplate = currentName == null;
        newButton.setDisable(busy.get());
        deleteButton.setDisable(busy.get() || noTemplate || currentBuiltin);
        saveButton.setDisable(busy.get() || noTemplate);
        printButton.setDisable(busy.get() || noTemplate);
        templateList.setDisable(busy.get());
        canvas.setDisable(busy.get());
    }

    private Set<String> currentVariables() {
        Set<String> vars = new LinkedHashSet<>(VariableSupport.extract(design.toString()));
        if (design.toString().contains("\"gs1Mode\":\"lens\"")) {
            vars.addAll(Set.of("diNo", "sterileDate", "serialNo"));
        }
        if (design.toString().contains("\"gs1Mode\":\"injector\"")) {
            vars.addAll(Set.of("diNo", "productionDate", "expiryDate", "lotNo"));
        }
        return vars;
    }

    private void renderPreview() {
        if (design == null) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        try {
            Image img = SwingFXUtils.toFXImage(
                    LabelRenderer.renderPreview(design.toString(), previewVariables()), null);
            g.drawImage(img, 0, 0);
        } catch (Exception e) {
            banner.fail("预览渲染失败：" + e.getMessage());
            uiLog.error("预览渲染失败: " + e.getMessage());
        }
        if (selected != null) {
            g.setStroke(javafx.scene.paint.Color.RED);
            g.setLineWidth(1.5);
            g.strokeRect(selected.path("x").asDouble() * PX_PER_MM,
                    selected.path("y").asDouble() * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM,
                    selected.path("sizeMm").asDouble(10) * PX_PER_MM);
        }
    }

    private static Map<String, String> previewVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("diNo", "06912345678901");
        vars.put("sterileDate", "2026-01-01");
        vars.put("serialNo", "2601010101");
        vars.put("productionDate", "2026-01-01");
        vars.put("expiryDate", "2031-01-01");
        vars.put("lotNo", "LOT001");
        return vars;
    }

    private void selectAt(double px, double py) {
        if (design == null) {
            return;
        }
        ArrayNode elements = (ArrayNode) design.path("elements");
        selected = null;
        for (JsonNode element : elements) {
            double ex = element.path("x").asDouble() * PX_PER_MM;
            double ey = element.path("y").asDouble() * PX_PER_MM;
            if (px >= ex && px <= ex + 40 && py >= ey && py <= ey + 40) {
                selected = element;
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
            propsBox.getChildren().add(emptyLabel("请选择画布中的元素"));
            return;
        }
        Label type = new Label("类型：" + selected.path("type").asText());
        type.getStyleClass().add("field-label");
        propsBox.getChildren().add(type);
        addNumberField("x", selected.path("x").asDouble());
        addNumberField("y", selected.path("y").asDouble());
        selected.fieldNames().forEachRemaining(field -> {
            if (!"id".equals(field) && !"type".equals(field) && !"x".equals(field) && !"y".equals(field)) {
                JsonNode value = selected.path(field);
                TextField input = new TextField(value.isNumber()
                        ? String.valueOf(value.asDouble()) : value.asText());
                input.setOnAction(e -> applyProp(field, input.getText()));
                propsBox.getChildren().addAll(fieldLabel(field), input);
            }
        });
    }

    private void addNumberField(String field, double value) {
        TextField input = new TextField(String.valueOf(value));
        input.setOnAction(e -> applyProp(field, input.getText()));
        propsBox.getChildren().addAll(fieldLabel(field), input);
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label emptyLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("empty-state");
        label.setWrapText(true);
        return label;
    }

    private void applyProp(String field, String raw) {
        if (selected == null) {
            return;
        }
        try {
            ((ObjectNode) selected).put(field, Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            ((ObjectNode) selected).put(field, raw);
        }
        renderPreview();
    }

    private void save() {
        ObjectNode designSnapshot = design;
        String nameSnapshot = currentName;
        if (designSnapshot == null || nameSnapshot == null) {
            banner.fail("请先打开模板");
            return;
        }
        String json = designSnapshot.toString();
        startTask("正在保存模板…", () -> {
            repo.update(nameSnapshot, json);
            banner.success("模板已保存：" + nameSnapshot);
            uiLog.info("模板已保存: " + nameSnapshot);
        });
    }

    private void newTemplate() {
        TextInputDialog dialog = new TextInputDialog("新模板");
        dialog.setTitle("新建模板");
        dialog.setHeaderText("请输入模板名称");
        dialog.showAndWait().ifPresent(rawName -> {
            String name = rawName.trim();
            if (name.isEmpty()) {
                banner.fail("模板名称不能为空");
                return;
            }
            startTask("正在创建模板…", () -> {
                repo.insert(name, "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,"
                        + "\"elements\":[]}", false);
                Platform.runLater(() -> {
                    currentName = name;
                    reloadList();
                    banner.success("模板已创建：" + name);
                });
                uiLog.info("模板已创建: " + name);
            });
        });
    }

    private void deleteTemplate() {
        LabelTemplate template = templateList.getSelectionModel().getSelectedItem();
        if (template == null || template.builtin()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除模板「" + template.name() + "」吗？此操作不可撤销。",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("删除模板");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String name = template.name();
        startTask("正在删除模板…", () -> {
            repo.delete(name);
            Platform.runLater(() -> {
                clearWorkspace();
                reloadList();
                banner.success("模板已删除：" + name);
            });
            uiLog.info("模板已删除: " + name);
        });
    }

    private void showPrintDialog() {
        String nameSnapshot = currentName;
        if (nameSnapshot == null) {
            banner.fail("请先打开模板");
            return;
        }
        Set<String> variables = printService.templateVariables(nameSnapshot);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("测试打印");
        dialog.setHeaderText("录入「" + nameSnapshot + "」的打印变量");
        ButtonType printType = new ButtonType("打印", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, printType);
        if (canvas.getScene() != null) {
            dialog.initOwner(canvas.getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(canvas.getScene().getStylesheets());
        }

        Map<String, TextField> fields = new LinkedHashMap<>();
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;
        for (String variable : variables) {
            TextField field = new TextField();
            field.setPromptText("请输入 " + variable);
            fields.put(variable, field);
            grid.addRow(row++, fieldLabel(variable), field);
        }
        if (variables.isEmpty()) {
            grid.add(emptyLabel("此模板无需录入变量"), 0, 0);
        }
        dialog.getDialogPane().setContent(new ScrollPane(grid));
        dialog.getDialogPane().setPrefWidth(460);
        dialog.getDialogPane().lookupButton(printType).disableProperty().bind(busy);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != printType) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, field) -> values.put(key, field.getText()));
        Map<String, String> snapshot = Map.copyOf(values);
        startTask("正在下发测试打印…", () -> {
            printService.print(nameSnapshot, snapshot);
            banner.success("测试打印已下发：" + nameSnapshot);
            uiLog.info("测试打印已下发: " + nameSnapshot);
        });
    }

    private void startTask(String status, IoAction action) {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        banner.running(status);
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                banner.fail(message);
                uiLog.error(message);
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "designer-ui-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
