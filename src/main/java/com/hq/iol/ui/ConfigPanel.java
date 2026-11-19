package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.service.PrinterRegistry;
import com.hq.iol.service.ScaleRegistry;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 系统配置面板：按设备分组的配置卡片、输入校验和保存/还原。 */
@Component
public class ConfigPanel {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final ConfigService configService;
    private final UiLogService uiLog;
    private final PrinterRegistry printers;
    private final ScaleRegistry scales;
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Map<String, Label> errors = new LinkedHashMap<>();
    private final ResultBanner banner = new ResultBanner();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    public ConfigPanel(ConfigService configService, UiLogService uiLog,
                       PrinterRegistry printers, ScaleRegistry scales) {
        this.configService = configService;
        this.uiLog = uiLog;
        this.printers = printers;
        this.scales = scales;
    }

    public Parent build() {
        Parent printer = new PrinterManagerPanel(printers, uiLog).build();
        Parent scaleDevices = new ScaleManagerPanel(scales, uiLog).build();
        VBox scale = section("称重参数", "KXQY · ASCII · 秤上 COM 可设为 SOFT 或 CON");
        int row = 0;
        addField(scale, row, "稳定重量读取超时", ConfigKeys.SCALE_READ_TIMEOUT_MS, "ms");

        Button saveButton = new Button("保存配置");
        saveButton.getStyleClass().add("btn-primary");
        saveButton.disableProperty().bind(busy);
        saveButton.setOnAction(e -> save());
        Button restoreButton = new Button("还原修改");
        restoreButton.disableProperty().bind(busy);
        restoreButton.setOnAction(e -> restore());
        HBox buttons = new HBox(10, saveButton, restoreButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox actionCard = new VBox(8, banner.node(), buttons);
        actionCard.getStyleClass().add("toolbar-card");

        Parent laser = new LaserSettingsPanel(configService, uiLog).build();
        VBox content = new VBox(14, printer, scaleDevices, scale, laser, actionCard);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("config-scroll");
        return scroll;
    }

    private VBox section(String title, String subtitle) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("hint");
        VBox card = new VBox(12, titleLabel, subtitleLabel);
        card.getStyleClass().add("card");
        return card;
    }

    private int addField(VBox card, int row, String labelText, String key, String unit) {
        GridPane grid = fieldGrid(card);
        Label caption = new Label(labelText);
        caption.getStyleClass().add("field-label");
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("hint");
        VBox captionBox = new VBox(2, caption, keyLabel);
        captionBox.setMinWidth(180);

        TextField field = new TextField(configService.get(key));
        field.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(field, Priority.ALWAYS);
        Label unitLabel = new Label(unit);
        unitLabel.getStyleClass().add("hint");
        unitLabel.setMinWidth(80);
        Label error = new Label();
        error.getStyleClass().add("field-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        fields.put(key, field);
        errors.put(key, error);
        field.textProperty().addListener((obs, oldValue, newValue) -> validateField(key));
        grid.addRow(row, captionBox, field, unitLabel);
        grid.add(error, 1, row + 1, 2, 1);
        return row + 2;
    }

    private static GridPane fieldGrid(VBox card) {
        for (javafx.scene.Node node : card.getChildren()) {
            if (node instanceof GridPane grid) {
                return grid;
            }
        }
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        card.getChildren().add(grid);
        return grid;
    }

    private boolean validateField(String key) {
        Optional<String> message = ConfigInputValidator.error(key, fields.get(key).getText());
        Label error = errors.get(key);
        error.setText(message.orElse(""));
        error.setVisible(message.isPresent());
        error.setManaged(message.isPresent());
        fields.get(key).pseudoClassStateChanged(ERROR, message.isPresent());
        return message.isEmpty();
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<>();
        boolean valid = true;
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            valid &= validateField(entry.getKey());
            values.put(entry.getKey(), entry.getValue().getText());
        }
        if (!valid) {
            banner.fail("配置存在错误，请修正标红字段");
            uiLog.error("系统配置未保存：存在非法输入");
            return;
        }
        Map<String, String> snapshot = Map.copyOf(values);
        startTask("正在保存配置…", () -> {
            configService.saveAll(snapshot);
            banner.success("配置已保存");
            uiLog.info("配置已保存");
        });
    }

    private void restore() {
        fields.forEach((key, field) -> {
            field.setText(configService.get(key));
            validateField(key);
        });
        banner.clear();
        uiLog.info("配置已还原为已保存值");
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
                banner.fail(message);
                uiLog.error(message);
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "config-ui-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
