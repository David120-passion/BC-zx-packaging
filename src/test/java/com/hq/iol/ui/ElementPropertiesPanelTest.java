package com.hq.iol.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.FxTestToolkit;
import com.hq.iol.label.DesignElements;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementPropertiesPanelTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Test
    void 所有组件的属性表单均可实际创建() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var design = mapper.createObjectNode();
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.put("dpi", 600);
        design.putArray("elements");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ElementPropertiesPanel panel = new ElementPropertiesPanel(() -> { }, () -> { }, message -> { });
                panel.build();
                panel.setDesign(design);
                for (String type : DesignElements.types()) {
                    panel.setElement(DesignElements.create(type, 1));
                }
                panel.setElement(null);
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "属性表单应及时创建");
        assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
    }
}
