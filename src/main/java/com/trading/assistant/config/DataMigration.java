package com.trading.assistant.config;

import com.trading.assistant.portfolio.model.DailyMetrics;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.DailyMetricsRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import com.trading.assistant.user.model.Bot;
import com.trading.assistant.user.model.Plan;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.BotRepository;
import com.trading.assistant.user.repository.UserRepository;
import com.trading.assistant.user.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Component
public class DataMigration {

    private static final Logger logger = LoggerFactory.getLogger(DataMigration.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BotRepository botRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private DailyMetricsRepository dailyMetricsRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Value("${app.migration.enabled:true}")
    private boolean migrationEnabled;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostConstruct
    public void migrate() {
        if (!migrationEnabled) {
            logger.info("Migration disabled via app.migration.enabled=false. Skipping.");
            return;
        }
        // 1. Create default tenant/user if not exists
        User defaultUser = userRepository.findByEmail("admin@trading.local")
                .orElseGet(() -> {
                    User u = new User();
                    u.setEmail("admin@trading.local");
                    u.setPasswordHash(passwordEncoder.encode("admin123"));
                    u.setPlan(Plan.ENTERPRISE);
                    u.setActive(true);
                    userRepository.save(u);
                    logger.info("Created default user (tenant 1): id={}, email={}", u.getId(), u.getEmail());
                    return u;
                });

        Long userId = defaultUser.getId();

        // 2. Create default bot if none exists for this user
        List<Bot> userBots = botRepository.findByUserId(userId);
        if (userBots.isEmpty()) {
            Bot bot = new Bot();
            bot.setUser(defaultUser);
            bot.setName("Default Bot");
            bot.setSymbol("HYPEUSDT");
            bot.setApiKeyEncrypted(encryptionService.encrypt("placeholder"));
            bot.setApiSecretEncrypted(encryptionService.encrypt("placeholder"));
            bot.setEnabled(true);
            bot.setRunning(true);
            botRepository.save(bot);
            logger.info("Created default bot for user {}", userId);
        }

        // 3. Migrate trades without user_id
        List<Trade> orphanTrades = tradeRepository.findByUserIdIsNull();
        if (!orphanTrades.isEmpty()) {
            for (Trade t : orphanTrades) {
                t.setUserId(userId);
            }
            tradeRepository.saveAll(orphanTrades);
            logger.info("Migrated {} orphan trades to user_id={}", orphanTrades.size(), userId);
        }

        // 4. Migrate signals without user_id
        List<Signal> orphanSignals = signalRepository.findByUserIdIsNull();
        if (!orphanSignals.isEmpty()) {
            for (Signal s : orphanSignals) {
                s.setUserId(userId);
            }
            signalRepository.saveAll(orphanSignals);
            logger.info("Migrated {} orphan signals to user_id={}", orphanSignals.size(), userId);
        }

        // 5. Migrate daily_metrics without user_id
        List<DailyMetrics> orphanMetrics = dailyMetricsRepository.findByUserIdIsNull();
        if (!orphanMetrics.isEmpty()) {
            for (DailyMetrics m : orphanMetrics) {
                m.setUserId(userId);
            }
            dailyMetricsRepository.saveAll(orphanMetrics);
            logger.info("Migrated {} orphan daily_metrics to user_id={}", orphanMetrics.size(), userId);
        }

        logger.info("Data migration complete. Default tenant: user_id={}", userId);
    }
}
