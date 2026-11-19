package com.hq.iol.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.DesignElements;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.label.VariableSupport;
import com.hq.iol.service.PrintService;
import com.hq.iol.service.LaserService;
import com.hq.iol.service.VariableService;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用标签设计器：模板库、组件面板、画布和类型化属性编辑。 */
@Component
public class DesignerPanel {

    private static final double PX_PER_MM = 4.0;
    private static final int TEMPLATE_ROWS = 10;
    private static final double TEMPLATE_ROW_HEIGHT = 32;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TemplateRepository repo;
    private final PrintService printService;
    private final LaserService laserService;
    private final VariableService variableService;
    private final OutputMode outputMode;
    private final UiLogService uiLog;
    private final ElementPropertiesPanel properties;

    public enum OutputMode {LABEL, LASER}

    private final ListView<LabelTemplate> templateList = new ListView<>();
    private final Canvas canvas = new Canvas();
    private final StackPane paper = new StackPane(canvas);
    private final ScrollPane canvasScroll = new ScrollPane();
    private final Label emptyCanvas = new Label("暂无模板，请新建或选择模板");
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final Button newButton = new Button("新建");
    private final Button deleteButton = new Button("删除");
    private final Button saveButton = new Button("保存模板");
    private final Button printButton = new Button("测试打印");
    private final Button previewButton = new Button("TSPL 预览");

    private ObjectNode design;
    private String currentName;
    private boolean currentBuiltin;
    private JsonNode selected;
    private int elementCounter;
    private double dragOffsetX;
    private double dragOffsetY;
    private Parent propertiesView;

    @org.springframework.beans.factory.annotation.Autowired
    public DesignerPanel(TemplateRepository repo, PrintService printService, UiLogService uiLog,
                         VariableService variableService) {
        this(repo, printService, null, variableService, uiLog, OutputMode.LABEL);
    }

    public DesignerPanel(TemplateRepository repo, LaserService laserService, UiLogService uiLog) {
        this(repo, null, laserService, null, uiLog, OutputMode.LASER);
    }

    public DesignerPanel(TemplateRepository repo, LaserService laserService, UiLogService uiLog,
                         VariableService variableService) {
        this(repo, null, laserService, variableService, uiLog, OutputMode.LASER);
    }

    private DesignerPanel(TemplateRepository repo, PrintService printService, LaserService laserService,
                          VariableService variableService, UiLogService uiLog, OutputMode outputMode) {
        this.repo = repo;
        this.printService = printService;
        this.laserService = laserService;
        this.variableService = variableService;
        this.outputMode = outputMode;
        this.uiLog = uiLog;
        this.properties = new ElementPropertiesPanel(
                this::onDesignChanged, this::deleteSelectedElement, banner::fail,
                () -> variableService == null ? List.of() : variableService.list());
        this.properties.setLaserMode(outputMode == OutputMode.LASER);
        previewButton.setText(outputMode == OutputMode.LASER ? "对象预览" : "TSPL 预览");
        printButton.setText(outputMode == OutputMode.LASER ? "测试打标（不出光）" : "测试打印");
    }

