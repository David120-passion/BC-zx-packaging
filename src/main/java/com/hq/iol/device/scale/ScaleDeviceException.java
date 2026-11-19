package com.hq.iol.device.scale;

import java.io.IOException;

public class ScaleDeviceException extends IOException {

    private final String status;

    public ScaleDeviceException(String status, String message) {
        super("电子秤" + message);
        this.status = status;
    }

    public String status() {
        return status;
    }
}
