package com.trading.assistant.user.api;

import com.trading.assistant.user.model.Bot;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.BotRepository;
import com.trading.assistant.user.repository.UserRepository;
import com.trading.assistant.user.service.EncryptionService;
import com.trading.assistant.user.service.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    @Autowired
    private BotRepository botRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private JwtUtil jwtUtil;

    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) return null;
        return jwtUtil.getUserId(token);
    }

    @GetMapping
    public ResponseEntity<?> listBots(@RequestHeader("Authorization") String authHeader) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Bot> bots = botRepository.findByUserId(userId);
        List<Map<String, Object>> response = bots.stream().map(b -> Map.<String, Object>of(
                "id", b.getId(),
                "name", b.getName(),
                "symbol", b.getSymbol(),
                "enabled", b.isEnabled(),
                "running", b.isRunning(),
                "maxCapitalUsd", b.getMaxCapitalUsd()
        )).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> createBot(@RequestHeader("Authorization") String authHeader,
                                         @RequestBody Map<String, String> request) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(404).body(Map.of("error", "User not found"));

        long botCount = botRepository.countByUserId(userId);
        if (botCount >= user.getMaxBots()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Plan limit reached",
                    "maxBots", user.getMaxBots(),
                    "currentBots", botCount
            ));
        }

        Bot bot = new Bot();
        bot.setUser(user);
        bot.setName(request.get("name"));
        bot.setSymbol(request.getOrDefault("symbol", "HYPEUSDT"));
        bot.setApiKeyEncrypted(encryptionService.encrypt(request.get("apiKey")));
        bot.setApiSecretEncrypted(encryptionService.encrypt(request.get("apiSecret")));
        bot.setMaxCapitalUsd(request.containsKey("maxCapitalUsd")
                ? new BigDecimal(request.get("maxCapitalUsd"))
                : BigDecimal.valueOf(user.getPlan().getMaxCapitalUsd()));
        botRepository.save(bot);

        return ResponseEntity.ok(Map.of(
                "id", bot.getId(),
                "name", bot.getName(),
                "symbol", bot.getSymbol(),
                "message", "Bot created successfully"
        ));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggleBot(@RequestHeader("Authorization") String authHeader,
                                       @PathVariable Long id) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Bot bot = botRepository.findById(id).orElse(null);
        if (bot == null || !bot.getUser().getId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Bot not found or access denied"));
        }

        bot.setRunning(!bot.isRunning());
        botRepository.save(bot);
        return ResponseEntity.ok(Map.of(
                "id", bot.getId(),
                "running", bot.isRunning(),
                "message", bot.isRunning() ? "Bot STARTED" : "Bot STOPPED"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBot(@RequestHeader("Authorization") String authHeader,
                                     @PathVariable Long id) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Bot bot = botRepository.findById(id).orElse(null);
        if (bot == null || !bot.getUser().getId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Bot not found or access denied"));
        }

        botRepository.delete(bot);
        return ResponseEntity.ok(Map.of("message", "Bot deleted successfully"));
    }
}
