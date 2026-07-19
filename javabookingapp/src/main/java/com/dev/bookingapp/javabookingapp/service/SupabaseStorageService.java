package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.PhotoStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class SupabaseStorageService {

    private static final long MAX_FILE_SIZE = 12 * 1024 * 1024;
    private static final int MAX_IMAGE_EDGE = 4032;
    private static final long MAX_IMAGE_PIXELS = 4032L * 3024L;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final String supabaseUrl;
    private final String serviceRoleKey;
    private final String bucket;
    private final HttpClient httpClient;

    public SupabaseStorageService(
            @Value("${app.supabase.url:}") String supabaseUrl,
            @Value("${app.supabase.service-role-key:}") String serviceRoleKey,
            @Value("${app.supabase.storage.bucket:business-photos}") String bucket) {
        this.supabaseUrl = stripTrailingSlash(supabaseUrl);
        this.serviceRoleKey = serviceRoleKey;
        this.bucket = bucket;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String uploadBusinessPhoto(UUID businessId, MultipartFile file) {
        validateConfigured();
        byte[] bytes = readAndValidate(file);
        String contentType = file.getContentType();
        String objectPath = businessId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);

        HttpRequest request = HttpRequest.newBuilder(objectUri(objectPath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .header("Content-Type", contentType)
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        send(request, "upload");
        return publicUrl(objectPath);
    }

    public void deleteIfManaged(UUID businessId, String photoUrl) {
        if (supabaseUrl.isBlank()) {
            return;
        }
        String expectedPrefix = publicUrl(businessId.toString()) + "/";
        if (!photoUrl.startsWith(expectedPrefix)) {
            return;
        }

        validateConfigured();
        String objectPath = businessId + "/" + photoUrl.substring(expectedPrefix.length());
        HttpRequest request = HttpRequest.newBuilder(objectUri(objectPath))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("apikey", serviceRoleKey)
                .DELETE()
                .build();

        send(request, "delete");
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a non-empty image file");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException("Each photo must be 12 MB or smaller");
        }

        String contentType = file.getContentType();
        if (!EXTENSIONS.containsKey(contentType)) {
            throw new BadRequestException("Photos must be JPEG, PNG, WebP, or GIF files");
        }

        try {
            byte[] bytes = file.getBytes();
            if (!hasValidSignature(bytes, contentType)) {
                throw new BadRequestException("The selected file is not a valid " + contentType + " image");
            }
            ImageDimensions dimensions = readDimensions(bytes, contentType);
            if (dimensions.width() > MAX_IMAGE_EDGE
                    || dimensions.height() > MAX_IMAGE_EDGE
                    || dimensions.pixelCount() > MAX_IMAGE_PIXELS) {
                throw new BadRequestException(
                        "Photos can be up to 4032px on either edge and 12.2 megapixels"
                );
            }
            return bytes;
        } catch (IOException ex) {
            throw new BadRequestException("The selected image could not be read");
        }
    }

    private ImageDimensions readDimensions(byte[] bytes, String contentType) {
        if ("image/webp".equals(contentType)) {
            return readWebpDimensions(bytes);
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BadRequestException("The selected image dimensions could not be read");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return validDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new BadRequestException("The selected image dimensions could not be read");
        }
    }

    private ImageDimensions readWebpDimensions(byte[] bytes) {
        if (bytes.length < 30) {
            throw new BadRequestException("The selected image dimensions could not be read");
        }

        String chunkType = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        return switch (chunkType) {
            case "VP8X" -> validDimensions(
                    1 + littleEndian24(bytes, 24),
                    1 + littleEndian24(bytes, 27)
            );
            case "VP8 " -> {
                if ((bytes[23] & 0xFF) != 0x9D
                        || (bytes[24] & 0xFF) != 0x01
                        || (bytes[25] & 0xFF) != 0x2A) {
                    throw new BadRequestException(
                            "The selected image dimensions could not be read"
                    );
                }
                yield validDimensions(
                        littleEndian16(bytes, 26) & 0x3FFF,
                        littleEndian16(bytes, 28) & 0x3FFF
                );
            }
            case "VP8L" -> {
                if ((bytes[20] & 0xFF) != 0x2F) {
                    throw new BadRequestException(
                            "The selected image dimensions could not be read"
                    );
                }
                int width = 1 + (bytes[21] & 0xFF)
                        + ((bytes[22] & 0x3F) << 8);
                int height = 1 + ((bytes[22] & 0xC0) >> 6)
                        + ((bytes[23] & 0xFF) << 2)
                        + ((bytes[24] & 0x0F) << 10);
                yield validDimensions(width, height);
            }
            default -> throw new BadRequestException(
                    "The selected image dimensions could not be read"
            );
        };
    }

    private int littleEndian16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private int littleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16);
    }

    private ImageDimensions validDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new BadRequestException("The selected image dimensions could not be read");
        }
        return new ImageDimensions(width, height);
    }

    private record ImageDimensions(int width, int height) {
        long pixelCount() {
            return (long) width * height;
        }
    }

    private boolean hasValidSignature(byte[] bytes, String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/gif" -> startsWith(bytes, 'G', 'I', 'F', '8')
                    && bytes.length >= 6
                    && (bytes[4] == '7' || bytes[4] == '9')
                    && bytes[5] == 'a';
            case "image/webp" -> startsWith(bytes, 'R', 'I', 'F', 'F')
                    && bytes.length >= 12
                    && bytes[8] == 'W'
                    && bytes[9] == 'E'
                    && bytes[10] == 'B'
                    && bytes[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private URI objectUri(String objectPath) {
        return URI.create(supabaseUrl + "/storage/v1/object/" + encode(bucket) + "/" + encodePath(objectPath));
    }

    private String publicUrl(String objectPath) {
        return supabaseUrl + "/storage/v1/object/public/" + encode(bucket) + "/" + encodePath(objectPath);
    }

    private String encodePath(String path) {
        return String.join("/", Arrays.stream(path.split("/")).map(this::encode).toList());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void send(HttpRequest request, String action) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Supabase Storage {} failed: status={}, body={}",
                        action, response.statusCode(), response.body());
                throw new PhotoStorageException(
                        "Supabase rejected the photo " + action
                                + ". Check the bucket and backend credentials."
                );
            }
        } catch (IOException ex) {
            throw new PhotoStorageException("Supabase photo storage is unavailable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PhotoStorageException("Photo storage request was interrupted", ex);
        }
    }

    private void validateConfigured() {
        if (supabaseUrl.isBlank() || serviceRoleKey.isBlank()) {
            throw new PhotoStorageException(
                    "Photo uploads are not configured on the backend. "
                            + "Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY."
            );
        }
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
