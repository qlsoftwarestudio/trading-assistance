package com.trading.assistant.portfolio;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.DailyMetricsRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private DailyMetricsRepository dailyMetricsRepository;

    @Mock
    private BinanceClient binanceClient;

    @InjectMocks
    private PortfolioService portfolioService;

    private void stubUserSummary(Long userId, long closed, long winning, long losing, long open,
                                  BigDecimal pnl, BigDecimal grossProfit, BigDecimal grossLoss) {
        when(tradeRepository.countByUserIdAndStatus(userId, "CLOSED")).thenReturn(closed);
        when(tradeRepository.countWinningTradesByUserId(userId)).thenReturn(winning);
        when(tradeRepository.countLosingTradesByUserId(userId)).thenReturn(losing);
        when(tradeRepository.countByUserIdAndStatus(userId, "OPEN")).thenReturn(open);
        when(tradeRepository.calculateTotalPnlByUserId(userId)).thenReturn(pnl);
        when(tradeRepository.calculateGrossProfitByUserId(userId)).thenReturn(grossProfit);
        when(tradeRepository.calculateGrossLossByUserId(userId)).thenReturn(grossLoss);
        when(tradeRepository.findClosedTradesOrderByExitTimeAsc()).thenReturn(List.of());
    }

    // ─── User-specific query routing ─────────────────────────────────────────

    @Test
    void getPortfolioSummary_withUserId_callsUserSpecificRepositoryMethods() {
        when(binanceClient.getBalance("USDT")).thenReturn(new BigDecimal("1000.00"));
        when(binanceClient.getCurrentPrice()).thenReturn(new BigDecimal("18.50"));
        stubUserSummary(1L, 10L, 7L, 3L, 1L,
                new BigDecimal("150.00"), new BigDecimal("200.00"), new BigDecimal("50.00"));

        portfolioService.getPortfolioSummary(null, 1L);

        verify(tradeRepository).countWinningTradesByUserId(1L);
        verify(tradeRepository).calculateTotalPnlByUserId(1L);
        verify(tradeRepository).calculateGrossProfitByUserId(1L);
        verify(tradeRepository).calculateGrossLossByUserId(1L);

        verify(tradeRepository, never()).countWinningTrades();
        verify(tradeRepository, never()).calculateTotalPnl();
        verify(tradeRepository, never()).calculateGrossProfit();
    }

    @Test
    void getPortfolioSummary_withoutUserId_callsGlobalRepositoryMethods() {
        when(binanceClient.getBalance("USDT")).thenReturn(new BigDecimal("1000.00"));
        when(binanceClient.getCurrentPrice()).thenReturn(new BigDecimal("18.50"));
        when(tradeRepository.count()).thenReturn(5L);
        when(tradeRepository.countWinningTrades()).thenReturn(3L);
        when(tradeRepository.countLosingTrades()).thenReturn(2L);
        when(tradeRepository.countByStatus("OPEN")).thenReturn(0L);
        when(tradeRepository.calculateTotalPnl()).thenReturn(BigDecimal.ZERO);
        when(tradeRepository.calculateGrossProfit()).thenReturn(BigDecimal.ZERO);
        when(tradeRepository.calculateGrossLoss()).thenReturn(BigDecimal.ZERO);
        when(tradeRepository.findClosedTradesOrderByExitTimeAsc()).thenReturn(List.of());

        portfolioService.getPortfolioSummary(null, null);

        verify(tradeRepository).countWinningTrades();
        verify(tradeRepository, never()).countWinningTradesByUserId(anyLong());
    }

    // ─── Win rate calculation ─────────────────────────────────────────────────

    @Test
    void getPortfolioSummary_winRate_calculatedCorrectly_60percent() {
        when(binanceClient.getBalance("USDT")).thenReturn(BigDecimal.ZERO);
        when(binanceClient.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        stubUserSummary(1L, 10L, 6L, 4L, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, Object> summary = portfolioService.getPortfolioSummary(null, 1L);

        BigDecimal winRate = (BigDecimal) summary.get("winRate");
        assertNotNull(winRate);
        assertEquals(0, new BigDecimal("60.0000").compareTo(winRate),
                "Expected 60% win rate (6/10), got: " + winRate);
    }

    @Test
    void getPortfolioSummary_winRate_zeroTrades_returnsZero() {
        when(binanceClient.getBalance("USDT")).thenReturn(BigDecimal.ZERO);
        when(binanceClient.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        stubUserSummary(1L, 0L, 0L, 0L, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        Map<String, Object> summary = portfolioService.getPortfolioSummary(null, 1L);

        assertEquals(BigDecimal.ZERO, summary.get("winRate"));
    }

    // ─── Profit factor calculation ────────────────────────────────────────────

    @Test
    void getPortfolioSummary_profitFactor_calculatedCorrectly() {
        when(binanceClient.getBalance("USDT")).thenReturn(BigDecimal.ZERO);
        when(binanceClient.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        stubUserSummary(1L, 10L, 6L, 4L, 0L,
                new BigDecimal("300.00"), new BigDecimal("400.00"), new BigDecimal("100.00"));

        Map<String, Object> summary = portfolioService.getPortfolioSummary(null, 1L);

        BigDecimal pf = (BigDecimal) summary.get("profitFactor");
        assertNotNull(pf);
        // grossProfit / grossLoss = 400 / 100 = 4.0
        assertTrue(pf.compareTo(new BigDecimal("4.0")) >= 0,
                "Profit factor should be 4.0, got: " + pf);
    }

    @Test
    void getPortfolioSummary_profitFactor_noLoss_returns999() {
        when(binanceClient.getBalance("USDT")).thenReturn(BigDecimal.ZERO);
        when(binanceClient.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        stubUserSummary(1L, 5L, 5L, 0L, 0L,
                new BigDecimal("300.00"), new BigDecimal("300.00"), BigDecimal.ZERO);

        Map<String, Object> summary = portfolioService.getPortfolioSummary(null, 1L);

        BigDecimal pf = (BigDecimal) summary.get("profitFactor");
        assertEquals(0, new BigDecimal("999.99").compareTo(pf),
                "Expected 999.99 profit factor when no losses, got: " + pf);
    }

    // ─── Multi-tenancy isolation ──────────────────────────────────────────────

    @Test
    void getPortfolioSummary_twoUsers_receiveIndependentData() {
        when(binanceClient.getBalance("USDT")).thenReturn(new BigDecimal("5000.00"));
        when(binanceClient.getCurrentPrice()).thenReturn(new BigDecimal("18.50"));

        stubUserSummary(1L, 20L, 12L, 8L, 0L,
                new BigDecimal("300.00"), new BigDecimal("400.00"), new BigDecimal("100.00"));
        stubUserSummary(2L, 4L, 1L, 3L, 1L,
                new BigDecimal("-80.00"), new BigDecimal("50.00"), new BigDecimal("130.00"));

        Map<String, Object> user1Summary = portfolioService.getPortfolioSummary(null, 1L);
        Map<String, Object> user2Summary = portfolioService.getPortfolioSummary(null, 2L);

        BigDecimal wr1 = (BigDecimal) user1Summary.get("winRate");
        BigDecimal wr2 = (BigDecimal) user2Summary.get("winRate");

        assertNotEquals(0, wr1.compareTo(wr2), "Users must have different win rates");
        assertEquals(20L, user1Summary.get("totalTrades"));
        assertEquals(4L, user2Summary.get("totalTrades"));
    }

    // ─── Max drawdown isolation ───────────────────────────────────────────────

    @Test
    void getPortfolioSummary_maxDrawdown_filtersTradesByUserId() {
        when(binanceClient.getBalance("USDT")).thenReturn(BigDecimal.ZERO);
        when(binanceClient.getCurrentPrice()).thenReturn(BigDecimal.ZERO);
        stubUserSummary(1L, 2L, 1L, 1L, 0L,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        Trade user1Win = new Trade();
        user1Win.setUserId(1L);
        user1Win.setSymbol("HYPEUSDT");
        user1Win.setPnl(new BigDecimal("100.00"));
        user1Win.setStatus("CLOSED");

        Trade user1Loss = new Trade();
        user1Loss.setUserId(1L);
        user1Loss.setSymbol("HYPEUSDT");
        user1Loss.setPnl(new BigDecimal("-50.00"));
        user1Loss.setStatus("CLOSED");

        Trade user2Loss = new Trade();
        user2Loss.setUserId(2L);
        user2Loss.setSymbol("HYPEUSDT");
        user2Loss.setPnl(new BigDecimal("-999.00"));
        user2Loss.setStatus("CLOSED");

        when(tradeRepository.findClosedTradesOrderByExitTimeAsc())
                .thenReturn(List.of(user1Win, user1Loss, user2Loss));

        Map<String, Object> summary = portfolioService.getPortfolioSummary(null, 1L);

        BigDecimal maxDrawdown = (BigDecimal) summary.get("maxDrawdown");
        assertNotNull(maxDrawdown);
        // user2's $-999 loss must not affect user1's drawdown calculation
        assertTrue(maxDrawdown.compareTo(new BigDecimal("900")) < 0,
                "User 2's massive loss must not pollute user 1's drawdown: " + maxDrawdown);
    }
}
