package com.trading.assistant.portfolio.repository;

import com.trading.assistant.portfolio.model.DailyMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyMetricsRepository extends JpaRepository<DailyMetrics, Long> {

    Optional<DailyMetrics> findByDate(LocalDate date);

    Optional<DailyMetrics> findBySymbolAndDate(String symbol, LocalDate date);

    Optional<DailyMetrics> findTopByOrderByDateDesc();

    Optional<DailyMetrics> findTopBySymbolOrderByDateDesc(String symbol);

    List<DailyMetrics> findAllByOrderByDateAsc();

    List<DailyMetrics> findBySymbolOrderByDateAsc(String symbol);

    List<DailyMetrics> findByUserIdIsNull();

    Optional<DailyMetrics> findTopByUserIdOrderByDateDesc(Long userId);

    Optional<DailyMetrics> findTopByUserIdAndSymbolOrderByDateDesc(Long userId, String symbol);

    List<DailyMetrics> findByUserIdOrderByDateAsc(Long userId);

    List<DailyMetrics> findByUserIdAndSymbolOrderByDateAsc(Long userId, String symbol);
}
