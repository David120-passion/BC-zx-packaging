package com.hq.iol.ui;

import com.hq.iol.label.LabelTemplate;
import com.hq.iol.service.PrintService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 打印测试面板：选模板 → 按变量生成表单 → 打印。 */
@Component
public class PrintPanel {

    private final PrintService printService;
    private final UiLogService uiLog;

    public PrintPanel(PrintService printService, UiLogService uiLog) {
        this.printService = printService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);

        ComboBox<LabelTemplate> templateBox = new ComboBox<>();
        templateBox.setCellFactory(lv -> cell());
        templateBox.setButtonCell(cell());
        Map<String, TextField> varFields = new LinkedHashMap<>();
        GridPane varGrid = new GridPane();
        varGrid.setHgap(8);
        varGrid.setVgap(8);

        Runnable rebuildVars = () -> {
            varFields.clear();
            varGrid.getChildren().clear();
            LabelTemplate t = templateBox.getValue();
            if (t == null) {
                return;
            }
            Set<String> vars = printService.templateVariables(t.name());
            int row = 0;
            for (String v : vars) {
                TextField tf = new TextField();
                varFields.put(v, tf);
                varGrid.addRow(row++, new Label(v), tf);
            }
        };
        templateBox.setOnAction(e -> rebuildVars.run());

        Button reloadBtn = new Button("刷新模板");
        reloadBtn.setOnAction(e -> runAsync(() -> {
            var list = printService.listTemplates();
            javafx.application.Platform.runLater(() -> {
                templateBox.getItems().setAll(list);
                if (!list.isEmpty()) {
                    templateBox.getSelectionModel().selectFirst();
                    rebuildVars.run();
                }
            });
        }));

        Button statusBtn = new Button("查询打印机状态");
        statusBtn.setOnAction(e -> runAsync(() ->
                uiLog.info("打印机状态: " + printService.queryPrinterStatus())));

        Button printBtn = new Button("打印");
        printBtn.setOnAction(e -> {
            LabelTemplate t = templateBox.getValue();
            if (t == null) {
                uiLog.error("请先选择模板");
                return;
            }
            Map<String, String> vars = new LinkedHashMap<>();
            varFields.forEach((k, tf) -> vars.put(k, tf.getText()));
            runAsync(() -> {
                printService.print(t.name(), vars);
                uiLog.info("已下发打印: " + t.name());
            });
        });

        g.addRow(0, new Label("模板"), templateBox, reloadBtn, statusBtn, printBtn);
        g.add(varGrid, 0, 1, 5, 1);
        runAsync(() -> {
            var list = printService.listTemplates();
            javafx.application.Platform.runLater(() -> {
                templateBox.getItems().setAll(list);
                if (!list.isEmpty()) {
                    templateBox.getSelectionModel().selectFirst();
                    rebuildVars.run();
                }
            });
        });
        return g;
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
