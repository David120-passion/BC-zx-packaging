package com.hq.iol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动失败协调：记录日志 → 尽力关闭启动画面 → 交由 FX 层提示错误并退出。
 * 关闭启动画面本身失败不得阻塞后续的错误提示与退出。
 */
final class StartupFailureHandler {

    /** 由 FX 层提供的动作：关闭启动画面、提示错误并退出。 */
    interface FxActions {
        void closeSplash();

        void showError(String message);
    }

    private static final Logger log = LoggerFactory.getLogger(StartupFailureHandler.class);

    void handle(Exception e, FxActions actions) {
        log.error("应用启动失败", e);
        try {
            actions.closeSplash();
        } catch (Exception closeFailure) {
            log.warn("关闭启动画面失败", closeFailure);
        }
        actions.showError(e.getMessage() != null ? e.getMessage() : e.toString());
    }
}
