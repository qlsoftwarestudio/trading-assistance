package com.trading.assistant.execution;

import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.TradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeManagerIsolationTest {

    @Mock
    private TradeRepository tradeRepository;

    @InjectMocks
    private TradeManager tradeManager;

    private Trade buildTrade(String action, String symbol) {
        Trade t = new Trade();
        t.setAction(action);
        t.setSymbol(symbol);
        t.setStatus("OPEN");
        return t;
    }

    // ─── hasOpenPosition isolation ────────────────────────────────────────────

    @Test
    void hasOpenPosition_user1HasLong_doesNotBlockUser2() {
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of(buildTrade("LONG", "HYPEUSDT")));
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(2L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of());

        assertTrue(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "LONG"),
                "User 1 should see their own open LONG");
        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 2L, "LONG"),
                "User 2 must not be blocked by user 1's position");
    }

    @Test
    void hasOpenPosition_sameUser_differentSymbols_notBlocked() {
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of(buildTrade("LONG", "HYPEUSDT")));
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "SOLUSDT", "OPEN"))
                .thenReturn(List.of());

        assertTrue(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "LONG"));
        assertFalse(tradeManager.hasOpenPosition("SOLUSDT", 1L, "LONG"),
                "HYPE trade must not block SOL trade for the same user");
    }

    @Test
    void hasOpenPosition_shortDoesNotBlockLong_sameUserAndSymbol() {
        Trade shortTrade = buildTrade("SHORT", "HYPEUSDT");
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of(shortTrade));

        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "LONG"),
                "Open SHORT must not block a LONG signal");
        assertTrue(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "SHORT"),
                "Open SHORT must be detected for SHORT direction");
    }

    @Test
    void hasOpenPosition_noOpenTrades_returnsFalseForBothDirections() {
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(anyLong(), anyString(), eq("OPEN")))
                .thenReturn(List.of());

        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "LONG"));
        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "SHORT"));
    }

    @Test
    void hasOpenPosition_multipleUsersMultipleSymbols_fullyIsolated() {
        // User 1: LONG on HYPE, nothing on SOL
        // User 2: SHORT on SOL, nothing on HYPE
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of(buildTrade("LONG", "HYPEUSDT")));
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(1L, "SOLUSDT", "OPEN"))
                .thenReturn(List.of());
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(2L, "HYPEUSDT", "OPEN"))
                .thenReturn(List.of());
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(2L, "SOLUSDT", "OPEN"))
                .thenReturn(List.of(buildTrade("SHORT", "SOLUSDT")));

        assertTrue(tradeManager.hasOpenPosition("HYPEUSDT", 1L, "LONG"));
        assertFalse(tradeManager.hasOpenPosition("SOLUSDT", 1L, "LONG"));
        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 2L, "LONG"));
        assertTrue(tradeManager.hasOpenPosition("SOLUSDT", 2L, "SHORT"));

        // Cross-checks: user 1 HYPE should not affect user 2 HYPE
        assertFalse(tradeManager.hasOpenPosition("HYPEUSDT", 2L, "LONG"));
        // User 2 SOL should not affect user 1 SOL
        assertFalse(tradeManager.hasOpenPosition("SOLUSDT", 1L, "SHORT"));
    }

    @Test
    void hasOpenPosition_queriesCorrectUserIdAndSymbol() {
        when(tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(anyLong(), anyString(), eq("OPEN")))
                .thenReturn(List.of());

        tradeManager.hasOpenPosition("HYPEUSDT", 7L, "LONG");

        verify(tradeRepository, times(1))
                .findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(7L, "HYPEUSDT", "OPEN");
        verify(tradeRepository, never())
                .findByStatusOrderByEntryTimeDesc(anyString());
    }
}
