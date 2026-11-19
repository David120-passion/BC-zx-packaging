package com.hq.iol.device.laser;

import java.util.Objects;

/**
 * Ezcad2 模板中的一个文本对象（加载 .ezd 后由桥接进程枚举得到）。
 *
 * @param name 对象名称（模板内唯一，打标前用它定位要替换的文本）
 * @param text 当前文本内容；非文本对象为 {@code null}
 * @param order 对象在数据库中的序号（枚举顺序）
 */
public record LaserEntity(String name, String text, int order) {

    public LaserEntity {
        Objects.requireNonNull(name, "name");
    }

    /** 是否为文本对象（有可读文本内容）。 */
    public boolean isText() {
        return text != null;
    }
}
