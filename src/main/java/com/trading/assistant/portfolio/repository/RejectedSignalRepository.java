package com.trading.assistant.portfolio.repository;

import com.trading.assistant.portfolio.model.RejectedSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RejectedSignalRepository extends JpaRepository<RejectedSignal, Long> {

    List<RejectedSignal> findBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(String symbol, LocalDateTime since);

    List<RejectedSignal> findByStrategyAndCreatedAtAfterOrderByCreatedAtDesc(String strategy, LocalDateTime since);

    @Query("SELECT r.rejectionReason, COUNT(r) FROM RejectedSignal r WHERE r.createdAt >= :since GROUP BY r.rejectionReason ORDER BY COUNT(r) DESC")
    List<Object[]> countByRejectionReasonSince(@Param("since") LocalDateTime since);

    @Query("SELECT r.setupType, COUNT(r) FROM RejectedSignal r WHERE r.symbol = :symbol AND r.action = :action AND r.createdAt >= :since GROUP BY r.setupType ORDER BY COUNT(r) DESC")
    List<Object[]> countBySetupTypeForSymbolAndActionSince(@Param("symbol") String symbol, @Param("action") String action, @Param("since") LocalDateTime since);

    long countBySymbolAndRejectionReasonAndCreatedAtAfter(String symbol, String rejectionReason, LocalDateTime since);
}
