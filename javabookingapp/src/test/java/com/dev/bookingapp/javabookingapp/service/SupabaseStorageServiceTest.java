package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupabaseStorageServiceTest {

    private SupabaseStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new SupabaseStorageService(
                "https://example.supabase.co",
                "service-role-key",
                "business-photos"
        );
    }

    @Test
    void rejectsUnsupportedContentTypes() {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "notes.txt",
                "text/plain",
                "not an image".getBytes()
        );

        assertThrows(
                BadRequestException.class,
                () -> storageService.uploadBusinessPhoto(UUID.randomUUID(), file)
        );
    }

    @Test
    void rejectsFilesWhoseSignatureDoesNotMatchTheirContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "fake.jpg",
                "image/jpeg",
                "not a jpeg".getBytes()
        );

        assertThrows(
                BadRequestException.class,
                () -> storageService.uploadBusinessPhoto(UUID.randomUUID(), file)
        );
    }

    @Test
    void rejectsImagesAboveIphoneResolutionLimit() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "files",
                "too-wide.png",
                "image/png",
                pngHeader(4033, 3024)
        );

        BadRequestException error = assertThrows(
                BadRequestException.class,
                () -> storageService.uploadBusinessPhoto(UUID.randomUUID(), file)
        );

        assertEquals(
                "Photos can be up to 4032px on either edge and 12.2 megapixels",
                error.getMessage()
        );
    }

    @Test
    void distinguishesLegacyJwtAndNewSecretKeys() {
        assertTrue(SupabaseStorageService.isLegacyJwtKey(
                "eyJhbGciOiJIUzI1NiJ9.payload.signature"
        ));
        assertFalse(SupabaseStorageService.isLegacyJwtKey(
                "sb_secret_example"
        ));
    }

    private byte[] pngHeader(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.write(new byte[]{
                    (byte) 0x89, 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A
            });
            data.writeInt(13);
            byte[] typeAndData = new byte[]{
                    'I', 'H', 'D', 'R',
                    (byte) (width >>> 24), (byte) (width >>> 16),
                    (byte) (width >>> 8), (byte) width,
                    (byte) (height >>> 24), (byte) (height >>> 16),
                    (byte) (height >>> 8), (byte) height,
                    8, 2, 0, 0, 0
            };
            data.write(typeAndData);
            CRC32 crc = new CRC32();
            crc.update(typeAndData);
            data.writeInt((int) crc.getValue());
        }
        return output.toByteArray();
    }
}
