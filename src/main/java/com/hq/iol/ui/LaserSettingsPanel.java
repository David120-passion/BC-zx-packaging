package com.hq.iol.ui;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LaserSettingsPanel {

    private final ConfigService config;
    private final UiLogService uiLog;
    private final TextField sdkDirectory = new TextField();
    private final TextField markTimeout = new TextField();
    private final ResultBanner banner = new ResultBanner();

    public LaserSettingsPanel(ConfigService config, UiLogService uiLog) {
        this.config = config;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Label title = new Label("激光打标");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("Ezcad2 SDK 目录需包含 MarkEzd.dll 和 EZCAD.CFG；留空使用程序随附目录");
        subtitle.getStyleClass().add("hint");
        subtitle.setWrapText(true);

        sdkDirectory.setText(config.get(ConfigKeys.LASER_SDK_DIRECTORY));
        sdkDirectory.setPromptText("Ezcad2 SDK 运行目录");
        sdkDirectory.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sdkDirectory, Priority.ALWAYS);
        Button browse = new Button("选择目录");
        browse.setOnAction(event -> chooseDirectory());
        HBox directory = new HBox(8, sdkDirectory, browse);

        markTimeout.setText(config.get(ConfigKeys.LASER_MARK_TIMEOUT_MS));
        markTimeout.setPrefWidth(180);
        HBox timeout = new HBox(8, new Label("打标等待上限"),
                NumericStepper.attach(markTimeout, 1, 3600000, 1000, true, () -> { }), new Label("ms"));
        timeout.setAlignment(Pos.CENTER_LEFT);

        Button save = new Button("保存打标设置");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(event -> save());
        VBox root = new VBox(12, title, subtitle, directory, timeout, save, banner.node());
        root.getStyleClass().add("card");
        return root;
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Ezcad2 SDK 运行目录");
        String current = sdkDirectory.getText().trim();
        try {
            if (!current.isEmpty() && Files.isDirectory(Path.of(current))) {
                chooser.setInitialDirectory(Path.of(current).toFile());
            }
        } catch (Exception e) {
            banner.fail("SDK 目录路径无效，请重新选择");
        }
        var selected = chooser.showDialog(sdkDirectory.getScene().getWindow());
        if (selected != null) {
            sdkDirectory.setText(selected.getAbsolutePath());
        }
    }

    private void save() {
        try {
            String directory = sdkDirectory.getText().trim();
            if (!directory.isEmpty() && !Files.isRegularFile(Path.of(directory, "MarkEzd.dll"))) {
                throw new IllegalArgumentException("所选目录缺少 MarkEzd.dll");
            }
            int timeout = Integer.parseInt(markTimeout.getText().trim());
            if (timeout <= 0) {
                throw new IllegalArgumentException("打标等待上限必须为正整数");
            }
            config.saveAll(Map.of(
                    ConfigKeys.LASER_SDK_DIRECTORY, directory,
                    ConfigKeys.LASER_MARK_TIMEOUT_MS, Integer.toString(timeout)));
            banner.success("打标设置已保存");
            uiLog.info("激光打标设置已保存");
        } catch (Exception e) {
            banner.fail(e.getMessage());
        }
    }
}
