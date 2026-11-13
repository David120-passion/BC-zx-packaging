package com.hq.iol.ui;

import com.hq.iol.service.ScaleService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 称重测试面板：读稳定重量 + 去皮。 */
@Component
public class ScalePanel {

    private final ScaleService scaleService;
    private final UiLogService uiLog;

    public ScalePanel(ScaleService scaleService, UiLogService uiLog) {
        this.scaleService = scaleService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Button readBtn = new Button("读取稳定重量");
        readBtn.setOnAction(e -> runAsync(() -> {
            BigDecimal w = scaleService.readStableWeight();
            uiLog.info("稳定重量: " + w + " g");
        }));
        Button tareBtn = new Button("去皮/清零");
        tareBtn.setOnAction(e -> runAsync(() -> {
            scaleService.tare();
            uiLog.info("已去皮");
        }));
        return new HBox(8, new Label("EK-i:"), readBtn, tareBtn);
    }

    private void runAsync(IoAction action) {
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Exception ex) {
                uiLog.error(ex.getMessage());
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
