package com.hq.iol.ui;

import com.hq.iol.service.InjectorLabelData;
import com.hq.iol.service.LensLabelData;
import com.hq.iol.service.PrintService;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 打印测试面板：A/B 线表单 + 状态查询 + 打印按钮。 */
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
        TextField type = new TextField("AT2000");
        TextField power = new TextField("+20.0D");
        TextField sn = new TextField("2605283500");
        TextField diNo = new TextField("06975897610400");
        TextField totalDia = new TextField("12.0");
        TextField bodyDia = new TextField("6.0");
        TextField lotNo = new TextField("B260513");
        g.addRow(0, new Label("型号"), type, new Label("度数"), power);
        g.addRow(1, new Label("序列号"), sn, new Label("UDI-DI"), diNo);
        g.addRow(2, new Label("总直径"), totalDia, new Label("主体直径"), bodyDia);
        g.addRow(3, new Label("批号(B线)"), lotNo);

        Button statusBtn = new Button("查询打印机状态");
        statusBtn.setOnAction(e -> runAsync(() ->
                uiLog.info("打印机状态: " + printService.queryPrinterStatus())));
        Button lensBtn = new Button("打印 A 线晶体标签");
        lensBtn.setOnAction(e -> runAsync(() -> {
            printService.printLens(new LensLabelData(type.getText(), power.getText(),
                    sn.getText(), LocalDate.now(), totalDia.getText(), bodyDia.getText(),
                    diNo.getText()));
            uiLog.info("A 线标签已下发: SN=" + sn.getText());
        }));
        Button injBtn = new Button("打印 B 线推注器标签");
        injBtn.setOnAction(e -> runAsync(() -> {
            printService.printInjector(new InjectorLabelData(type.getText(), lotNo.getText(),
                    LocalDate.now(), LocalDate.now().plusYears(3), "2.2",
                    "国械注准20243160001", diNo.getText()));
            uiLog.info("B 线标签已下发: LOT=" + lotNo.getText());
        }));
        g.addRow(4, statusBtn, lensBtn, injBtn);
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
