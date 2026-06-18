package com.trading.assistant.user.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bots")
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String symbol = "HYPEUSDT";

    @Column(nullable = false, length = 500)
    private String apiKeyEncrypted;

    @Column(nullable = false, length = 500)
    private String apiSecretEncrypted;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean running = true;

    @Column(precision = 20, scale = 8)
    private BigDecimal maxCapitalUsd;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Bot() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getApiSecretEncrypted() { return apiSecretEncrypted; }
    public void setApiSecretEncrypted(String apiSecretEncrypted) { this.apiSecretEncrypted = apiSecretEncrypted; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public BigDecimal getMaxCapitalUsd() { return maxCapitalUsd; }
    public void setMaxCapitalUsd(BigDecimal maxCapitalUsd) { this.maxCapitalUsd = maxCapitalUsd; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
