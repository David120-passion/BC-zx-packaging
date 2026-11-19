package com.hq.iol.device.printer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 将可读的 BITMAP hex 预览指令转换为 TSPL 实际发送的二进制数据流。 */
public final class TsplBinaryCodec {

    private TsplBinaryCodec() {
    }

    public static byte[] encode(String tspl) {
        int commandStart = tspl.indexOf("BITMAP ");
        if (commandStart < 0) {
            return tspl.getBytes(StandardCharsets.UTF_8);
        }
        int lineEnd = tspl.indexOf("\r\n", commandStart);
        if (lineEnd < 0) {
            throw new IllegalArgumentException("BITMAP 指令缺少 CR LF");
        }
        int dataStart = tspl.lastIndexOf(',', lineEnd);
        if (dataStart < commandStart) {
            throw new IllegalArgumentException("BITMAP 指令缺少位图数据");
        }
        String command = tspl.substring(commandStart, dataStart);
        String hex = tspl.substring(dataStart + 1, lineEnd);
        String[] parts = command.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("BITMAP 指令参数不完整: " + command);
        }
        int rowBytes = Integer.parseInt(parts[2]);
        int height = Integer.parseInt(parts[3]);
        if (!parts[4].equals("0")) {
            throw new IllegalArgumentException("BITMAP 仅支持 OVERWRITE 模式 0");
        }
        if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException("BITMAP hex 数据格式错误");
        }
        int expectedBytes = rowBytes * height;
        if (expectedBytes != hex.length() / 2) {
            throw new IllegalArgumentException("BITMAP 数据长度不匹配，期望 " + expectedBytes
                    + " 字节，实际 " + (hex.length() / 2) + " 字节");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(tspl.substring(0, dataStart + 1).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < hex.length(); i += 2) {
                out.write(Integer.parseInt(hex.substring(i, i + 2), 16));
            }
            out.write(tspl.substring(lineEnd).getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("BITMAP 二进制编码失败", e);
        }
    }
}
