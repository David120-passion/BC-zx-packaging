package com.hq.iol.ui;

import com.hq.iol.service.PrinterStatusService;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.springframework.stereotype.Component;

import java.util.List;

/** 主界面：固定侧边导航与缓存页面，操作日志为独立导航页。 */
@Component
public class MainView {

    private final ScalePanel scalePanel;
    private final ConfigPanel configPanel;
    private final DesignerPanel designerPanel;
    private final VariablePanel variablePanel;
    private final LaserPanel laserPanel;
    private final LaserDesignPanel laserDesignPanel;
    private final UiLogService uiLog;
    private final PrinterStatusService printerStatusService;

    public MainView(ScalePanel scalePanel, ConfigPanel configPanel, DesignerPanel designerPanel,
                    VariablePanel variablePanel, LaserPanel laserPanel, LaserDesignPanel laserDesignPanel,
                    UiLogService uiLog, PrinterStatusService printerStatusService) {
        this.scalePanel = scalePanel;
        this.configPanel = configPanel;
        this.designerPanel = designerPanel;
        this.variablePanel = variablePanel;
        this.laserPanel = laserPanel;
        this.laserDesignPanel = laserDesignPanel;
        this.uiLog = uiLog;
        this.printerStatusService = printerStatusService;
    }

    public Parent build() {
        Image appIcon = new Image(getClass().getResourceAsStream("/icon.png"));
        SidebarNav nav = new SidebarNav(appIcon, List.of(
                new SidebarNav.NavItem("秤", "称重测试", "chip-green"),
                new SidebarNav.NavItem("签", "打印模板", "chip-blue"),
                new SidebarNav.NavItem("变", "变量管理", "chip-blue"),
                new SidebarNav.NavItem("标", "激光模板", "chip-blue"),
                new SidebarNav.NavItem("光", "激光打标", "chip-blue"),
                new SidebarNav.NavItem("设", "系统配置", "chip-gray"),
                new SidebarNav.NavItem("志", "操作日志", "chip-gray")));
        printerStatusService.addListener(statuses -> Platform.runLater(() ->
                nav.setPrinterStatuses(printerStatusService.profiles(), statuses)));
        printerStatusService.start();

        List<Parent> pages = List.of(
                scalePanel.build(), designerPanel.build(), variablePanel.build(), laserDesignPanel.build(),
                laserPanel.build(), configPanel.build(), new LogDrawer(uiLog).build());
        List<String> headings = List.of(
                "称重测试", "打印模板", "变量管理", "激光模板",
                "激光打标", "系统配置", "操作日志");

        Label title = new Label(headings.get(0));
        title.getStyleClass().add("page-title");
        VBox heading = new VBox(title);
        heading.getStyleClass().add("topbar");

        StackPane content = new StackPane(pages.get(0));
        content.getStyleClass().add("page-content");
        VBox.setVgrow(content, Priority.ALWAYS);

        nav.selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> {
            int index = newIndex.intValue();
            title.setText(headings.get(index));
            Parent page = pages.get(index);
            content.getChildren().setAll(page);
            FadeTransition fade = new FadeTransition(Duration.millis(180), page);
            fade.setFromValue(0.25);
            fade.setToValue(1.0);
            fade.play();
        });

        VBox center = new VBox(heading, content);
        VBox.setMargin(content, new Insets(16, 18, 16, 18));

        BorderPane root = new BorderPane();
        root.setLeft(nav.node());
        root.setCenter(center);
        uiLog.info("系统就绪");
        return root;
    }
}
