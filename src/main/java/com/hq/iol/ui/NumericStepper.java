package com.hq.iol.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class NumericStepper {

    private NumericStepper() {
    }

    static Node attach(TextField input, double min, double max, double step,
                       boolean integer, Runnable apply) {
        Runnable increase = () -> adjust(input, min, max, step, integer, apply);
        Runnable decrease = () -> adjust(input, min, max, -step, integer, apply);
        return withArrows(input, increase, decrease);
    }

    static Node attachChoices(ComboBox<?> combo) {
        combo.setMaxWidth(Double.MAX_VALUE);
        Button[] buttons = arrowButtons();
        Runnable update = () -> {
            int index = combo.getSelectionModel().getSelectedIndex();
            buttons[0].setDisable(index >= combo.getItems().size() - 1);
            buttons[1].setDisable(index <= 0);
        };
        buttons[0].setOnAction(event -> {
            combo.getSelectionModel().select(Math.min(combo.getItems().size() - 1,
                    combo.getSelectionModel().getSelectedIndex() + 1));
            update.run();
        });
        buttons[1].setOnAction(event -> {
            combo.getSelectionModel().select(Math.max(0,
                    combo.getSelectionModel().getSelectedIndex() - 1));
            update.run();
        });
        combo.valueProperty().addListener((obs, oldValue, newValue) -> update.run());
        update.run();
        return withArrows(combo, buttons[0], buttons[1]);
    }

    private static void adjust(TextField input, double min, double max, double delta,
                               boolean integer, Runnable apply) {
        apply.run();
        try {
            double value = Double.parseDouble(input.getText().trim()) + delta;
            value = Math.max(min, Math.min(max, value));
            value = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
            if (integer) {
                input.setText(Integer.toString((int) Math.round(value)));
            } else {
                input.setText(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
            }
            apply.run();
        } catch (NumberFormatException ignored) {
            input.setText(BigDecimal.valueOf(min).stripTrailingZeros().toPlainString());
            apply.run();
        }
    }

    private static Node withArrows(Node content, Runnable increase, Runnable decrease) {
        Button[] buttons = arrowButtons();
        buttons[0].setOnAction(event -> increase.run());
        buttons[1].setOnAction(event -> decrease.run());
        return withArrows(content, buttons[0], buttons[1]);
    }

    private static Node withArrows(Node content, Button increase, Button decrease) {
        increase.setTooltip(new Tooltip("增加"));
        decrease.setTooltip(new Tooltip("减少"));
        VBox buttons = new VBox(2, increase, decrease);
        buttons.getStyleClass().add("stepper-buttons");
        HBox row = new HBox(4, content, buttons);
        row.getStyleClass().add("numeric-stepper");
        HBox.setHgrow(content, Priority.ALWAYS);
        return row;
    }

    private static Button[] arrowButtons() {
        Button up = new Button("▲");
        Button down = new Button("▼");
        for (Button button : new Button[]{up, down}) {
            button.getStyleClass().add("stepper-button");
            button.setFocusTraversable(false);
        }
        return new Button[]{up, down};
    }
}
