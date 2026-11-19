package com.hq.iol.service;

import com.hq.iol.device.laser.LaserDesign;

import java.nio.file.Path;
import java.time.Duration;

public interface LaserOperations extends AutoCloseable {

    void initialize(Path sdkDirectory, boolean testMode) throws Exception;

    void buildDesign(LaserDesign design) throws Exception;

    void mark(Duration timeout) throws Exception;

    void redLight() throws Exception;

    void stopMarking() throws Exception;

    @Override
    void close();
}
