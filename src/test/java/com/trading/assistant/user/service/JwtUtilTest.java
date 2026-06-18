package com.trading.assistant.user.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "test-secret-key-for-unit-testing-1234567890");
        ReflectionTestUtils.setField(jwtUtil, "jwtExpiration", 86400000L);
    }

    @Test
    void generateToken_containsCorrectUserId() {
        String token = jwtUtil.generateToken(42L, "user@test.com");
        assertNotNull(token);
        assertEquals(42L, jwtUtil.getUserId(token));
    }

    @Test
    void generateToken_containsEmailClaim() {
        String token = jwtUtil.generateToken(1L, "admin@trading.local");
        Claims claims = jwtUtil.parseToken(token);
        assertEquals("admin@trading.local", claims.get("email", String.class));
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = jwtUtil.generateToken(1L, "test@test.com");
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(jwtUtil.validateToken("not-a-valid-token"));
    }

    @Test
    void validateToken_tamperedToken_returnsFalse() {
        String token = jwtUtil.generateToken(1L, "test@test.com");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertFalse(jwtUtil.validateToken(tampered));
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtUtil, "jwtExpiration", -1000L);
        String token = jwtUtil.generateToken(1L, "test@test.com");
        assertFalse(jwtUtil.validateToken(token));
    }

    @Test
    void differentUsers_getDifferentTokens_withCorrectIds() {
        String token1 = jwtUtil.generateToken(1L, "user1@test.com");
        String token2 = jwtUtil.generateToken(2L, "user2@test.com");

        assertNotEquals(token1, token2);
        assertEquals(1L, jwtUtil.getUserId(token1));
        assertEquals(2L, jwtUtil.getUserId(token2));
    }

    @Test
    void getUserId_returnsLongNotString() {
        String token = jwtUtil.generateToken(99L, "test@test.com");
        Long userId = jwtUtil.getUserId(token);
        assertInstanceOf(Long.class, userId);
        assertEquals(99L, userId);
    }
}
