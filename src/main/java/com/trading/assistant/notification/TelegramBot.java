package com.trading.assistant.notification;

import com.trading.assistant.portfolio.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class TelegramBot {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBot.class);

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.bot.chat-id:}")
    private String chatId;

    @Value("${telegram.bot.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendMessage";

    /**
     * Send trade notification
     */
    public void sendTradeNotification(Trade trade, String type) {
        if (!enabled || botToken.isEmpty() || chatId.isEmpty()) {
            logger.debug("Telegram notifications disabled or not configured");
            return;
        }

        try {
            String message = formatTradeMessage(trade, type);
            sendMessage(message);
        } catch (Exception e) {
            logger.error("Failed to send Telegram notification: {}", e.getMessage());
        }
    }

    /**
     * Send generic alert
     */
    public void sendAlert(String title, String message) {
        if (!enabled || botToken.isEmpty() || chatId.isEmpty()) {
            return;
        }

        try {
            String formatted = String.format("🔔 *%s*\n\n%s", title, message);
            sendMessage(formatted);
        } catch (Exception e) {
            logger.error("Failed to send Telegram alert: {}", e.getMessage());
        }
    }

    private String formatTradeMessage(Trade trade, String type) {
        StringBuilder sb = new StringBuilder();

        if ("ENTRY".equals(type)) {
            sb.append("🟢 *LONG ENTRY EXECUTED*\n\n");
            sb.append(String.format("Symbol: %s\n", trade.getSymbol()));
            sb.append(String.format("Entry Price: $%s\n", trade.getEntryPrice()));
            sb.append(String.format("Quantity: %s\n", trade.getQuantity()));
            sb.append(String.format("Invested: $%s\n", trade.getInvestedAmount()));
            sb.append(String.format("Stop Loss: $%s (%.1f%%)\n", 
                    trade.getStopLoss(), calculatePercent(trade.getEntryPrice(), trade.getStopLoss())));
            sb.append(String.format("Take Profit: $%s (+%.1f%%)\n", 
                    trade.getTakeProfit(), calculatePercent(trade.getEntryPrice(), trade.getTakeProfit())));
            sb.append(String.format("\nTime: %s", trade.getEntryTime()));

        } else if ("EXIT".equals(type)) {
            String emoji = trade.getPnl().compareTo(BigDecimal.ZERO) > 0 ? "🟢" : "🔴";
            sb.append(String.format("%s *TRADE CLOSED* %s\n\n", emoji, emoji));
            sb.append(String.format("Symbol: %s\n", trade.getSymbol()));
            sb.append(String.format("Exit Price: $%s\n", trade.getExitPrice()));
            sb.append(String.format("Reason: %s\n", trade.getExitReason()));
            sb.append(String.format("\n*P&L: $%s (%s%%)*\n", 
                    trade.getPnl(), trade.getPnlPercent()));
            sb.append(String.format("Commission: $%s\n", trade.getCommission()));
            sb.append(String.format("\nTime: %s", trade.getExitTime()));
        }

        return sb.toString();
    }

    private double calculatePercent(BigDecimal entry, BigDecimal target) {
        return target.subtract(entry)
                .divide(entry, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private void sendMessage(String text) {
        String url = String.format(TELEGRAM_API_URL, botToken);

        // Simple POST to Telegram API
        // In production, use a proper Telegram Bot library
        logger.info("Telegram message: {}", text);

        // Uncomment when bot token is configured:
        /*
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, request, String.class);
        } catch (Exception e) {
            logger.error("Telegram API error: {}", e.getMessage());
        }
        */
    }
}
