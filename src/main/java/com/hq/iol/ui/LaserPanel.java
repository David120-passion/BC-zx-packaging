package com.hq.iol.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.label.LabelTemplate;
import com.hq.iol.label.TemplateRepository;
import com.hq.iol.label.VariableSupport;
import com.hq.iol.service.LaserService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LaserPanel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LaserService laserService;
    private final ConfigService config;
    private final UiLogService uiLog;
    private final TemplateRepository templates;
    private final ComboBox<LabelTemplate> templateBox = new ComboBox<>();
    private final CheckBox testMode = new CheckBox("测试模式（不出光）");
    private final GridPane variableGrid = new GridPane();
    private final Map<String, TextField> variableFields = new LinkedHashMap<>();
    private final Label emptyVariables = new Label();
    private final ResultBanner banner = new ResultBanner();
    private final Label templateSummary = new Label("请选择打标模板");
    private final Label sdkPathValue = new Label();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty loaded = new SimpleBooleanProperty(false);
    private final BooleanProperty marking = new SimpleBooleanProperty(false);
    private final BooleanProperty redLighting = new SimpleBooleanProperty(false);

    public LaserPanel(LaserService laserService, ConfigService config, UiLogService uiLog,
                      @Qualifier("laserTemplateRepository") TemplateRepository templates) {
        this.laserService = laserService;
        this.config = config;
        this.uiLog = uiLog;
        this.templates = templates;
    }

    public Parent build() {
        testMode.setSelected(true);
        testMode.disableProperty().bind(busy);
        templateBox.setCellFactory(view -> templateCell());
        templateBox.setButtonCell(templateCell());
        templateBox.setMaxWidth(Double.MAX_VALUE);
        templateBox.setPromptText("请选择打标模板");
        templateBox.disableProperty().bind(busy);
        templateBox.valueProperty().addListener((obs, oldValue, selected) -> showTemplate(selected));

        Button refresh = new Button("刷新模板");
        refresh.disableProperty().bind(busy);
        refresh.setOnAction(event -> loadTemplates());
        HBox selection = new HBox(8, templateBox, refresh);
        HBox.setHgrow(templateBox, Priority.ALWAYS);

        variableGrid.setHgap(12);
        variableGrid.setVgap(10);
        emptyVariables.getStyleClass().add("hint");
        ScrollPane variableScroll = new ScrollPane(new VBox(12, variableGrid, emptyVariables));
        variableScroll.setFitToWidth(true);
        variableScroll.getStyleClass().add("config-scroll");
        variableScroll.setMinHeight(180);
        VBox.setVgrow(variableScroll, Priority.ALWAYS);
        templateSummary.getStyleClass().add("hint");
        templateSummary.setWrapText(true);
        VBox templateCard = card("打标模板", "在“激光模板”中维护组件；此处只需录入模板变量");
        templateCard.getChildren().addAll(selection, templateSummary, variableScroll);
        HBox.setHgrow(templateCard, Priority.ALWAYS);
        VBox.setVgrow(templateCard, Priority.ALWAYS);

        Button mark = new Button("开始打标");
        mark.getStyleClass().add("btn-primary");
        mark.setMaxWidth(Double.MAX_VALUE);
        mark.disableProperty().bind(busy.or(loaded.not()));
        mark.setOnAction(event -> mark());
        Button redLight = new Button("红光指示");
        redLight.setMaxWidth(Double.MAX_VALUE);
        redLight.disableProperty().bind(busy.and(redLighting.not()).or(loaded.not()));
        redLighting.addListener((obs, oldValue, active) ->
                redLight.setText(active ? "关闭红光" : "红光指示"));
        redLight.setOnAction(event -> {
            if (redLighting.get()) {
                stopMarking();
            } else {
                redLight();
            }
        });
        Button stop = new Button("停止打标/红光");
        stop.getStyleClass().add("btn-danger");
        stop.setMaxWidth(Double.MAX_VALUE);
        stop.disableProperty().bind(marking.not());
        stop.setOnAction(event -> stopMarking());

        VBox actions = card("打标操作", "核对模板与变量后再开始打标");
        actions.setPrefWidth(300);
        actions.setMinWidth(260);
        Label safety = new Label("每次启动默认使用测试模式。真实打标需关闭测试模式并再次确认，请先完成现场安全检查。");
        safety.getStyleClass().add("hint");
        safety.setWrapText(true);
        sdkPathValue.getStyleClass().add("hint");
        sdkPathValue.setWrapText(true);
        actions.getChildren().addAll(testMode, safety, banner.node(), redLight, mark, stop,
                new Region(), new Label("SDK 目录"), sdkPathValue);

        HBox root = new HBox(14, templateCard, actions);
        root.getStyleClass().add("page-content");
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                refreshSdkPath();
                if (!loaded.get()) {
                    loadTemplates();
                }
            }
        });
        refreshSdkPath();
        loadTemplates();
        return root;
    }

    private void loadTemplates() {
        String selectedName = templateBox.getValue() == null
                ? config.get(ConfigKeys.LASER_TEMPLATE) : templateBox.getValue().name();
        try {
            List<LabelTemplate> items = templates.findAll();
            templateBox.getItems().setAll(items);
            items.stream().filter(item -> item.name().equals(selectedName)).findFirst()
                    .ifPresentOrElse(templateBox::setValue, () -> {
                        if (!items.isEmpty()) {
                            templateBox.getSelectionModel().selectFirst();
                        } else {
                            showTemplate(null);
                        }
                    });
        } catch (Exception e) {
            loaded.set(false);
            banner.fail("打标模板加载失败：" + e.getMessage());
        }
    }

    private void showTemplate(LabelTemplate template) {
        variableFields.clear();
        variableGrid.getChildren().clear();
        if (template == null) {
            loaded.set(false);
            templateSummary.setText("暂无打标模板，请先在“激光模板”中新建");
            return;
        }
        try {
            JsonNode design = MAPPER.readTree(template.designJson());
            loaded.set(true);
            templateSummary.setText(template.name() + " · " + design.path("widthMm").asDouble()
                    + " × " + design.path("heightMm").asDouble() + " mm · "
                    + design.path("elements").size() + " 个组件");
            Set<String> variables = VariableSupport.extractRequired(template.designJson());
            int row = 0;
            for (String name : variables) {
                Label caption = new Label(name);
                caption.getStyleClass().add("field-label");
                TextField field = new TextField();
                field.setPromptText("请输入 " + name);
                field.disableProperty().bind(busy);
                variableFields.put(name, field);
                variableGrid.addRow(row++, caption, field);
                GridPane.setHgrow(field, Priority.ALWAYS);
            }
            emptyVariables.setText(variables.isEmpty() ? "此模板无需录入变量" : "");
            config.saveAll(Map.of(ConfigKeys.LASER_TEMPLATE, template.name()));
        } catch (Exception e) {
            loaded.set(false);
            banner.fail("打标模板无法读取：" + e.getMessage());
        }
    }

    private void mark() {
        LabelTemplate template = templateBox.getValue();
        if (template == null || !loaded.get()) {
            banner.fail("请先选择打标模板");
            return;
        }
        if (!testMode.isSelected() && !confirmRealMark()) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        variableFields.forEach((name, field) -> values.put(name, field.getText()));
        Map<String, String> snapshot = Map.copyOf(values);
        boolean simulation = testMode.isSelected();
        startMark(simulation ? "正在执行测试打标…" : "正在真实打标…", () -> {
            laserService.markDesign(template.designJson(), snapshot, simulation);
            banner.success(simulation ? "测试打标完成（未出光）" : "真实打标完成");
            uiLog.info(simulation ? "测试打标完成（未出光）" : "真实打标完成");
        });
    }

    private void redLight() {
        LabelTemplate template = templateBox.getValue();
        if (template == null || !loaded.get()) {
            banner.fail("请先选择打标模板");
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        variableFields.forEach((name, field) -> values.put(name, field.getText()));
        Map<String, String> snapshot = Map.copyOf(values);
        redLighting.set(true);
        startMark("正在红光指示…", () -> {
            try {
                laserService.redLightDesign(template.designJson(), snapshot);
                banner.success("红光指示已结束");
                uiLog.info("红光指示已结束");
            } finally {
                Platform.runLater(() -> redLighting.set(false));
            }
        });
    }

    private boolean confirmRealMark() {
        ButtonType confirm = new ButtonType("确认实标", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "即将实际出光。请确认激光设备、防护设施和加工区域均已准备就绪。",
                ButtonType.CANCEL, confirm);
        alert.setTitle("确认真实打标");
        alert.setHeaderText("请确认现场安全");
        return alert.showAndWait().orElse(ButtonType.CANCEL) == confirm;
    }

    private void stopMarking() {
        banner.running("正在请求停止…");
        Thread thread = new Thread(() -> {
            try {
                laserService.stopMarking();
                Platform.runLater(() -> marking.set(false));
                banner.success("已发送停止请求");
                uiLog.info("已请求停止激光打标或红光指示");
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                banner.fail(message);
                uiLog.error(message);
            }
        }, "laser-stop-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshSdkPath() {
        try {
            sdkPathValue.setText(laserService.sdkDirectory().toString());
        } catch (Exception e) {
            sdkPathValue.setText("SDK 目录配置无效，请在系统配置中重新选择");
        }
    }

    private void startMark(String status, IoAction action) {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        marking.set(true);
        banner.running(status);
        Thread thread = new Thread(() -> {
            java.util.concurrent.atomic.AtomicBoolean mayContinue = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                action.run();
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                mayContinue.set(message.contains("超时"));
                banner.fail(message);
                uiLog.error(message);
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    marking.set(mayContinue.get());
                });
            }
        }, "laser-mark-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private static ListCell<LabelTemplate> templateCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LabelTemplate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private static VBox card(String title, String subtitle) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label hint = new Label(subtitle);
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        VBox card = new VBox(10, titleLabel, hint);
        card.getStyleClass().add("card");
        card.setMinWidth(0);
        return card;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
