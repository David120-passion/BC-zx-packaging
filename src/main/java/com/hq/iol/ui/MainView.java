package com.hq.iol.ui;

import javafx.animation.FadeTransition;
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

/** 主界面：固定侧边导航、缓存页面和全局操作日志抽屉。 */
@Component
public class MainView {

    private final PrintPanel printPanel;
    private final ScalePanel scalePanel;
    private final ConfigPanel configPanel;
    private final DesignerPanel designerPanel;
    private final UiLogService uiLog;

    public MainView(PrintPanel printPanel, ScalePanel scalePanel,
                    ConfigPanel configPanel, DesignerPanel designerPanel, UiLogService uiLog) {
        this.printPanel = printPanel;
        this.scalePanel = scalePanel;
        this.configPanel = configPanel;
        this.designerPanel = designerPanel;
        this.uiLog = uiLog;
    }

    public Parent build() {
        Image appIcon = new Image(getClass().getResourceAsStream("/icon.png"));
        SidebarNav nav = new SidebarNav(appIcon, List.of(
                new SidebarNav.NavItem("打", "打印测试", "chip-blue"),
                new SidebarNav.NavItem("秤", "称重测试", "chip-green"),
                new SidebarNav.NavItem("设", "系统配置", "chip-gray"),
                new SidebarNav.NavItem("签", "标签设计", "chip-blue")));

        List<Parent> pages = List.of(
                printPanel.build(), scalePanel.build(), configPanel.build(), designerPanel.build());
        List<String[]> headings = List.of(
                new String[]{"打印测试", "选择模板、录入变量并验证标签输出"},
                new String[]{"称重测试", "读取 EK-i 稳定重量并执行去皮/清零"},
                new String[]{"系统配置", "管理打印机与电子秤连接参数"},
                new String[]{"标签设计", "编辑模板布局、变量与打印效果"});

        Label title = new Label(headings.get(0)[0]);
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(headings.get(0)[1]);
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(3, title, subtitle);
        heading.getStyleClass().add("topbar");

        StackPane content = new StackPane(pages.get(0));
        content.getStyleClass().add("page-content");
        VBox.setVgrow(content, Priority.ALWAYS);

        nav.selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> {
            int index = newIndex.intValue();
            title.setText(headings.get(index)[0]);
            subtitle.setText(headings.get(index)[1]);
            Parent page = pages.get(index);
            content.getChildren().setAll(page);
            FadeTransition fade = new FadeTransition(Duration.millis(180), page);
            fade.setFromValue(0.25);
            fade.setToValue(1.0);
            fade.play();
        });

        Parent logDrawer = new LogDrawer(uiLog).build();
        VBox center = new VBox(heading, content, logDrawer);
        VBox.setMargin(content, new Insets(16, 18, 12, 18));
        VBox.setMargin(logDrawer, new Insets(0, 18, 12, 18));

        BorderPane root = new BorderPane();
        root.setLeft(nav.node());
        root.setCenter(center);
        uiLog.info("系统就绪");
        return root;
    }
}
