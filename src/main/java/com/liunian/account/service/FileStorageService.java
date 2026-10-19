package com.liunian.account.service;

import com.liunian.account.common.BizException;
import com.liunian.account.common.ErrorCode;
import com.liunian.account.common.TimeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘文件存储（记账凭证图片）。
 *
 * <p>落盘路径：{@code app.upload.dir/yyyy/MM/<uuid>.<ext>}；返回的 url 为相对路径
 * （默认 {@code /api/files/...}），由 {@code WebMvcConfig} 映射为静态资源。
 * 文件名使用 UUID，避免目录穿越与可猜测 URL；扩展名与文件头（magic bytes）双校验。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "heic", "heif");

    private final Path root;
    private final String publicPrefix;
    private final String publicBaseUrl;
    private final long maxBytes;

    public FileStorageService(@Value("${app.upload.dir:./data/upload}") String dir,
                              @Value("${app.upload.public-prefix:/api/files}") String prefix,
                              @Value("${app.public-base-url:}") String baseUrl,
                              @Value("${app.upload.max-size-mb:5}") long maxSizeMb) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + root, e);
        }
        this.publicPrefix = normalizePrefix(prefix);
        this.publicBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.maxBytes = Math.max(1, maxSizeMb) * 1024 * 1024;
        log.info("[UPLOAD] 图片根目录={} 公开前缀={} 单文件上限={}MB", root, publicPrefix, maxSizeMb);
    }

    public Path root() {
        return root;
    }

    public Map<String, Object> store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        if (file.getSize() > maxBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 jpg/jpeg/png/webp/heic 图片");
        }
        if (!looksLikeImage(readHead(file), ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件内容不是有效图片");
        }

        LocalDate today = TimeSupport.today();
        String key = String.format("%04d/%02d/%s.%s", today.getYear(), today.getMonthValue(),
                UUID.randomUUID().toString().replace("-", ""), ext);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法文件路径");
        }
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("[UPLOAD] 保存失败 key={}", key, e);
            throw new BizException(ErrorCode.INTERNAL, "保存图片失败");
        }

        String url = publicPrefix + "/" + key;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("url", url);
        result.put("fullUrl", publicBaseUrl.isBlank() ? url : trimTrailingSlash(publicBaseUrl) + url);
        result.put("size", file.getSize());
        result.put("contentType", file.getContentType() == null ? "" : file.getContentType());
        log.info("[UPLOAD] 已保存 {} ({} bytes)", key, file.getSize());
        return result;
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[16];
            int read = in.read(buf);
            if (read <= 0) {
                return new byte[0];
            }
            byte[] head = new byte[read];
            System.arraycopy(buf, 0, head, 0, read);
            return head;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }
    }

    /** magic bytes 校验，防止把非图片文件改扩展名后上传。 */
    private boolean looksLikeImage(byte[] h, String ext) {
        if (h.length < 12) {
            return false;
        }
        boolean jpeg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
        boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean webp = ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WEBP");
        boolean heic = ascii(h, 4, 4).equals("ftyp")
                && Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1").contains(ascii(h, 8, 4));
        return switch (ext) {
            case "jpg", "jpeg" -> jpeg;
            case "png" -> png;
            case "webp" -> webp;
            case "heic", "heif" -> heic;
            default -> false;
        };
    }

    private String ascii(byte[] b, int offset, int len) {
        if (b.length < offset + len) {
            return "";
        }
        return new String(b, offset, len, StandardCharsets.US_ASCII);
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizePrefix(String prefix) {
        String p = (prefix == null || prefix.isBlank()) ? "/api/files" : prefix.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
