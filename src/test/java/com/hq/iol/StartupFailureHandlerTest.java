package com.hq.iol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 启动失败协调逻辑回归：日志之后先关闭启动画面、再提示错误；
 * 关闭启动画面本身失败也不得阻塞错误提示。
 */
class StartupFailureHandlerTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void closesSplashBeforeShowingError() {
        new StartupFailureHandler().handle(new IllegalStateException("图标缺失"), new StartupFailureHandler.FxActions() {
            @Override
            public void closeSplash() {
                calls.add("close");
            }

            @Override
            public void showError(String message) {
                calls.add("error:" + message);
            }
        });
        assertEquals(List.of("close", "error:图标缺失"), calls);
    }

    @Test
    void stillShowsErrorWhenSplashCloseFails() {
        new StartupFailureHandler().handle(new IllegalStateException("boom"), new StartupFailureHandler.FxActions() {
            @Override
            public void closeSplash() {
                throw new IllegalStateException("close-fail");
            }

            @Override
            public void showError(String message) {
                calls.add("error:" + message);
            }
        });
        assertEquals(List.of("error:boom"), calls, "关闭启动画面失败不得阻塞错误提示");
    }

    @Test
    void messageFallsBackToExceptionToString() {
        Exception failure = new Exception() {
            @Override
            public String getMessage() {
                return null;
            }
        };
        new StartupFailureHandler().handle(failure, new StartupFailureHandler.FxActions() {
            @Override
            public void closeSplash() {
            }

            @Override
            public void showError(String message) {
                calls.add("error:" + message);
            }
        });
        assertEquals(List.of("error:" + failure.toString()), calls);
    }
}
