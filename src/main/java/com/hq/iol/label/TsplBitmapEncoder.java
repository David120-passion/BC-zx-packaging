package com.hq.iol.label;

import java.awt.image.BufferedImage;

/**
 * BufferedImage → TSPL BITMAP 指令。
 * 1bpp、行内按字节对齐、MSB 在前，黑点写 0、白点写 1。
 */
public final class TsplBitmapEncoder {

    private TsplBitmapEncoder() {
    }

    /** 打包为 1bpp 字节流：TSPL 黑点写 0、白点写 1，MSB 在前。 */
    static byte[] toMonoBytes(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int rowBytes = (w + 7) / 8;
        byte[] out = new byte[rowBytes * h];
        java.util.Arrays.fill(out, (byte) 0xFF);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114;
                if (lum / 1000 < 128) {
                    out[y * rowBytes + x / 8] &= (byte) ~(0x80 >> (x % 8));
                }
            }
        }
        return out;
    }

    /** BITMAP x,y,字节宽,点高,OVERWRITE(0),大写hex + CR LF；发送时再转二进制。 */
    public static String toBitmapCommand(BufferedImage img, int x, int y) {
        byte[] bytes = toMonoBytes(img);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
               .append(Character.forDigit(b & 0xF, 16));
        }
        return "BITMAP %d,%d,%d,%d,0,%s\r\n".formatted(
                x, y, (img.getWidth() + 7) / 8, img.getHeight(),
                hex.toString().toUpperCase());
    }
}
