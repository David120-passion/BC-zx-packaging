package com.hq.iol.ui;

import com.hq.iol.device.scale.DiscoveredPort;
import com.hq.iol.device.scale.ScaleProfile;
import com.hq.iol.device.scale.SerialPortInspector;
import com.hq.iol.service.ScaleRegistry;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
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

/** 串口扫描、命名、地址、波特率和最多三台电子秤管理。 */
public class ScaleManagerPanel {

    private final ScaleRegistry registry;
    private final UiLogService uiLog;
    private final ListView<ScaleProfile> list = new ListView<>();
    private final ComboBox<DiscoveredPort> discoveredBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final ResultBanner banner = new ResultBanner();
    private final Button renameButton = new Button("重命名");
    private final Button deleteButton = new Button("删除");
    private final Button activeButton = new Button("设为当前秤");
    private final TextField addressField = new TextField("1");
    private final ComboBox<Integer> baudBox = new ComboBox<>();
    private List<ScaleProfile> profiles = new ArrayList<>();

    public ScaleManagerPanel(ScaleRegistry registry, UiLogService uiLog) {
        this.registry = registry;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Label title = new Label("电子秤连接");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("KXQY 系列 · ASCII · 8N1；每台秤的地址需与秤上 ID 一致");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);

        Button scanButton = new Button("扫描串口");
        scanButton.setOnAction(e -> scanPorts());
        discoveredBox.setPromptText("先扫描串口");
        discoveredBox.setMaxWidth(Double.MAX_VALUE);
        discoveredBox.setCellFactory(lv -> discoveredCell());
        discoveredBox.setButtonCell(discoveredCell());
        nameField.setPromptText("设备名称，例如 A秤");
        HBox.setHgrow(discoveredBox, Priority.ALWAYS);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        Button addButton = new Button("添加并命名");
        addButton.getStyleClass().add("btn-primary");
        addButton.setOnAction(e -> addScale());
        HBox discovery = new HBox(8, scanButton, discoveredBox, nameField, addButton);
        discovery.setAlignment(Pos.CENTER_LEFT);

        addressField.setPromptText("1–255");
        addressField.setPrefWidth(100);
        baudBox.getItems().setAll(1200, 2400, 4800, 9600);
        baudBox.setValue(9600);
        HBox connection = new HBox(8, new Label("设备地址"), addressField,
                new Label("波特率"), baudBox);
        connection.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ScaleProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.name() + " · " + item.port() + " · " + item.baud()
                        + (item.id().equals(registry.active().id()) ? "（当前）" : ""));
            }
        });
        list.setPrefHeight(145);
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, profile) -> {
            if (profile != null) {
                nameField.setText(profile.name());
                addressField.setText(profile.address().toString());
                baudBox.setValue(profile.baud());
            }
            updateButtons();
        });

        renameButton.setText("保存名称与参数");
        renameButton.setOnAction(e -> renameScale());
        deleteButton.getStyleClass().add("btn-danger");
        deleteButton.setOnAction(e -> deleteScale());
        activeButton.setOnAction(e -> setActive());
        HBox actions = new HBox(8, renameButton, deleteButton, activeButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, title, subtitle, discovery, connection, list, actions, banner.node());
        root.getStyleClass().add("card");
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                reload();
            }
        });
        reload();
        return root;
    }

    private void scanPorts() {
        List<DiscoveredPort> discovered = SerialPortInspector.discover();
        discoveredBox.getItems().setAll(discovered);
        if (discovered.isEmpty()) {
            banner.fail("未发现串口设备，请检查连接与驱动");
            return;
        }
        discoveredBox.getSelectionModel().selectFirst();
        banner.success("发现 " + discovered.size() + " 个串口，请选择后命名");
    }

    private void addScale() {
        DiscoveredPort discovered = discoveredBox.getValue();
        if (discovered == null) {
            banner.fail("请先扫描并选择一个串口");
            return;
        }
        if (profiles.stream().anyMatch(profile -> profile.port().equals(discovered.portName()))) {
            banner.fail("串口 " + discovered.portName() + " 已配置，不能重复添加");
            return;
        }
        try {
            String name = nameField.getText().isBlank()
                    ? "电子秤 " + (profiles.size() + 1) : nameField.getText().trim();
            String description = discovered.description() == null ? "" : discovered.description();
            if (discovered.serialNumber() != null && !discovered.serialNumber().isBlank()) {
                description += (description.isBlank() ? "" : " · ") + "SN=" + discovered.serialNumber();
            }
            ScaleProfile profile = new ScaleProfile(UUID.randomUUID().toString(),
                    name, discovered.portName(), description, readAddress(), baudBox.getValue());
            List<ScaleProfile> updated = new ArrayList<>(profiles);
            updated.add(profile);
            registry.saveAll(updated, profile.id());
            reload();
            list.getSelectionModel().select(profile);
            banner.success("已添加并设为当前秤：" + name);
            uiLog.info("已添加电子秤: " + name + " · " + discovered.displayName());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private void renameScale() {
        ScaleProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            banner.fail("请先选择要保存的电子秤");
            return;
        }
        try {
            ScaleProfile renamed = new ScaleProfile(selected.id(), nameField.getText(),
                    selected.port(), selected.description(), readAddress(), baudBox.getValue());
            List<ScaleProfile> updated = new ArrayList<>(profiles);
            updated.replaceAll(profile -> profile.id().equals(selected.id()) ? renamed : profile);
            registry.saveAll(updated, registry.active().id());
            reload();
            list.getSelectionModel().select(renamed);
            banner.success("已保存：" + renamed.name());
            uiLog.info("电子秤参数已保存: " + renamed.name() + " · 地址=" + renamed.address()
                    + " · 波特率=" + renamed.baud());
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }

    private int readAddress() {
        try {
            return Integer.parseInt(addressField.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("设备地址必须为 0–255 的整数");
        }
    }

    private void deleteScale() {
        ScaleProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null || profiles.size() <= 1) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除电子秤「" + selected.name() + "」吗？", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        List<ScaleProfile> updated = new ArrayList<>(profiles);
        updated.removeIf(profile -> profile.id().equals(selected.id()));
        String activeId = selected.id().equals(registry.active().id())
                ? updated.get(0).id() : registry.active().id();
        registry.saveAll(updated, activeId);
        reload();
        banner.success("已删除电子秤：" + selected.name());
        uiLog.info("已删除电子秤: " + selected.name());
    }

    private void setActive() {
        ScaleProfile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        registry.setActive(selected.id());
        reload();
        list.getSelectionModel().select(selected);
        banner.success("当前电子秤：" + selected.name());
        uiLog.info("当前电子秤: " + selected.name());
    }

    private void updateButtons() {
        ScaleProfile selected = list.getSelectionModel().getSelectedItem();
        renameButton.setDisable(selected == null);
        deleteButton.setDisable(selected == null || profiles.size() <= 1);
        activeButton.setDisable(selected == null || selected.id().equals(registry.active().id()));
    }

    private void reload() {
        profiles = new ArrayList<>(registry.list());
        list.getItems().setAll(profiles);
        list.refresh();
        updateButtons();
    }

    private static ListCell<DiscoveredPort> discoveredCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DiscoveredPort item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }
}
