package com.trading.assistant.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService();
        ReflectionTestUtils.setField(encryptionService, "secretKey", "test-encryption-key-32bytes!!");
    }

    @Test
    void testEncryptDecrypt() {
        String original = "my-secret-api-key-12345";
        String encrypted = encryptionService.encrypt(original);
        assertNotEquals(original, encrypted);

        String decrypted = encryptionService.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void testEncryptDeterministicWithSameKey() {
        String original = "test-key";
        String encrypted1 = encryptionService.encrypt(original);
        String encrypted2 = encryptionService.encrypt(original);
        // AES/ECB is deterministic: same key + same plaintext = same ciphertext
        assertEquals(encrypted1, encrypted2);
        assertEquals(original, encryptionService.decrypt(encrypted1));
        assertEquals(original, encryptionService.decrypt(encrypted2));
    }
}
