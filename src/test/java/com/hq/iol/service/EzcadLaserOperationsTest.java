package com.hq.iol.service;

import com.hq.iol.device.laser.EzcadLaserDriver;
import com.hq.iol.device.laser.LaserDesign;
import com.hq.iol.device.laser.LaserDesignCompiler;
import com.hq.iol.device.laser.LaserException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EzcadLaserOperationsTest {

    @Test
    void 动态设计直接交给桥接而不加载ezd() throws Exception {
        EzcadLaserDriver driver = mock(EzcadLaserDriver.class);
        EzcadLaserOperations operations = new EzcadLaserOperations(driver);
        LaserDesign design = LaserDesignCompiler.compile("{\"widthMm\":100,\"heightMm\":80,"
                + "\"elements\":[{\"type\":\"text\",\"id\":\"text_1\",\"x\":5,\"y\":5,"
                + "\"widthMm\":30,\"heightMm\":6,\"content\":\"ABC\"}]}", Map.of());
        operations.buildDesign(design);
        verify(driver).buildDesign(design);
        operations.close();
        verify(driver).close();
    }

    @Test
    void 缺少控制设备时保留SDK错误码并提示检查加密狗() throws Exception {
        EzcadLaserDriver driver = mock(EzcadLaserDriver.class);
        doThrow(new LaserException("SDK_ERROR", "no device", 4))
                .when(driver).initialize(Path.of("sdk"), true);
        EzcadLaserOperations operations = new EzcadLaserOperations(driver);
        assertThatThrownBy(() -> operations.initialize(Path.of("sdk"), true))
                .hasMessageContaining("加密狗")
                .hasMessageContaining("4")
                .hasCauseInstanceOf(LaserException.class);
    }

    @Test
    void 对象创建失败保留原始错误码() throws Exception {
        EzcadLaserDriver driver = mock(EzcadLaserDriver.class);
        LaserDesign design = LaserDesignCompiler.compile("{\"widthMm\":100,\"heightMm\":80,"
                + "\"elements\":[{\"type\":\"text\",\"id\":\"text_1\",\"x\":5,\"y\":5,"
                + "\"widthMm\":30,\"heightMm\":6,\"content\":\"ABC\"}]}", Map.of());
        doThrow(new LaserException("SDK_ERROR", "entity missing", 18)).when(driver).buildDesign(design);
        EzcadLaserOperations operations = new EzcadLaserOperations(driver);
        assertThatThrownBy(() -> operations.buildDesign(design)).hasMessageContaining("对象名称");
    }

    @Test
    void 停止失败会向界面报告而不是静默忽略() throws Exception {
        EzcadLaserDriver driver = mock(EzcadLaserDriver.class);
        doThrow(new LaserException("PROCESS_EXITED", "bridge closed")).when(driver).stopMarking();
        EzcadLaserOperations operations = new EzcadLaserOperations(driver);
        assertThatThrownBy(operations::stopMarking)
                .isInstanceOf(LaserException.class)
                .hasMessageContaining("bridge closed");
    }
}
