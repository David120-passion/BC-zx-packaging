package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * 操作日志抽屉：标题 + 日志列表 + 收起/清空按钮。
 * 数据完全来自 {@link UiLogService#entries()}（上限 500 条），组件不写日志。
 * <p>
 * 长日志：单元格不换行省略，横向滚动查看（单元格宽度按最长一条日志计算），
 * 悬停 Tooltip 显示完整内容；空列表时不自动滚动。
 */
public class LogDrawer {

    private static final double CELL_H_PADDING = 16;

    private final UiLogService logs;

    public LogDrawer(UiLogService logs) {
        this.logs = logs;
    }

    public Parent build() {
        Label title = new Label("操作日志（最新 500 条）");
        title.getStyleClass().add("card-title");
        ListView<String> list = new ListView<>(logs.entries());
        list.getStyleClass().add("log-list");
        list.setMinHeight(0);
        list.setPrefHeight(150);
        Button toggle = new Button("收起");
        Button clear = new Button("清空");
        clear.setOnAction(e -> logs.entries().clear());
        toggle.setOnAction(e -> {
            boolean show = !list.isVisible();
            list.setVisible(show);
            list.setManaged(show);
            toggle.setText(show ? "收起" : "展开");
        });
        logs.entries().addListener((javafx.collections.ListChangeListener<String>) change ->
                Platform.runLater(() -> {
                    // 空列表（如刚清空）时没有可滚动到的末行，跳过自动滚动
                    if (!logs.entries().isEmpty()) {
                        list.scrollTo(logs.entries().size() - 1);
                    }
                }));
        HBox actions = new HBox(8, toggle, clear);
        actions.setAlignment(Pos.CENTER_LEFT);
        BorderPane header = new BorderPane(null, null, actions, null, title);
        VBox root = new VBox(8, header, list);
        root.getStyleClass().add("log-drawer");

        list.setCellFactory(v -> new LogCell());
        // 单元格宽度跟随最长日志，超出部分由横向滚动条查看
        logs.entries().addListener((javafx.collections.ListChangeListener<String>) change -> updateCellWidth(list));
        updateCellWidth(list);
        return root;
    }

    private void updateCellWidth(ListView<String> list) {
        double max = 0;
        for (String entry : logs.entries()) {
            max = Math.max(max, textWidth(entry));
        }
        double width = max + CELL_H_PADDING;
        list.getProperties().put("log-cell-width", width);
    }

    private static double textWidth(String s) {
        Text text = new Text(s);
        text.setFont(Font.font("Consolas", 12.5));
        return text.getLayoutBounds().getWidth();
    }

    private class LogCell extends ListCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("log-error", "log-warn");
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
            } else {
                setText(item);
                // 长日志悬停查看完整内容；单元格内不换行省略
                setTooltip(new Tooltip(item));
                if (item.contains("[ERROR]")) {
                    getStyleClass().add("log-error");
                } else if (item.contains("[WARN]")) {
                    getStyleClass().add("log-warn");
                }
                Object w = getListView().getProperties().get("log-cell-width");
                if (w instanceof Double width) {
                    setPrefWidth(width);
                }
            }
        }
    }
}
