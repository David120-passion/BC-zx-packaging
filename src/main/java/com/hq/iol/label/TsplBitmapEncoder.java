package com.hq.iol.label;

import java.awt.image.BufferedImage;

/**
 * BufferedImage → TSPL GRAPH 指令。
 * 1bpp、行内按字节对齐、每字节高位（MSB）在前、灰度 <128 为黑点。
 */
public final class TsplBitmapEncoder {

    private TsplBitmapEncoder() {
    }

    /** 打包为 1bpp 字节流（包级可见供测试）。 */
    static byte[] toMonoBytes(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int rowBytes = (w + 7) / 8;
        byte[] out = new byte[rowBytes * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114;
                if (lum / 1000 < 128) {
                    out[y * rowBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return out;
    }

    /** GRAPH x,y,字节宽,点高,&lt;大写hex&gt; + CR LF。 */
    public static String toGraphCommand(BufferedImage img, int x, int y) {
        byte[] bytes = toMonoBytes(img);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
               .append(Character.forDigit(b & 0xF, 16));
        }
        return "GRAPH %d,%d,%d,%d,%s\r\n".formatted(
                x, y, (img.getWidth() + 7) / 8, img.getHeight(),
                hex.toString().toUpperCase());
    }
}
