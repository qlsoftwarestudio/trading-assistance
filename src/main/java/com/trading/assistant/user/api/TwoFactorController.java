package com.trading.assistant.user.api;

import com.trading.assistant.user.service.JwtUtil;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.UserRepository;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 2FA TOTP endpoints (Google Authenticator compatible).
 *
 * Flow:
 *  1. POST /api/auth/2fa/setup         → returns otpauthUrl + base32 secret
 *  2. POST /api/auth/2fa/enable        → validates first OTP, persists secret
 *  3. POST /api/auth/2fa/disable       → validates OTP, removes secret
 *  4. POST /api/auth/2fa/validate      → used during login when 2FA is required
 *
 * Telegram chatId (also here for convenience):
 *  5. PUT  /api/users/me/telegram      → sets user's personal Telegram chat ID
 */
@RestController
public class TwoFactorController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        try {
            return jwtUtil.getUserId(token);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Step 3 (login flow): validate OTP from temp token issued during login when 2FA is required.
     * tempToken is a short-lived token with claim twoFaPending=true.
     * Returns full JWT on success.
     */
    @PostMapping("/api/auth/2fa/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, String> body) {
        String tempToken = body.get("tempToken");
        String otpStr = body.get("otp");
        if (tempToken == null || otpStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "tempToken and otp are required"));

        Long userId;
        try {
            if (!Boolean.TRUE.equals(jwtUtil.parseToken(tempToken).get("twoFaPending", Boolean.class)))
                return ResponseEntity.status(400).body(Map.of("error", "Invalid temp token"));
            userId = Long.valueOf(jwtUtil.parseToken(tempToken).getSubject());
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Temp token invalid or expired"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        int otp;
        try {
            otp = Integer.parseInt(otpStr.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "OTP must be a 6-digit number"));
        }

        boolean valid = gAuth.authorize(user.getTwoFactorSecret(), otp);
        if (!valid) return ResponseEntity.status(400).body(Map.of("error", "Invalid OTP"));

        String fullToken = jwtUtil.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
                "token", fullToken,
                "userId", user.getId(),
                "email", user.getEmail(),
                "plan", user.getPlan().name(),
                "maxBots", user.getMaxBots()
        ));
    }

    /**
     * Step 1: Generate a new TOTP secret and return QR code URL.
     * The secret is NOT saved yet — user must confirm with a valid OTP first (via /enable).
     */
    @PostMapping("/api/auth/2fa/setup")
    public ResponseEntity<?> setup(@RequestHeader("Authorization") String authHeader) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        GoogleAuthenticatorKey credentials = gAuth.createCredentials();
        String secret = credentials.getKey();

        String issuer = "TradingAssistant";
        String accountName = user.getEmail();
        String otpauthUrl = GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL(issuer, accountName, credentials);

        return ResponseEntity.ok(Map.of(
                "secret", secret,
                "otpauthUrl", otpauthUrl,
                "instructions", "Scan the QR code in Google Authenticator or Authy, then call /enable with the 6-digit OTP."
        ));
    }

    /**
     * Step 2: Confirm 2FA setup by validating the first OTP. Saves the secret.
     */
    @PostMapping("/api/auth/2fa/enable")
    public ResponseEntity<?> enable(@RequestHeader("Authorization") String authHeader,
                                    @RequestBody Map<String, String> body) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String secret = body.get("secret");
        String otpStr = body.get("otp");
        if (secret == null || otpStr == null)
            return ResponseEntity.badRequest().body(Map.of("error", "secret and otp are required"));

        int otp;
        try {
            otp = Integer.parseInt(otpStr.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "OTP must be a 6-digit number"));
        }

        boolean valid = gAuth.authorize(secret, otp);
        if (!valid) return ResponseEntity.status(400).body(Map.of("error", "Invalid OTP — please try again"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        user.setTwoFactorSecret(secret);
        user.setTwoFactorEnabled(true);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "2FA enabled successfully"));
    }

    /**
     * Disable 2FA — requires a valid OTP to confirm intent.
     */
    @PostMapping("/api/auth/2fa/disable")
    public ResponseEntity<?> disable(@RequestHeader("Authorization") String authHeader,
                                     @RequestBody Map<String, String> body) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        if (!user.isTwoFactorEnabled()) return ResponseEntity.badRequest().body(Map.of("error", "2FA is not enabled"));

        String otpStr = body.get("otp");
        if (otpStr == null) return ResponseEntity.badRequest().body(Map.of("error", "otp is required"));

        int otp;
        try {
            otp = Integer.parseInt(otpStr.trim());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "OTP must be a 6-digit number"));
        }

        boolean valid = gAuth.authorize(user.getTwoFactorSecret(), otp);
        if (!valid) return ResponseEntity.status(400).body(Map.of("error", "Invalid OTP"));

        user.setTwoFactorSecret(null);
        user.setTwoFactorEnabled(false);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "2FA disabled successfully"));
    }

    /**
     * 2FA status for the authenticated user.
     */
    @GetMapping("/api/auth/2fa/status")
    public ResponseEntity<?> status(@RequestHeader("Authorization") String authHeader) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        return ResponseEntity.ok(Map.of("twoFactorEnabled", user.isTwoFactorEnabled()));
    }

    /**
     * Set the user's personal Telegram chatId for trade notifications.
     * To find your chatId: message @userinfobot on Telegram.
     */
    @PutMapping("/api/users/me/telegram")
    public ResponseEntity<?> setTelegramChatId(@RequestHeader("Authorization") String authHeader,
                                               @RequestBody Map<String, String> body) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        String newChatId = body.get("chatId");
        user.setTelegramChatId(newChatId);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "message", "Telegram chatId updated",
                "chatId", newChatId != null ? newChatId : ""
        ));
    }
}
