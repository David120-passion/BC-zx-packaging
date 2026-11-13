package com.hq.iol.ui;

import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import org.springframework.stereotype.Component;

/** 主界面：上方 Tab 面板，下方操作日志列表。 */
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
        TabPane tabs = new TabPane(
                new Tab("打印测试", printPanel.build()),
                new Tab("称重测试", scalePanel.build()),
                new Tab("系统配置", configPanel.build()),
                new Tab("标签设计", designerPanel.build()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        ListView<String> logView = new ListView<>(uiLog.entries());

        BorderPane root = new BorderPane();
        root.setTop(tabs);
        root.setCenter(logView);
        return root;
    }
}
