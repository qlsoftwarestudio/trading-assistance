package com.trading.assistant.notification;

import com.trading.assistant.portfolio.model.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

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
            logger.info("Telegram skipped: enabled={}, tokenEmpty={}, chatIdEmpty={}", enabled, botToken.isEmpty(), chatId.isEmpty());
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
            logger.info("Telegram alert skipped: enabled={}, tokenEmpty={}, chatIdEmpty={}", enabled, botToken.isEmpty(), chatId.isEmpty());
            return;
        }

        try {
            String formatted = String.format("🔔 <b>%s</b>\n\n%s", title, message);
            sendMessage(formatted);
        } catch (Exception e) {
            logger.error("Failed to send Telegram alert: {}", e.getMessage());
        }
    }

    private String formatTradeMessage(Trade trade, String type) {
        StringBuilder sb = new StringBuilder();

        if ("ENTRY".equals(type)) {
            String direction = "LONG".equals(trade.getAction()) ? "🟢" : "🔴";
            String label = "LONG".equals(trade.getAction()) ? "LONG ENTRY" : "SHORT ENTRY";
            sb.append(String.format("%s <b>%s EXECUTED</b>\n\n", direction, label));
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
            boolean isProfit = trade.getPnl().compareTo(BigDecimal.ZERO) > 0;
            String emoji = isProfit ? "🟢" : "🔴";
            sb.append(String.format("%s <b>TRADE CLOSED</b> %s\n\n", emoji, emoji));
            sb.append(String.format("Symbol: %s\n", trade.getSymbol()));
            sb.append(String.format("Action: %s\n", trade.getAction()));
            sb.append(String.format("Exit Price: $%s\n", trade.getExitPrice()));
            sb.append(String.format("Reason: %s\n", trade.getExitReason()));
            sb.append(String.format("\n<b>P&amp;L: $%s (%s%%)</b>\n",
                    trade.getPnl().setScale(2, RoundingMode.HALF_UP),
                    trade.getPnlPercent().setScale(2, RoundingMode.HALF_UP)));
            sb.append(String.format("Commission: $%s\n", trade.getCommission()));
            sb.append(String.format("\nTime: %s", trade.getExitTime()));
        }

        return sb.toString();
    }

    private double calculatePercent(BigDecimal entry, BigDecimal target) {
        return target.subtract(entry)
                .divide(entry, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private void sendMessage(String text) {
        String url = String.format(TELEGRAM_API_URL, botToken);

        logger.info("Sending Telegram message to chatId={}", chatId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "HTML");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(url, request, String.class);
            logger.info("Telegram API response: status={}, body={}", response.getStatusCode(), response.getBody());
        } catch (Exception e) {
            logger.error("Telegram API error: {}", e.getMessage());
        }
    }
}
