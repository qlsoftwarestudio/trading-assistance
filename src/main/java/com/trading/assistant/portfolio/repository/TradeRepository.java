package com.trading.assistant.portfolio.repository;

import com.trading.assistant.portfolio.model.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByStatusOrderByEntryTimeDesc(String status);

    Page<Trade> findAllByOrderByEntryTimeDesc(Pageable pageable);

    Optional<Trade> findFirstByStatusOrderByEntryTimeDesc(String status);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED'")
    BigDecimal calculateTotalPnl();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0")
    Long countWinningTrades();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl <= 0")
    Long countLosingTrades();

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0")
    BigDecimal calculateGrossProfit();

    @Query("SELECT COALESCE(SUM(ABS(t.pnl)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl < 0")
    BigDecimal calculateGrossLoss();

    @Query("SELECT t FROM Trade t WHERE t.status = 'OPEN' AND t.entryTime < :cutoffTime")
    List<Trade> findOldOpenTrades(LocalDateTime cutoffTime);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :startOfDay")
    BigDecimal calculateDailyPnl(@Param("startOfDay") LocalDateTime startOfDay);

    long countByStatus(String status);
}
