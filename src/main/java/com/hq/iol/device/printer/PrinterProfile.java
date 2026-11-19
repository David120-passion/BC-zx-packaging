package com.hq.iol.device.printer;

public record PrinterProfile(String id, String name, String host, int port) {

    public PrinterProfile {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        host = host == null ? "" : host.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("打印机 ID 不能为空");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("打印机名称不能为空");
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("打印机地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("打印机端口须为 1–65535");
        }
    }
}
