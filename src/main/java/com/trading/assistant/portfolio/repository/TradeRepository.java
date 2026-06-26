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

    List<Trade> findBySymbolAndStatusOrderByEntryTimeDesc(String symbol, String status);

    Page<Trade> findAllByOrderByEntryTimeDesc(Pageable pageable);

    Page<Trade> findBySymbolOrderByEntryTimeDesc(String symbol, Pageable pageable);

    Optional<Trade> findFirstByStatusOrderByEntryTimeDesc(String status);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED'")
    BigDecimal calculateTotalPnl();

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.symbol = :symbol")
    BigDecimal calculateTotalPnlBySymbol(@Param("symbol") String symbol);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0")
    Long countWinningTrades();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.symbol = :symbol")
    Long countWinningTradesBySymbol(@Param("symbol") String symbol);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl <= 0")
    Long countLosingTrades();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl <= 0 AND t.symbol = :symbol")
    Long countLosingTradesBySymbol(@Param("symbol") String symbol);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0")
    BigDecimal calculateGrossProfit();

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.symbol = :symbol")
    BigDecimal calculateGrossProfitBySymbol(@Param("symbol") String symbol);

    @Query("SELECT COALESCE(SUM(ABS(t.pnl)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl < 0")
    BigDecimal calculateGrossLoss();

    @Query("SELECT COALESCE(SUM(ABS(t.pnl)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl < 0 AND t.symbol = :symbol")
    BigDecimal calculateGrossLossBySymbol(@Param("symbol") String symbol);

    @Query("SELECT t FROM Trade t WHERE t.status = 'OPEN' AND t.entryTime < :cutoffTime")
    List<Trade> findOldOpenTrades(LocalDateTime cutoffTime);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.exitTime >= :startOfDay")
    BigDecimal calculateDailyPnl(@Param("startOfDay") LocalDateTime startOfDay);

    @Query("SELECT t FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl IS NOT NULL ORDER BY t.exitTime ASC")
    List<Trade> findClosedTradesOrderByExitTimeAsc();

    long countByStatus(String status);

    long countBySymbolAndStatus(String symbol, String status);

    List<Trade> findByUserIdIsNull();

    // Multi-tenant queries
    Page<Trade> findByUserIdOrderByEntryTimeDesc(Long userId, Pageable pageable);

    Page<Trade> findByUserIdAndSymbolOrderByEntryTimeDesc(Long userId, String symbol, Pageable pageable);

    List<Trade> findByUserIdAndStatusOrderByEntryTimeDesc(Long userId, String status);

    List<Trade> findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(Long userId, String symbol, String status);

    long countByUserIdAndStatus(Long userId, String status);

    long countByUserIdAndSymbolAndStatus(Long userId, String symbol, String status);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.userId = :userId")
    BigDecimal calculateTotalPnlByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.symbol = :symbol AND t.userId = :userId")
    BigDecimal calculateTotalPnlBySymbolAndUserId(@Param("symbol") String symbol, @Param("userId") Long userId);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.userId = :userId")
    Long countWinningTradesByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl <= 0 AND t.userId = :userId")
    Long countLosingTradesByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.userId = :userId")
    BigDecimal calculateGrossProfitByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(ABS(t.pnl)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl < 0 AND t.userId = :userId")
    BigDecimal calculateGrossLossByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(t.investedAmount), 0) FROM Trade t WHERE t.status = 'OPEN' AND t.userId = :userId")
    BigDecimal calculateTotalInvestedOpenByUserId(@Param("userId") Long userId);

    // A/B symbol comparison queries
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.symbol = :symbol AND t.userId = :userId")
    Long countWinningTradesBySymbolAndUserId(@Param("symbol") String symbol, @Param("userId") Long userId);

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl <= 0 AND t.symbol = :symbol AND t.userId = :userId")
    Long countLosingTradesBySymbolAndUserId(@Param("symbol") String symbol, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl > 0 AND t.symbol = :symbol AND t.userId = :userId")
    BigDecimal calculateGrossProfitBySymbolAndUserId(@Param("symbol") String symbol, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(ABS(t.pnl)), 0) FROM Trade t WHERE t.status = 'CLOSED' AND t.pnl < 0 AND t.symbol = :symbol AND t.userId = :userId")
    BigDecimal calculateGrossLossBySymbolAndUserId(@Param("symbol") String symbol, @Param("userId") Long userId);
}
