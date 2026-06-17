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

    Optional<DailyMetrics> findTopByOrderByDateDesc();

    List<DailyMetrics> findAllByOrderByDateAsc();
}
