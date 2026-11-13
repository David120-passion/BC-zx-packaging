package com.hq.iol;

import com.hq.iol.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** JavaFX 入口：main 启动 FX，后台 daemon 线程引导 Spring，完成后挂载主界面。 */
public class MainApp extends Application {

    private ConfigurableApplicationContext ctx;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("IOL 包装线上位机");
        BorderPane root = new BorderPane(new Label("Spring 启动中…"));
        stage.setScene(new Scene(root, 960, 640));
        stage.show();

        Thread boot = new Thread(() -> {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("data"));
                ctx = new SpringApplicationBuilder(SpringMain.class)
                        .web(WebApplicationType.NONE)
                        .headless(false)
                        .run();
                Platform.runLater(() -> root.setCenter(ctx.getBean(MainView.class).build()));
            } catch (Exception e) {
                Platform.runLater(() ->
                        root.setCenter(new Label("Spring 启动失败: " + e.getMessage())));
            }
        });
        boot.setDaemon(true);
        boot.start();
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }
}
