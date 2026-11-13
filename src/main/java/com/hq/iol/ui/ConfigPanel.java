package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统配置面板：展示并保存 ConfigKeys.DEFAULTS 中的全部键。 */
@Component
public class ConfigPanel {

    private final ConfigService configService;
    private final UiLogService uiLog;

    public ConfigPanel(ConfigService configService, UiLogService uiLog) {
        this.configService = configService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        Map<String, TextField> fields = new LinkedHashMap<>();
        int row = 0;
        for (String key : ConfigKeys.DEFAULTS.keySet()) {
            TextField tf = new TextField(configService.get(key));
            fields.put(key, tf);
            g.addRow(row++, new Label(key), tf);
        }
        Button save = new Button("保存");
        save.setOnAction(e -> {
            Map<String, String> values = new LinkedHashMap<>();
            fields.forEach((k, tf) -> values.put(k, tf.getText()));
            runAsync(() -> {
                configService.saveAll(values);
                uiLog.info("配置已保存");
            });
        });
        g.addRow(row, save, new Label());
        return g;
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
