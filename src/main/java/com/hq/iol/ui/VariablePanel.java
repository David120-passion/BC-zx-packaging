package com.hq.iol.ui;

import com.hq.iol.label.TemplateVariable;
import com.hq.iol.service.VariableService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

@Component
public class VariablePanel {

    private final VariableService variableService;
    private final UiLogService uiLog;
    private final ListView<TemplateVariable> list = new ListView<>();
    private final TextField keyField = new TextField();
    private final TextField remarkField = new TextField();
    private final TextField runtimeValueField = new TextField();
    private final ResultBanner banner = new ResultBanner();
    private final Button updateButton = new Button("保存备注");
    private final Button deleteButton = new Button("删除");

    public VariablePanel(VariableService variableService, UiLogService uiLog) {
        this.variableService = variableService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Label title = new Label("变量定义");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("变量名与备注保存到本地；运行时值由后续业务代码赋值，重启后清空");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(TemplateVariable variable, boolean empty) {
                super.updateItem(variable, empty);
                setText(empty || variable == null ? null : variable.key()
                        + (variable.remark().isBlank() ? "" : " · " + variable.remark()));
            }
        });
        list.setPrefWidth(360);
        list.setMaxHeight(Double.MAX_VALUE);
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, variable) -> {
            populate(variable);
            updateButtons();
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        keyField.setPromptText("例如：serialNo");
        remarkField.setPromptText("例如：产品序列号");
        runtimeValueField.setEditable(false);
        runtimeValueField.setPromptText("尚未赋值");
        form.addRow(0, fieldLabel("变量名"), keyField);
        form.addRow(1, fieldLabel("备注"), remarkField);
        form.addRow(2, fieldLabel("运行时值"), runtimeValueField);
        GridPane.setHgrow(keyField, Priority.ALWAYS);
        GridPane.setHgrow(remarkField, Priority.ALWAYS);
        GridPane.setHgrow(runtimeValueField, Priority.ALWAYS);
        HBox.setHgrow(form, Priority.ALWAYS);
        Label hint = new Label("后续代码注入 VariableService，调用 setValue(key, value) 或 setValues(map) 即可赋值。"
                + "变量名创建后不可直接修改，避免已有模板引用失效。");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);
        VBox fields = new VBox(12, form, hint);
        HBox.setHgrow(fields, Priority.ALWAYS);
        HBox content = new HBox(16, list, fields);
        content.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button newButton = new Button("清空以新增");
        newButton.setOnAction(event -> clearForm());
        Button addButton = new Button("添加变量");
        addButton.getStyleClass().add("btn-primary");
        addButton.setOnAction(event -> addVariable());
        updateButton.setOnAction(event -> updateRemark());
        deleteButton.getStyleClass().add("btn-danger");
        deleteButton.setOnAction(event -> deleteVariable());
        Button refreshButton = new Button("刷新");
        refreshButton.setOnAction(event -> {
            reload();
            banner.success("变量列表已刷新");
        });
        HBox actions = new HBox(8, newButton, addButton, updateButton, deleteButton, refreshButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, title, subtitle, content, actions, banner.node());
        root.getStyleClass().add("card");
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                reload();
            }
        });
        reload();
        return root;
    }

    private void addVariable() {
        try {
            TemplateVariable variable = variableService.create(keyField.getText(), remarkField.getText());
            reload();
            select(variable.key());
            banner.success("已添加变量：" + variable.key());
            uiLog.info("已添加变量: " + variable.key());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void updateRemark() {
        TemplateVariable selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            banner.fail("请先选择要修改备注的变量");
            return;
        }
        try {
            variableService.updateRemark(selected.key(), remarkField.getText());
            reload();
            select(selected.key());
            banner.success("变量备注已保存：" + selected.key());
            uiLog.info("变量备注已保存: " + selected.key());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void deleteVariable() {
        TemplateVariable selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除变量「" + selected.key() + "」吗？\n"
                        + "模板中的已有绑定不会自动修改，请同步检查相关标签模板。",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("删除变量");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            variableService.delete(selected.key());
            clearForm();
            reload();
            banner.success("已删除变量：" + selected.key());
            uiLog.info("已删除变量: " + selected.key());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void populate(TemplateVariable variable) {
        if (variable == null) {
            keyField.clear();
            remarkField.clear();
            runtimeValueField.clear();
            keyField.setDisable(false);
            return;
        }
        keyField.setText(variable.key());
        keyField.setDisable(true);
        remarkField.setText(variable.remark());
        runtimeValueField.setText(variableService.get(variable.key()).orElse(""));
    }

    private void clearForm() {
        list.getSelectionModel().clearSelection();
        populate(null);
        updateButtons();
    }

    private void updateButtons() {
        boolean noSelection = list.getSelectionModel().getSelectedItem() == null;
        updateButton.setDisable(noSelection);
        deleteButton.setDisable(noSelection);
    }

    private void reload() {
        String key = list.getSelectionModel().getSelectedItem() == null
                ? keyField.getText() : list.getSelectionModel().getSelectedItem().key();
        list.getItems().setAll(variableService.list());
        if (!select(key)) {
            clearForm();
        }
        updateButtons();
    }

    private boolean select(String key) {
        for (TemplateVariable variable : list.getItems()) {
            if (variable.key().equals(key)) {
                list.getSelectionModel().select(variable);
                return true;
            }
        }
        return false;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(70);
        return label;
    }
}