    public Parent build() {
        configureTemplateList();
        configureActions();
        configureCanvas();

        Label listTitle = new Label("模板库");
        listTitle.getStyleClass().add("card-title");
        HBox listActions = new HBox(8, newButton, deleteButton);
        Label componentTitle = new Label("组件（点击添加）");
        componentTitle.getStyleClass().add("card-title");
        GridPane palette = componentPalette();
        VBox left = new VBox(10, listTitle, templateList, listActions, componentTitle, palette);
        left.getStyleClass().add("card");
        left.setPrefWidth(220);
        left.setMinWidth(195);
        templateList.setFixedCellSize(TEMPLATE_ROW_HEIGHT);
        templateList.setPrefHeight(TEMPLATE_ROWS * TEMPLATE_ROW_HEIGHT + 4);
        templateList.setMinHeight(templateList.getPrefHeight());
        templateList.setMaxHeight(templateList.getPrefHeight());
        VBox.setVgrow(templateList, Priority.NEVER);
        palette.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(palette, Priority.ALWAYS);

        saveButton.getStyleClass().add("btn-primary");
        HBox toolbar = new HBox(8, saveButton, previewButton, printButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox toolbarCard = new VBox(6, toolbar, banner.node());
        toolbarCard.getStyleClass().add("toolbar-card");

        emptyCanvas.getStyleClass().add("empty-state");
        StackPane canvasHost = new StackPane(paper, emptyCanvas);
        StackPane.setMargin(paper, new Insets(24));
        canvasScroll.setContent(canvasHost);
        canvasScroll.getStyleClass().add("designer-canvas");
        canvasScroll.setPannable(true);
        canvasScroll.setFitToWidth(true);
        canvasScroll.setFitToHeight(true);
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);

        VBox bottomBlankCard = new VBox();
        bottomBlankCard.getStyleClass().add("toolbar-card");
        bottomBlankCard.setMinHeight(80);
        bottomBlankCard.setPrefHeight(80);
        bottomBlankCard.setMaxHeight(80);
        VBox center = new VBox(10, toolbarCard, canvasScroll, bottomBlankCard);
        center.setMinWidth(0);

        propertiesView = properties.build();
        ScrollPane propertiesScroll = new ScrollPane(propertiesView);
        propertiesScroll.setFitToWidth(true);
        propertiesScroll.getStyleClass().add("config-scroll");
        propertiesScroll.setPrefWidth(325);
        propertiesScroll.setMinWidth(285);

        BorderPane root = new BorderPane();
        root.setLeft(left);
        root.setCenter(center);
        root.setRight(propertiesScroll);
        BorderPane.setMargin(left, new Insets(0, 12, 0, 0));
        BorderPane.setMargin(propertiesScroll, new Insets(0, 0, 0, 12));

        reloadList();
        busy.addListener((obs, oldValue, newValue) -> updateActionState());
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                properties.setElement((ObjectNode) selected);
                onDesignChanged();
            }
        });
        return root;
    }

    private GridPane componentPalette() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int rowCount = (DesignElements.types().size() + 1) / 2;
        for (int row = 0; row < rowCount; row++) {
            javafx.scene.layout.RowConstraints constraints = new javafx.scene.layout.RowConstraints();
            constraints.setPercentHeight(100.0 / rowCount);
            grid.getRowConstraints().add(constraints);
        }
        int index = 0;
        for (String type : DesignElements.types()) {
            Button button = new Button(DesignElements.displayName(type));
            button.getStyleClass().add("component-item");
            button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            button.setOnAction(e -> addElement(type));
            int row = index / 2;
            int col = index % 2;
            grid.add(button, col, row);
            GridPane.setHgrow(button, Priority.ALWAYS);
            GridPane.setVgrow(button, Priority.ALWAYS);
            index++;
        }
        return grid;
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
        previewButton.setOnAction(e -> showOutputPreview());
        printButton.setOnAction(e -> printCurrentDesign());
        printButton.setTooltip(new javafx.scene.control.Tooltip(outputMode == OutputMode.LASER
                ? "使用示例变量创建对象，并以不出光测试模式执行" : "使用示例变量直接生成并发送当前画布的 TSPL"));
    }

    private void configureCanvas() {
        paper.getStyleClass().add("designer-paper");
        canvas.setOnMousePressed(e -> selectAt(e.getX(), e.getY()));
        canvas.setOnMouseDragged(e -> dragTo(e.getX(), e.getY()));
        canvas.setOnMouseReleased(e -> {
            onDesignChanged();
            properties.setElement((ObjectNode) selected);
        });
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
            selected = null;
            elementCounter = 0;
            properties.setDesign(design);
            properties.setElement(null);
            onDesignChanged();
            canvasScroll.setHvalue(0);
            canvasScroll.setVvalue(0);
            Platform.runLater(() -> {
                canvasScroll.setHvalue(0);
                canvasScroll.setVvalue(0);
            });
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
        elementCounter = 0;
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        paper.setVisible(false);
        paper.setManaged(false);
        emptyCanvas.setVisible(true);
        emptyCanvas.setManaged(true);
        properties.setDesign(null);
        properties.setElement(null);
        updateActionState();
    }

    private void updateActionState() {
        boolean noTemplate = currentName == null;
        newButton.setDisable(busy.get());
        deleteButton.setDisable(busy.get() || noTemplate || currentBuiltin);
        saveButton.setDisable(busy.get() || noTemplate);
        printButton.setDisable(busy.get() || noTemplate);
        previewButton.setDisable(busy.get() || noTemplate);
        templateList.setDisable(busy.get());
        canvas.setDisable(busy.get());
        if (propertiesView != null) {
            propertiesView.setDisable(busy.get());
        }
    }

    private void addElement(String type) {
        if (design == null) {
            banner.fail("请先新建或选择模板");
            return;
        }
        ArrayNode elements;
        if (design.path("elements") instanceof ArrayNode existing) {
            elements = existing;
        } else {
            elements = design.putArray("elements");
        }
        ObjectNode element = DesignElements.create(type, nextElementIndex(type));
        elements.add(element);
        selected = element;
        properties.setElement(element);
        onDesignChanged();
    }

    private int nextElementIndex(String type) {
        String prefix = type + "_";
        while (true) {
            elementCounter++;
            String id = prefix + elementCounter;
            boolean exists = false;
            for (JsonNode element : design.path("elements")) {
                if (id.equals(element.path("id").asText())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                return elementCounter;
            }
        }
    }

    private void deleteSelectedElement() {
        if (design == null || selected == null) {
            return;
        }
        ArrayNode elements = (ArrayNode) design.path("elements");
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == selected) {
                elements.remove(i);
                break;
            }
        }
        selected = null;
        properties.setElement(null);
        onDesignChanged();
    }

    private void onDesignChanged() {
        if (design == null) {
            return;
        }
        updateCanvasSize();
        renderPreview();
    }

    private void updateCanvasSize() {
        double width = design.path("widthMm").asDouble(60) * PX_PER_MM;
        double height = design.path("heightMm").asDouble(40) * PX_PER_MM;
        canvas.setWidth(width);
        canvas.setHeight(height);
        paper.setMinSize(width, height);
        paper.setPrefSize(width, height);
        paper.setMaxSize(width, height);
        paper.setVisible(true);
        paper.setManaged(true);
        emptyCanvas.setVisible(false);
        emptyCanvas.setManaged(false);
    }

    private Set<String> currentVariables() {
        return VariableSupport.extractRequired(design.toString());
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
            g.setImageSmoothing(true);
            g.drawImage(img, 0, 0, canvas.getWidth(), canvas.getHeight());
        } catch (Exception e) {
            banner.fail("预览渲染失败：" + e.getMessage());
            uiLog.error("预览渲染失败: " + e.getMessage());
        }
        if (selected != null) {
            DesignElements.Bounds b = DesignElements.bounds(selected);
            g.setStroke(javafx.scene.paint.Color.web("#007AFF"));
            g.setLineWidth(1.5);
            g.setLineDashes(4, 4);
            g.strokeRect(b.x() * PX_PER_MM, b.y() * PX_PER_MM,
                    b.width() * PX_PER_MM, b.height() * PX_PER_MM);
            g.setLineDashes();
        }
    }

    private Map<String, String> previewVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("diNo", "06912345678901");
        vars.put("sterileDate", "2026-01-01");
        vars.put("serialNo", "2601010101");
        vars.put("productionDate", "2026-01-01");
        vars.put("expiryDate", "2031-01-01");
        vars.put("lotNo", "LOT001");
        for (String variable : currentVariables()) {
            vars.putIfAbsent(variable, switch (variable) {
                case "type" -> "IOL-DEMO";
                case "power" -> "+20.0D";
                case "totalDia", "bodyDia", "optDia", "cutDia" -> "12.0";
                case "registerNo" -> "REG-DEMO";
                default -> "SAMPLE";
            });
        }
        if (variableService != null) {
            vars.putAll(variableService.snapshot());
        }
        return vars;
    }

    private void selectAt(double px, double py) {
        if (design == null) {
            return;
        }
        selected = null;
        ArrayNode elements = (ArrayNode) design.path("elements");
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode element = elements.get(i);
            DesignElements.Bounds b = DesignElements.bounds(element);
            double x = b.x() * PX_PER_MM;
            double y = b.y() * PX_PER_MM;
            double width = b.width() * PX_PER_MM;
            double height = b.height() * PX_PER_MM;
            if (px >= x && px <= x + width && py >= y && py <= y + height) {
                selected = element;
                dragOffsetX = px - b.x() * PX_PER_MM;
                dragOffsetY = py - b.y() * PX_PER_MM;
                break;
            }
        }
        properties.setElement((ObjectNode) selected);
        renderPreview();
    }

    private void dragTo(double px, double py) {
        if (selected == null || design == null) {
            return;
        }
        DesignElements.Bounds b = DesignElements.bounds(selected);
        double maxX = Math.max(0, design.path("widthMm").asDouble() - b.width());
        double maxY = Math.max(0, design.path("heightMm").asDouble() - b.height());
        double x = snapAndClamp((px - dragOffsetX) / PX_PER_MM, maxX);
        double y = snapAndClamp((py - dragOffsetY) / PX_PER_MM, maxY);
        ((ObjectNode) selected).put("x", x);
        ((ObjectNode) selected).put("y", y);
        renderPreview();
    }

    private static double snapAndClamp(double value, double max) {
        double snapped = Math.round(value * 2) / 2.0;
        return Math.max(0, Math.min(max, snapped));
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
                String initialDesign = outputMode == OutputMode.LASER
                        ? "{\"version\":1,\"widthMm\":100,\"heightMm\":100,\"pen\":0,\"elements\":[]}"
                        : "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":[]}";
                repo.insert(name, initialDesign, false);
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

    private void printCurrentDesign() {
        if (design == null) {
            banner.fail("请先打开模板");
            return;
        }
        String designSnapshot = design.toString();
        String nameSnapshot = currentName;
        Map<String, String> variables = previewVariables();
        if (outputMode == OutputMode.LASER) {
            startTask("正在创建测试打标对象…", () -> {
                laserService.markDesign(designSnapshot, variables, true);
                banner.success("测试打标完成（未出光）");
                uiLog.info("测试打标完成: " + nameSnapshot);
            });
            return;
        }
        startTask("正在生成并发送 TSPL…", () -> {
            printService.printDesign(designSnapshot, variables);
            banner.success("测试打印已下发");
            uiLog.info("测试打印已下发: " + nameSnapshot);
        });
    }

    private void showOutputPreview() {
        if (design == null) {
            banner.fail("请先打开模板");
            return;
        }
        String designSnapshot = design.toString();
        Map<String, String> variables = previewVariables();
        if (outputMode == OutputMode.LASER) {
            startTask("正在生成打标对象…", () -> {
                String preview = laserService.previewDesign(designSnapshot, variables);
                Platform.runLater(() -> {
                    showLaserDialog(preview);
                    banner.success("打标对象已生成");
                });
            });
            return;
        }
        startTask("正在生成 TSPL 指令…", () -> {
            String tspl = printService.previewTspl(designSnapshot, variables);
            Platform.runLater(() -> {
                showTsplDialog(tspl);
                banner.success("TSPL 指令已生成");
            });
        });
    }

    private void showLaserDialog(String preview) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("打标对象预览");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        if (canvas.getScene() != null) {
            dialog.initOwner(canvas.getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(canvas.getScene().getStylesheets());
        }
        Label summary = new Label("以下对象将由 SDK 动态创建，不读取或写入 .ezd 文件。预览不代表设备已连接。");
        summary.getStyleClass().add("hint");
        summary.setWrapText(true);
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(preview);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("tspl-preview");
        area.setPrefSize(900, 600);
        VBox content = new VBox(10, summary, area);
        content.setPrefSize(920, 650);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void showTsplDialog(String tspl) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("TSPL 指令预览");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        if (canvas.getScene() != null) {
            dialog.initOwner(canvas.getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(canvas.getScene().getStylesheets());
        }
        Label summary = new Label("模板 DPI：" + design.path("dpi").asInt(600)
                + " · UTF-8 字节数：" + tspl.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "\n实际发送前先查询 <ESC>!?（HEX：1B 21 3F）；下方为完整 TSPL 内容。");
        summary.getStyleClass().add("hint");
        summary.setWrapText(true);
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(tspl);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("tspl-preview");
        area.setPrefSize(900, 600);
        VBox content = new VBox(10, summary, area);
        content.setPrefSize(920, 650);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
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
