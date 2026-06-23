package com.trading.assistant.user.repository;

import com.trading.assistant.user.model.Bot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotRepository extends JpaRepository<Bot, Long> {
    List<Bot> findByUserId(Long userId);
    long countByUserId(Long userId);
    List<Bot> findByRunningTrue();
    List<Bot> findByEnabledTrueAndRunningTrue();

    @Query("SELECT b FROM Bot b JOIN FETCH b.user WHERE b.enabled = true AND b.running = true")
    List<Bot> findByEnabledTrueAndRunningTrueWithUser();
}
