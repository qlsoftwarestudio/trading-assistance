package com.trading.assistant.portfolio.repository;

import com.trading.assistant.portfolio.model.TradeJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeJournalRepository extends JpaRepository<TradeJournal, Long> {

    List<TradeJournal> findByTradeId(Long tradeId);

    @Query("SELECT j FROM TradeJournal j WHERE j.setupType = :setupType AND j.action = :action AND j.createdAt >= :since ORDER BY j.createdAt DESC")
    List<TradeJournal> findRecentBySetupTypeAndAction(@Param("setupType") String setupType,
                                                       @Param("action") String action,
                                                       @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(j) FROM TradeJournal j WHERE j.setupType = :setupType AND j.action = :action AND j.createdAt >= :since AND j.pnl > 0")
    Long countWinsBySetupTypeAndAction(@Param("setupType") String setupType,
                                        @Param("action") String action,
                                        @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(j) FROM TradeJournal j WHERE j.setupType = :setupType AND j.action = :action AND j.createdAt >= :since")
    Long countTotalBySetupTypeAndAction(@Param("setupType") String setupType,
                                         @Param("action") String action,
                                         @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(AVG(j.pnl), 0) FROM TradeJournal j WHERE j.setupType = :setupType AND j.action = :action AND j.createdAt >= :since")
    java.math.BigDecimal avgPnlBySetupTypeAndAction(@Param("setupType") String setupType,
                                                     @Param("action") String action,
                                                     @Param("since") LocalDateTime since);
}
