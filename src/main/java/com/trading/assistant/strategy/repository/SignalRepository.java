package com.trading.assistant.strategy.repository;

import com.trading.assistant.strategy.model.Signal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SignalRepository extends JpaRepository<Signal, Long> {

    List<Signal> findTop50ByOrderByGeneratedAtDesc();

    List<Signal> findTop50BySymbolOrderByGeneratedAtDesc(String symbol);

    Optional<Signal> findTop1ByOrderByGeneratedAtDesc();

    List<Signal> findByExecutedFalseOrderByGeneratedAtDesc();

    long countByExecutedTrue();

    long countByActionAndExecutedTrue(String action);

    List<Signal> findByExecutedTrue();

    List<Signal> findBySymbolAndExecutedTrue(String symbol);

    List<Signal> findByUserIdIsNull();

    List<Signal> findTop50ByUserIdOrderByGeneratedAtDesc(Long userId);

    List<Signal> findTop50ByUserIdAndSymbolOrderByGeneratedAtDesc(Long userId, String symbol);
}
