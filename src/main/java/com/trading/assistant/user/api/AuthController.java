package com.trading.assistant.user.api;

import com.trading.assistant.user.model.Plan;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.UserRepository;
import com.trading.assistant.user.service.EncryptionService;
import com.trading.assistant.user.service.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${app.registration.invite-code:}")
    private String inviteCode;

    @Value("${app.registration.enabled:true}")
    private boolean registrationEnabled;

    @Value("${app.rate-limit.login-max-attempts:5}")
    private int maxLoginAttempts;

    @Value("${app.rate-limit.login-window-seconds:60}")
    private int loginWindowSeconds;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Simple in-memory rate limiting: IP -> list of attempt timestamps
    private final Map<String, LinkedList<Long>> loginAttempts = new ConcurrentHashMap<>();

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        if (!registrationEnabled) {
            return ResponseEntity.status(403).body(Map.of("error", "Registration is disabled"));
        }

        if (!inviteCode.isBlank()) {
            String providedCode = request.get("inviteCode");
            if (providedCode == null || !inviteCode.equals(providedCode)) {
                return ResponseEntity.status(403).body(Map.of("error", "Invalid invite code"));
            }
        }

        String email = request.get("email");
        String password = request.get("password");
        Plan plan = Plan.valueOf(request.getOrDefault("plan", "FREE"));

        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
        }

        User user = new User(email, passwordEncoder.encode(password), plan);
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", user.getId(),
                "email", user.getEmail(),
                "role", user.getPlan().name(),
                "plan", user.getPlan().name(),
                "maxBots", user.getMaxBots()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request,
                                                      HttpServletRequest httpRequest) {
        String email = request.get("email");
        String password = request.get("password");
        String clientIp = extractClientIp(httpRequest);

        if (isRateLimited(clientIp)) {
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Too many login attempts. Please wait " + loginWindowSeconds + " seconds."
            ));
        }

        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginAttempt(clientIp);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        // Clear attempts on successful login
        loginAttempts.remove(clientIp);

        if (user.isTwoFactorEnabled()) {
            String tempToken = jwtUtil.generateTempToken(user.getId());
            Map<String, Object> resp = new HashMap<>();
            resp.put("twoFactorRequired", true);
            resp.put("tempToken", tempToken);
            return ResponseEntity.ok(resp);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", user.getId(),
                "email", user.getEmail(),
                "role", user.getPlan().name(),
                "plan", user.getPlan().name(),
                "maxBots", user.getMaxBots(),
                "twoFactorEnabled", false
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwarded = request.getHeader("X-Forwarded-For");
        if (xForwarded != null && !xForwarded.isBlank()) return xForwarded.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    private boolean isRateLimited(String ip) {
        LinkedList<Long> attempts = loginAttempts.get(ip);
        if (attempts == null) return false;
        long now = Instant.now().getEpochSecond();
        long windowStart = now - loginWindowSeconds;
        // Clean expired entries and count
        while (!attempts.isEmpty() && attempts.peekFirst() < windowStart) {
            attempts.pollFirst();
        }
        return attempts.size() >= maxLoginAttempts;
    }

    private void recordLoginAttempt(String ip) {
        loginAttempts.computeIfAbsent(ip, k -> new LinkedList<>()).addLast(Instant.now().getEpochSecond());
    }
}
