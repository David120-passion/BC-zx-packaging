package com.hq.iol.label;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelRendererPreviewTest {

    @Test
    void 预览尺寸按4px每毫米() {
        BufferedImage img = LabelRenderer.renderPreview(LabelSeed.LENS_JSON, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28", "serialNo", "2605283500"));
        assertThat(img.getWidth()).isEqualTo(240);
        assertThat(img.getHeight()).isEqualTo(160);
    }

    @Test
    void 预览中的DataMatrix可被ZXing解码回GS1流() throws Exception {
        BufferedImage img = LabelRenderer.renderPreview(LabelSeed.LENS_JSON, Map.of(
                "diNo", "06975897610400", "sterileDate", "2026-05-28", "serialNo", "2605283500"));
        // 种子设计的 DataMatrix 元素 x=3mm,y=16mm,sizeMm=20 → 4px/mm 下 (12,64,80)。
        // ZXing 3.5.3 的 WhiteRectangleDetector 从图像中心开始扩张，整图 240x160 中
        // 该符号（右缘 x=92）够不到中心区域，必须裁剪出符号邻域再解码。
        BufferedImage crop = img.getSubimage(8, 60, 88, 88);
        LuminanceSource source = new BufferedImageLuminanceSource(crop);
        String decoded = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertThat(decoded).isEqualTo("\u001d01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
    }

    @Test
    void 预览中的CODE128条码可解码() throws Exception {
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":5,\"y\":5,\"widthMm\":40,\"heightMm\":15,\"moduleMm\":0.3,\"displayText\":false,\"content\":\"ABC123\"}]}";
        BufferedImage img = LabelRenderer.renderPreview(json, Map.of());
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(img)))).getText();
        assertThat(decoded).isEqualTo("ABC123");
    }
}
