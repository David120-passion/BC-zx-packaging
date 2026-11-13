package com.hq.iol.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.DataInputStream;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class UiResourcesTest {
    @Test
    void bundledFontsAndMasterIconExist() throws Exception {
        assertNotNull(getClass().getResource("/fonts/NotoSansCJKsc-Medium.otf"));
        assertNotNull(getClass().getResource("/fonts/NotoSansCJKsc-Bold.otf"));
        assertNotNull(getClass().getResource("/branding/app-icon.svg"));
        var master = ImageIO.read(getClass().getResource("/branding/app-icon-master.png"));
        assertEquals(512, master.getWidth());
        assertEquals(512, master.getHeight());
    }

    @Test
    void themeStylesheetExists() {
        assertNotNull(getClass().getResource("/app.css"));
    }

    @Test
    void splashExposesLifecycleContract() throws Exception {
        Class<?> type = Class.forName("com.hq.iol.ui.SplashScreen");
        type.getConstructor(javafx.scene.image.Image.class);
        type.getMethod("show");
        type.getMethod("updateStatus", String.class);
        type.getMethod("finish", Runnable.class);
        type.getMethod("close");
    }

    @Test
    void windowIconsAndIcoHaveDeclaredSizes() throws Exception {
        for (int size : new int[]{16, 32, 64, 256}) {
            var image = ImageIO.read(getClass().getResource("/icon-" + size + ".png"));
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
        }
        assertEquals(512, ImageIO.read(getClass().getResource("/icon.png")).getWidth());
        try (InputStream in = getClass().getResourceAsStream("/icon.ico");
             DataInputStream data = new DataInputStream(in)) {
            assertEquals(0, Short.reverseBytes(data.readShort()));
            assertEquals(1, Short.reverseBytes(data.readShort()));
            assertEquals(4, Short.reverseBytes(data.readShort()));
            for (int expected : new int[]{16, 32, 64, 256}) {
                int width = data.readUnsignedByte();
                int height = data.readUnsignedByte();
                assertEquals(expected, width == 0 ? 256 : width);
                assertEquals(expected, height == 0 ? 256 : height);
                assertEquals(14, data.skipBytes(14));
            }
        }
    }
}
