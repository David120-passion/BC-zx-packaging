package com.hq.iol.device.laser;

/**
 * Ezcad2 激光打标桥接失败。
 *
 * <p>{@link #getCode()} 为协议/桥接层错误码（例如 {@code SDK_ERROR}、
 * {@code NOT_INITIALIZED}、{@code INIT_TIMEOUT}、{@code TIMEOUT}、
 * {@code PROCESS_EXITED}），{@link #getSdkCode()} 保留 MarkEzd.dll 返回的
 * 原始错误码（0-21，见 Ezcad2 开发手册“返回值”一章），无 SDK 错误时为 {@code null}。</p>
 */
public class LaserException extends Exception {

    private final String code;
    private final Integer sdkCode;

    public LaserException(String code, String message) {
        super(message);
        this.code = code;
        this.sdkCode = null;
    }

    public LaserException(String code, String message, Integer sdkCode) {
        super(message);
        this.code = code;
        this.sdkCode = sdkCode;
    }

    public LaserException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.sdkCode = null;
    }

    public String getCode() {
        return code;
    }

    /** MarkEzd.dll 原始错误码（0-21），不涉及 SDK 调用时为 {@code null}。 */
    public Integer getSdkCode() {
        return sdkCode;
    }

    @Override
    public String toString() {
        String s = super.toString();
        return sdkCode != null ? s + " [sdkCode=" + sdkCode + "]" : s;
    }
}
