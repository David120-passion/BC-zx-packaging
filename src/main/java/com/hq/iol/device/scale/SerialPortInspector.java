package com.hq.iol.device.scale;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Arrays;
import java.util.List;

public final class SerialPortInspector {

    private SerialPortInspector() {
    }

    public static List<DiscoveredPort> discover() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(port -> new DiscoveredPort(port.getSystemPortName(),
                        port.getPortDescription(), port.getSerialNumber()))
                .toList();
    }
}
