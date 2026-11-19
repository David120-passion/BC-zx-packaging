package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.scale.ScaleProfile;
import com.hq.iol.device.scale.ScaleReading;
import com.hq.iol.service.ScaleRegistry;
import com.hq.iol.service.ScaleService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/** 称重测试面板：大号重量读数、配置摘要和串行设备操作。 */
@Component
public class ScalePanel {

    private final ScaleService scaleService;
    private final ConfigService configService;
    private final UiLogService uiLog;
    private final ScaleRegistry scales;
    private final Label weightValue = new Label("—");
    private final Label configSummary = new Label();
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final ComboBox<ScaleProfile> scaleBox = new ComboBox<>();

    public ScalePanel(ScaleService scaleService, ConfigService configService, UiLogService uiLog,
                      ScaleRegistry scales) {
        this.scaleService = scaleService;
        this.configService = configService;
        this.uiLog = uiLog;
        this.scales = scales;
    }

    public Parent build() {
        Label title = new Label("重量读数");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("KXQY ASCII 数据组 · 仅在 STATUS:STEADY 时返回稳定重量");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);

        weightValue.getStyleClass().add("weight-value");
        Label unit = new Label("g");
        unit.getStyleClass().add("weight-unit");
        HBox reading = new HBox(14, weightValue, unit);
        reading.setAlignment(Pos.CENTER);
        readingStatus.getStyleClass().add("hint");
        VBox readingCenter = new VBox(12, reading, readingStatus);
        readingCenter.setAlignment(Pos.CENTER);
        VBox.setVgrow(readingCenter, Priority.ALWAYS);

        VBox weightCard = new VBox(12, title, subtitle, readingCenter);
        weightCard.getStyleClass().add("card");
        weightCard.setMinWidth(0);
        HBox.setHgrow(weightCard, Priority.ALWAYS);

        scaleBox.setCellFactory(lv -> scaleCell());
        scaleBox.setButtonCell(scaleCell());
        scaleBox.setMaxWidth(Double.MAX_VALUE);
        scaleBox.disableProperty().bind(busy);
        refreshScales();
        scaleBox.valueProperty().addListener((obs, oldValue, profile) -> {
            if (oldValue != null && profile != null && !profile.id().equals(scales.active().id())) {
                scales.setActive(profile.id());
                weightValue.setText("—");
                readingStatus.setText("尚未读取");
                refreshConfigSummary();
                uiLog.info("当前电子秤: " + profile.name());
            }
        });

        Button readButton = new Button("读取稳定重量");
        readButton.getStyleClass().add("btn-primary");
        readButton.setMaxWidth(Double.MAX_VALUE);
        readButton.disableProperty().bind(busy);
        readButton.setOnAction(e -> readWeight());

        Button currentButton = new Button("读取当前数据");
        currentButton.setMaxWidth(Double.MAX_VALUE);
        currentButton.disableProperty().bind(busy);
        currentButton.setOnAction(e -> readCurrent());

        Button tareButton = new Button("去皮");
        tareButton.setMaxWidth(Double.MAX_VALUE);
        tareButton.disableProperty().bind(busy);
        tareButton.setOnAction(e -> adjustWeight("去皮", scaleService::tare));
        Button zeroButton = new Button("置零");
        zeroButton.setMaxWidth(Double.MAX_VALUE);
        zeroButton.disableProperty().bind(busy);
        zeroButton.setOnAction(e -> adjustWeight("置零", scaleService::zero));
        HBox adjustmentButtons = new HBox(8, tareButton, zeroButton);
        HBox.setHgrow(tareButton, Priority.ALWAYS);
        HBox.setHgrow(zeroButton, Priority.ALWAYS);

        configSummary.getStyleClass().add("hint");
        configSummary.setWrapText(true);
        Label protocolHint = new Label("KXQY · ASCII · 8N1\n秤上 FMT=ASCII，COM=SOFT 或 CON，地址与上方配置一致。\n000 为广播地址，不能用于读取。");
        protocolHint.getStyleClass().add("hint");
        protocolHint.setWrapText(true);

        VBox actions = new VBox(12,
                new Label("称重操作"), scaleBox, banner.node(), readButton, currentButton,
                adjustmentButtons, new Region(), configSummary, protocolHint);
        actions.getChildren().get(0).getStyleClass().add("card-title");
        actions.getStyleClass().add("card");
        actions.setPrefWidth(300);
        actions.setMinWidth(260);

        HBox root = new HBox(14, weightCard, actions);
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                refreshScales();
            }
        });
        refreshConfigSummary();
        return root;
    }

    private void refreshScales() {
        scaleBox.getItems().setAll(scales.list());
        ScaleProfile active = scales.active();
        scaleBox.getItems().stream().filter(profile -> profile.id().equals(active.id()))
                .findFirst().ifPresent(scaleBox::setValue);
        refreshConfigSummary();
    }

    private static ListCell<ScaleProfile> scaleCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ScaleProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + " · " + item.port());
            }
        };
    }

    private final Label readingStatus = new Label("尚未读取");

    private void refreshConfigSummary() {
        ScaleProfile active = scales.active();
        configSummary.setText("当前设备：" + active.name()
                + "\n串口：" + active.port() + " · " + active.baud() + " / 8N1"
                + "\n设备地址：" + String.format("%03d", active.address())
                + "\n稳定等待上限：" + configService.get(ConfigKeys.SCALE_READ_TIMEOUT_MS) + " ms");
    }

    private void readWeight() {
        startTask("正在等待稳定重量…", () -> {
            ScaleReading reading = scaleService.readStableReading();
            showReading(reading);
            banner.success("稳定重量读取成功");
            uiLog.info("稳定重量: " + reading.value() + " g");
        });
    }

    private void readCurrent() {
        startTask("正在读取当前数据组…", () -> {
            ScaleReading reading = scaleService.readCurrentFrame();
            showReading(reading);
            banner.success("当前状态：" + reading.statusDescription());
            uiLog.info("当前重量: " + reading.value() + " g · " + reading.status());
        });
    }

    private void showReading(ScaleReading reading) {
        Platform.runLater(() -> {
            weightValue.setText(reading.value().toPlainString());
            readingStatus.setText("状态：" + reading.statusDescription()
                    + (reading.mode().isEmpty() ? "" : " · 模式：" + reading.mode()));
        });
    }

    private void adjustWeight(String operation, WeightAdjustment adjustment) {
        startTask("正在" + operation + "…", () -> {
            Optional<ScaleReading> result = adjustment.run();
            if (result.isEmpty()) {
                String message = operation + "广播已发送，设备无回读";
                Platform.runLater(() -> {
                    weightValue.setText("—");
                    readingStatus.setText(message);
                });
                banner.success(message);
                uiLog.info(message);
                return;
            }

            ScaleReading reading = result.get();
            showReading(reading);
            String details = operation + "发送队列已排空，稳定回读 " + reading.value().toPlainString() + " g"
                    + "，TARE=" + reading.fields().getOrDefault("TARE", "—")
                    + "，ZERO=" + reading.fields().getOrDefault("ZERO", "—");
            if (reading.value().signum() == 0) {
                banner.success(operation + "后回读为零");
                uiLog.info(details);
            } else {
                banner.fail(operation + "后回读未归零");
                uiLog.error(details + "，未归零");
            }
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
                Platform.runLater(() -> {
                    weightValue.setText("—");
                    readingStatus.setText("未取得读数");
                });
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

    @FunctionalInterface
    private interface WeightAdjustment {
        Optional<ScaleReading> run() throws IOException;
    }
}
