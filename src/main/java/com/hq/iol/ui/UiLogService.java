package com.hq.iol.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 操作日志：slf4j 落盘 + FX ObservableList 上屏，上限 500 条。 */
@Service
public class UiLogService {

    private static final Logger log = LoggerFactory.getLogger("ui");
    private static final int MAX = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObservableList<String> entries = FXCollections.observableArrayList();

    public ObservableList<String> entries() {
        return entries;
    }

    public void info(String msg) {
        log.info(msg);
        append("[INFO] " + msg);
    }

    public void error(String msg) {
        log.error(msg);
        append("[ERROR] " + msg);
    }

    private void append(String line) {
        String stamped = LocalTime.now().format(TIME) + " " + line;
        Runnable r = () -> {
            entries.add(stamped);
            while (entries.size() > MAX) {
                entries.remove(0);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }
}
