package com.hq.iol.service;

import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.laser.LaserDesign;
import com.hq.iol.device.laser.LaserDesignCompiler;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class LaserService {

    private final ConfigService config;
    private final Supplier<LaserOperations> operationsFactory;
    private final Object lifecycleLock = new Object();

    private volatile LaserOperations operations;
    private volatile boolean marking;
    private Path sdkDirectory;
    private LaserDesign design;
    private boolean testMode = true;

    @Autowired
    public LaserService(ConfigService config, ObjectProvider<LaserOperations> provider) {
        this(config, () -> Objects.requireNonNull(provider.getIfAvailable(),
                "激光打标桥接未初始化"));
    }

    public LaserService(ConfigService config, Supplier<LaserOperations> operationsFactory) {
        this.config = config;
        this.operationsFactory = operationsFactory;
    }

    public String previewDesign(String designJson, Map<String, String> variables) {
        return LaserDesignCompiler.compile(designJson, variables).summary();
    }

    public void markDesign(String designJson, Map<String, String> variables, boolean useTestMode) throws Exception {
        LaserDesign compiled = LaserDesignCompiler.compile(designJson, variables);
        LaserOperations active;
        synchronized (lifecycleLock) {
            ensureIdle();
            prepareConnection(useTestMode);
            try {
                operations.buildDesign(compiled);
                design = compiled;
                marking = true;
                active = operations;
            } catch (Exception e) {
                closeConnection();
                throw e;
            }
        }
        try {
            active.mark(Duration.ofMillis(config.getInt(ConfigKeys.LASER_MARK_TIMEOUT_MS)));
        } finally {
            marking = false;
        }
    }

    public void redLightDesign(String designJson, Map<String, String> variables) throws Exception {
        LaserDesign compiled = LaserDesignCompiler.compile(designJson, variables);
        LaserOperations active;
        synchronized (lifecycleLock) {
            ensureIdle();
            prepareConnection(false);
            try {
                operations.buildDesign(compiled);
                design = compiled;
                marking = true;
                active = operations;
            } catch (Exception e) {
                closeConnection();
                throw e;
            }
        }
        try {
            active.redLight();
        } finally {
            marking = false;
        }
    }

    public void stopMarking() throws Exception {
        LaserOperations active = operations;
        if (active != null) {
            active.stopMarking();
        }
    }

    public boolean isMarking() {
        return marking;
    }

    public boolean isLoaded() {
        return design != null;
    }

    public Path sdkDirectory() {
        String configured = config.get(ConfigKeys.LASER_SDK_DIRECTORY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String executable = System.getProperty("jpackage.app-path", "");
        if (!executable.isBlank()) {
            return Path.of(executable).toAbsolutePath().getParent().resolve("laser-sdk");
        }
        return Path.of("src/main/laser-sdk").toAbsolutePath().normalize();
    }

    private void prepareConnection(boolean useTestMode) throws Exception {
        Path sdk = sdkDirectory();
        if (operations != null && sdk.equals(sdkDirectory) && testMode == useTestMode) {
            return;
        }
        closeConnection();
        if (!Files.isRegularFile(sdk.resolve("MarkEzd.dll"))) {
            throw new IllegalArgumentException("SDK 目录缺少 MarkEzd.dll: " + sdk);
        }
        if (!Files.isRegularFile(sdk.resolve("EZCAD.CFG"))) {
            throw new IllegalArgumentException("SDK 目录缺少 EZCAD.CFG: " + sdk);
        }
        operations = operationsFactory.get();
        try {
            operations.initialize(sdk, useTestMode);
            sdkDirectory = sdk;
            testMode = useTestMode;
        } catch (Exception e) {
            closeConnection();
            throw e;
        }
    }

    private void ensureIdle() {
        if (marking) {
            throw new IllegalStateException("正在打标，请先停止或等待完成");
        }
    }

    @PreDestroy
    public void close() {
        synchronized (lifecycleLock) {
            closeConnection();
        }
    }

    private void closeConnection() {
        LaserOperations current = operations;
        operations = null;
        sdkDirectory = null;
        design = null;
        testMode = true;
        if (current != null) {
            current.close();
        }
    }
}
