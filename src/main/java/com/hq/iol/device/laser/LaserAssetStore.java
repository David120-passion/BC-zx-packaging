package com.hq.iol.device.laser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;

final class LaserAssetStore {

    private LaserAssetStore() {
    }

    static Path materialize(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0 || !dataUri.substring(0, comma).contains(";base64")) {
                throw new IllegalArgumentException("图片 Base64 格式不正确");
            }
            String mime = dataUri.substring(5, dataUri.indexOf(';'));
            String extension = switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/bmp" -> "bmp";
                case "image/tiff" -> "tiff";
                default -> throw new IllegalArgumentException("激光 SDK 不支持图片格式: " + mime);
            };
            byte[] bytes = Base64.getDecoder().decode(dataUri.substring(comma + 1));
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("图片内容为空或超过 5 MB");
            }
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
            Path directory = writableDirectory();
            Path target = directory.resolve(hash + "." + extension);
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
                return target;
            }
            Path temporary = Files.createTempFile(directory, "image-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法准备激光图片文件: " + e.getMessage(), e);
        }
    }

    private static Path writableDirectory() throws IOException {
        Path preferred = Path.of("data", "laser-assets");
        try {
            Files.createDirectories(preferred);
            if (Files.isWritable(preferred)) {
                return preferred.toAbsolutePath();
            }
        } catch (IOException | SecurityException ignored) {
            // 使用系统临时目录作为不可写安装目录的回退。
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"),
                "BC-zx-packaging", "laser-assets");
        Files.createDirectories(fallback);
        return fallback.toAbsolutePath();
    }
}
