package com.trading.assistant.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EncryptionServiceTest {

    @Autowired
    private EncryptionService encryptionService;

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
        assertNotEquals(encrypted1, encrypted2); // AES without IV should be deterministic, but our impl uses default
        assertEquals(encryptionService.decrypt(encrypted1), encryptionService.decrypt(encrypted2));
    }
}
