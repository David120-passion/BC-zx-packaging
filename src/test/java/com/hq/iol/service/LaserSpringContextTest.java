package com.hq.iol.service;

import com.hq.iol.FxTestToolkit;
import com.hq.iol.SpringMain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SpringMain.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:sqlite:target/laser-spring-context-test.db")
class LaserSpringContextTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxTestToolkit.start();
    }

    @Autowired
    private LaserService laserService;

    @Autowired
    private ObjectProvider<LaserOperations> operations;

    @Autowired
    private com.hq.iol.ui.LaserDesignPanel laserDesignPanel;

    @Test
    void 打标服务可通过真实Spring容器创建且桥接按连接独立创建() {
        assertThat(laserService).isNotNull();
        assertThat(laserService.sdkDirectory().resolve("MarkEzd.dll")).exists();
        assertThat(operations.getObject()).isNotSameAs(operations.getObject());
        assertThat(laserService.isMarking()).isFalse();
        assertThat(laserDesignPanel).isNotNull();
    }
}
