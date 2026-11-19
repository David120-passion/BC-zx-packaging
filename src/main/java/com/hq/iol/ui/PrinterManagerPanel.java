package com.hq.iol.ui;

import com.hq.iol.device.printer.PrinterProfile;
import com.hq.iol.service.PrinterRegistry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 多打印机连接管理：名称、地址、端口和活动打印机。 */
public class PrinterManagerPanel {

    private final PrinterRegistry registry;
    private final UiLogService uiLog;
    private final ListView<PrinterProfile> list = new ListView<>();
    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("9100");
    private final ResultBanner banner = new ResultBanner();
    private final Button updateButton = new Button("保存修改");
    private final Button deleteButton = new Button("删除");
    private final Button activeButton = new Button("设为当前打印机");
    private List<PrinterProfile> profiles = new ArrayList<>();

    public PrinterManagerPanel(PrinterRegistry registry, UiLogService uiLog) {
        this.registry = registry;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Label title = new Label("打印机连接");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("支持多台 TSC 打印机；DPI 在标签模板中设置");
        subtitle.getStyleClass().add("hint");

        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PrinterProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                boolean active = item.id().equals(registry.active().id());
                setText(item.name() + " · " + item.host() + ":" + item.port()
                        + (active ? "（当前）" : ""));
            }
        });
        list.setPrefWidth(330);
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, profile) -> {
            populate(profile);
            updateButtons();
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        nameField.setPromptText("例如：A 线打印机");
        hostField.setPromptText("例如：127.0.0.1");
        form.addRow(0, fieldLabel("名称"), nameField);
        form.addRow(1, fieldLabel("主机"), hostField);
        form.addRow(2, fieldLabel("端口"), portField);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(hostField, Priority.ALWAYS);
        GridPane.setHgrow(portField, Priority.ALWAYS);
        HBox.setHgrow(form, Priority.ALWAYS);

        Button newButton = new Button("清空以新增");
        newButton.setOnAction(e -> {
            list.getSelectionModel().clearSelection();
            nameField.clear();
            hostField.setText("127.0.0.1");
            portField.setText("9100");
            updateButtons();
        });
        Button addButton = new Button("添加打印机");
        addButton.getStyleClass().add("btn-primary");
        addButton.setOnAction(e -> addPrinter());
        updateButton.setOnAction(e -> updatePrinter());
        deleteButton.getStyleClass().add("btn-danger");
        deleteButton.setOnAction(e -> deletePrinter());
        activeButton.setOnAction(e -> setActive());
        HBox actions = new HBox(8, newButton, addButton, updateButton, deleteButton, activeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, title, subtitle,
                new HBox(14, list, form), actions, banner.node());
        root.getStyleClass().add("card");
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                reload();
            }
        });
        reload();
        return root;
    }

    private void populate(PrinterProfile profile) {
        if (profile == null) {
            return;
        }
        nameField.setText(profile.name());
        hostField.setText(profile.host());
        portField.setText(String.valueOf(profile.port()));
    }

    private void updateButtons() {
        PrinterProfile selected = list.getSelectionModel().getSelectedItem();
        updateButton.setDisable(selected == null);
        deleteButton.setDisable(selected == null || profiles.size() <= 1);
        activeButton.setDisable(selected == null || selected.id().equals(registry.active().id()));
    }

    private void addPrinter() {
        try {
            PrinterProfile profile = readForm(UUID.randomUUID().toString());
            List<PrinterProfile> updated = new ArrayList<>(profiles);
            updated.add(profile);
            registry.saveAll(updated, profile.id());
            reload();
            list.getSelectionModel().select(profile);
            banner.success("已添加并设为当前打印机：" + profile.name());
            uiLog.info("已添加打印机: " + profile.name());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void updatePrinter() {
        PrinterProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            banner.fail("请先选择要修改的打印机");
            return;
        }
        try {
            PrinterProfile updatedProfile = readForm(selected.id());
            List<PrinterProfile> updated = new ArrayList<>(profiles);
            updated.replaceAll(profile -> profile.id().equals(selected.id()) ? updatedProfile : profile);
            registry.saveAll(updated, registry.active().id());
            reload();
            list.getSelectionModel().select(updatedProfile);
            banner.success("打印机配置已保存");
            uiLog.info("打印机配置已保存: " + updatedProfile.name());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void deletePrinter() {
        PrinterProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null || profiles.size() <= 1) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除打印机「" + selected.name() + "」吗？", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        List<PrinterProfile> updated = new ArrayList<>(profiles);
        updated.removeIf(profile -> profile.id().equals(selected.id()));
        String activeId = selected.id().equals(registry.active().id())
                ? updated.get(0).id() : registry.active().id();
        registry.saveAll(updated, activeId);
        reload();
        banner.success("已删除打印机：" + selected.name());
        uiLog.info("已删除打印机: " + selected.name());
    }

    private void setActive() {
        PrinterProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        registry.setActive(selected.id());
        reload();
        list.getSelectionModel().select(selected);
        banner.success("当前打印机：" + selected.name());
        uiLog.info("当前打印机: " + selected.name());
    }

    private PrinterProfile readForm(String id) {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口须为 1–65535 的整数");
        }
        return new PrinterProfile(id, nameField.getText(), hostField.getText(), port);
    }

    private void reload() {
        profiles = new ArrayList<>(registry.list());
        list.getItems().setAll(profiles);
        list.refresh();
        updateButtons();
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(45);
        return label;
    }
}
