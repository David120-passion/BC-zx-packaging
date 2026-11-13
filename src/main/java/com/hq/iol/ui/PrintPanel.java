package com.hq.iol.ui;

import com.hq.iol.label.LabelTemplate;
import com.hq.iol.service.PrintService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 打印测试面板：模板与变量在左，真实打印操作在右。 */
@Component
public class PrintPanel {

    private final PrintService printService;
    private final UiLogService uiLog;
    private final ComboBox<LabelTemplate> templateBox = new ComboBox<>();
    private final Map<String, TextField> varFields = new LinkedHashMap<>();
    private final GridPane varGrid = new GridPane();
    private final Label emptyVars = new Label();
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final Button printButton = new Button("下发打印");
    private final Button reloadButton = new Button("刷新模板");
    private final Button statusButton = new Button("查询打印机状态");

    public PrintPanel(PrintService printService, UiLogService uiLog) {
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        templateBox.setCellFactory(lv -> cell());
        templateBox.setButtonCell(cell());
        templateBox.setMaxWidth(Double.MAX_VALUE);
        templateBox.setPromptText("请选择标签模板");
        templateBox.valueProperty().addListener((obs, oldValue, newValue) -> rebuildVars());
        templateBox.disableProperty().bind(busy);

        reloadButton.setOnAction(e -> loadTemplates());
        reloadButton.disableProperty().bind(busy);
        statusButton.setOnAction(e -> queryStatus());
        statusButton.disableProperty().bind(busy);
        statusButton.setMaxWidth(Double.MAX_VALUE);
        printButton.getStyleClass().add("btn-primary");
        printButton.setOnAction(e -> print());
        printButton.disableProperty().bind(busy.or(templateBox.valueProperty().isNull()));
        printButton.setMaxWidth(Double.MAX_VALUE);

        VBox templateCard = card("标签模板", "选择需要验证的设计稿");
        templateCard.getChildren().addAll(templateBox, reloadButton);
        reloadButton.setAlignment(Pos.CENTER_LEFT);

        VBox variablesCard = card("模板变量", "按当前模板生成输入项");
        varGrid.setHgap(12);
        varGrid.setVgap(10);
        emptyVars.getStyleClass().add("empty-state");
        emptyVars.setWrapText(true);
        ScrollPane variableScroll = new ScrollPane(new VBox(12, varGrid, emptyVars));
        variableScroll.setFitToWidth(true);
        variableScroll.getStyleClass().add("config-scroll");
        variableScroll.setMinHeight(160);
        VBox.setVgrow(variableScroll, Priority.ALWAYS);
        variablesCard.getChildren().add(variableScroll);
        VBox.setVgrow(variablesCard, Priority.ALWAYS);

        VBox left = new VBox(14, templateCard, variablesCard);
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);

        VBox actions = card("打印操作", "先验证状态，再下发一张标签");
        actions.setPrefWidth(280);
        actions.setMinWidth(240);
        actions.getChildren().addAll(
                banner.node(), statusButton, printButton,
                hint("打印前会自动查询打印机状态；缺纸、卡纸或暂停时不会下发指令。"),
                hint("“已下发”仅表示指令已写入打印机，不代表实物已完成打印。"));

        HBox root = new HBox(14, left, actions);
        root.getStyleClass().add("page-content");
        loadTemplates();
        return root;
    }

    private void loadTemplates() {
        if (busy.get()) {
            return;
        }
        String selected = templateBox.getValue() == null ? null : templateBox.getValue().name();
        startTask("正在加载模板…", () -> {
            List<LabelTemplate> templates = printService.listTemplates();
            Platform.runLater(() -> {
                templateBox.getItems().setAll(templates);
                templates.stream().filter(t -> t.name().equals(selected)).findFirst()
                        .ifPresentOrElse(templateBox::setValue, () -> {
                            if (!templates.isEmpty()) {
                                templateBox.getSelectionModel().selectFirst();
                            } else {
                                templateBox.getSelectionModel().clearSelection();
                                rebuildVars();
                            }
                        });
                banner.success("已加载 " + templates.size() + " 个模板");
            });
        });
    }

    private void rebuildVars() {
        varFields.clear();
        varGrid.getChildren().clear();
        LabelTemplate template = templateBox.getValue();
        if (template == null) {
            showEmpty("请选择标签模板");
            return;
        }
        Set<String> vars = printService.templateVariables(template.name());
        if (vars.isEmpty()) {
            showEmpty("此模板无需录入变量");
            return;
        }
        emptyVars.setVisible(false);
        emptyVars.setManaged(false);
        int row = 0;
        for (String name : vars) {
            Label label = new Label(name);
            label.getStyleClass().add("field-label");
            label.setMinWidth(105);
            TextField field = new TextField();
            field.setPromptText("请输入 " + name);
            field.disableProperty().bind(busy);
            varFields.put(name, field);
            varGrid.addRow(row++, label, field);
            GridPane.setHgrow(field, Priority.ALWAYS);
        }
    }

    private void showEmpty(String text) {
        emptyVars.setText(text);
        emptyVars.setVisible(true);
        emptyVars.setManaged(true);
    }

    private void queryStatus() {
        startTask("正在查询打印机状态…", () -> {
            String status = printService.queryPrinterStatus();
            banner.success("打印机状态：" + status);
            uiLog.info("打印机状态: " + status);
        });
    }

    private void print() {
        LabelTemplate template = templateBox.getValue();
        if (template == null) {
            banner.fail("请先选择模板");
            return;
        }
        String templateName = template.name();
        Map<String, String> values = new LinkedHashMap<>();
        varFields.forEach((key, field) -> values.put(key, field.getText()));
        Map<String, String> snapshot = Map.copyOf(values);
        startTask("正在打印…", () -> {
            printService.print(templateName, snapshot);
            banner.success("打印任务已下发");
            uiLog.info("已下发打印: " + templateName);
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
        }, "print-ui-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private static VBox card(String title, String subtitle) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = hint(subtitle);
        VBox card = new VBox(10, titleLabel, subtitleLabel);
        card.getStyleClass().add("card");
        card.setMinWidth(0);
        return card;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        return label;
    }

    private static ListCell<LabelTemplate> cell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LabelTemplate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
