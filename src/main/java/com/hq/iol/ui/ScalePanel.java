package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.service.ScaleService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 称重测试面板：大号重量读数、配置摘要和串行设备操作。 */
@Component
public class ScalePanel {

    private final ScaleService scaleService;
    private final ConfigService configService;
    private final UiLogService uiLog;
    private final Label weightValue = new Label("—");
    private final Label configSummary = new Label();
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    public ScalePanel(ScaleService scaleService, ConfigService configService, UiLogService uiLog) {
        this.scaleService = scaleService;
        this.configService = configService;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Label title = new Label("稳定重量");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("仅在电子秤返回 ST/QT 且单位为克时显示读数");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);

        weightValue.getStyleClass().add("weight-value");
        Label unit = new Label("g");
        unit.getStyleClass().add("weight-unit");
        HBox reading = new HBox(14, weightValue, unit);
        reading.setAlignment(Pos.CENTER);
        VBox readingCenter = new VBox(reading);
        readingCenter.setAlignment(Pos.CENTER);
        VBox.setVgrow(readingCenter, Priority.ALWAYS);

        VBox weightCard = new VBox(12, title, subtitle, readingCenter);
        weightCard.getStyleClass().add("card");
        weightCard.setMinWidth(0);
        HBox.setHgrow(weightCard, Priority.ALWAYS);

        Button readButton = new Button("读取稳定重量");
        readButton.getStyleClass().add("btn-primary");
        readButton.setMaxWidth(Double.MAX_VALUE);
        readButton.disableProperty().bind(busy);
        readButton.setOnAction(e -> readWeight());

        Button tareButton = new Button("去皮/清零");
        tareButton.setMaxWidth(Double.MAX_VALUE);
        tareButton.disableProperty().bind(busy);
        tareButton.setOnAction(e -> tare());

        configSummary.getStyleClass().add("hint");
        configSummary.setWrapText(true);
        Label protocolHint = new Label("EK-i · RS-232C · 9600 / 8N1\n配置值仅用于连接，不代表设备在线。");
        protocolHint.getStyleClass().add("hint");
        protocolHint.setWrapText(true);

        VBox actions = new VBox(12,
                new Label("称重操作"), banner.node(), readButton, tareButton,
                new Region(), configSummary, protocolHint);
        actions.getChildren().get(0).getStyleClass().add("card-title");
        actions.getStyleClass().add("card");
        actions.setPrefWidth(280);
        actions.setMinWidth(240);

        HBox root = new HBox(14, weightCard, actions);
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                refreshConfigSummary();
            }
        });
        refreshConfigSummary();
        return root;
    }

    private void refreshConfigSummary() {
        configSummary.setText("当前配置\n串口：" + configService.get(ConfigKeys.SCALE_PORT)
                + "\n稳定等待上限：" + configService.get(ConfigKeys.SCALE_READ_TIMEOUT_MS) + " ms");
    }

    private void readWeight() {
        startTask("正在等待稳定重量…", () -> {
            BigDecimal weight = scaleService.readStableWeight();
            Platform.runLater(() -> weightValue.setText(weight.toPlainString()));
            banner.success("稳定重量读取成功");
            uiLog.info("稳定重量: " + weight + " g");
        });
    }

    private void tare() {
        startTask("正在去皮/清零…", () -> {
            scaleService.tare();
            Platform.runLater(() -> weightValue.setText("—"));
            banner.success("去皮/清零成功，请重新读取重量");
            uiLog.info("已去皮");
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
                Platform.runLater(() -> weightValue.setText("—"));
                banner.fail(message);
                uiLog.error(message);
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "scale-ui-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
