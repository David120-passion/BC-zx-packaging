package com.hq.iol.device.laser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 从 classpath 提取 32 位桥接程序 {@code /laser-bridge/LaserBridge.exe} 到本地磁盘。
 *
 * <p>优先提取到 {@code data/laser-bridge/}（相对当前工作目录，与应用数据库同级），
 * 不可写时回退到系统临时目录。已存在且内容一致时直接复用，避免重复写盘或复用旧版本。</p>
 *
 * <p>exe 由 {@code src/native/laser-bridge/build-laser-bridge.ps1} 编译（csc /platform:x86），
 * 产物复制到 classpath 的 {@code laser-bridge/} 目录下（开发期即 {@code target/classes}）。
 * 资源不在 classpath 时抛出 {@link LaserException}，提示先构建桥接程序。</p>
 */
public final class LaserBridgeBinary {

    public static final String CLASSPATH_RESOURCE = "/laser-bridge/LaserBridge.exe";

    private LaserBridgeBinary() {
    }

    /**
     * 解析桥接 exe 的本地路径，不存在时从 classpath 提取。
     *
     * @throws LaserException classpath 中没有桥接资源，或提取失败
     */
    public static Path resolve() throws LaserException {
        return resolve(preferredTargetDir());
    }

    /** 提取到指定目录（父目录不存在时创建），资源缺失时报错。 */
    public static Path resolve(Path targetDir) throws LaserException {
        try (InputStream in = LaserBridgeBinary.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                throw new LaserException("BRIDGE_MISSING",
                        "classpath 中找不到 " + CLASSPATH_RESOURCE
                                + "；请先运行 src/native/laser-bridge/build-laser-bridge.ps1 构建 LaserBridge.exe"
                                + " 并将其放到 classpath 的 laser-bridge/ 目录");
            }
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve("LaserBridge.exe");
            byte[] data = in.readAllBytes();
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), data)) {
                return target;
            }
            Path tmp = targetDir.resolve("LaserBridge.exe.tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (LaserException e) {
            throw e;
        } catch (Exception e) {
            throw new LaserException("BRIDGE_EXTRACT_FAILED", "提取 LaserBridge.exe 失败: " + e.getMessage(), e);
        }
    }

    /** 首选提取目录：{@code data/laser-bridge}；不可写时回退到临时目录。 */
    static Path preferredTargetDir() {
        Path data = Paths.get("data", "laser-bridge");
        try {
            Files.createDirectories(data);
            if (Files.isWritable(data)) {
                return data;
            }
        } catch (Exception ignored) {
            // 回退到临时目录
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "BC-zx-packaging", "laser-bridge");
    }
}
