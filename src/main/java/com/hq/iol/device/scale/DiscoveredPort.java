package com.hq.iol.device.scale;

public record DiscoveredPort(String portName, String description, String serialNumber) {

    public String displayName() {
        StringBuilder text = new StringBuilder(portName);
        if (description != null && !description.isBlank()) {
            text.append(" · ").append(description);
        }
        if (serialNumber != null && !serialNumber.isBlank()) {
            text.append(" · SN=").append(serialNumber);
        }
        return text.toString();
    }
}
