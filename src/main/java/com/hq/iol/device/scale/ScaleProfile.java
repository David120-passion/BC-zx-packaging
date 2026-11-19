package com.hq.iol.device.scale;

public record ScaleProfile(String id, String name, String port, String description,
                           Integer address, Integer baud) {

    public ScaleProfile(String id, String name, String port, String description) {
        this(id, name, port, description, 1, 9600);
    }

    public ScaleProfile {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        port = port == null ? "" : port.trim();
        description = description == null ? "" : description.trim();
        address = address == null ? 1 : address;
        baud = baud == null ? 9600 : baud;
        if (id.isEmpty()) {
            throw new IllegalArgumentException("电子秤 ID 不能为空");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("电子秤名称不能为空");
        }
        if (port.isEmpty()) {
            throw new IllegalArgumentException("电子秤串口不能为空");
        }
        if (address < 0 || address > 255) {
            throw new IllegalArgumentException("电子秤地址必须在 0–255 之间");
        }
        if (baud != 1200 && baud != 2400 && baud != 4800 && baud != 9600) {
            throw new IllegalArgumentException("电子秤波特率仅支持 1200、2400、4800、9600");
        }
    }
}
