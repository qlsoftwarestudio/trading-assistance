package com.trading.assistant.strategy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Disabled("Integration test: requires PostgreSQL. Run manually with a live DB.")
class HypeStrategyToggleTest {

    @Autowired
    private HypeStrategy hypeStrategy;

    @Test
    void testToggle() {
        boolean initial = hypeStrategy.isRunning();
        boolean toggled = hypeStrategy.toggle();
        assertEquals(!initial, toggled);
        // Restore
        hypeStrategy.toggle();
        assertEquals(initial, hypeStrategy.isRunning());
    }

    @Test
    void testStartStop() {
        hypeStrategy.stop();
        assertFalse(hypeStrategy.isRunning());
        hypeStrategy.start();
        assertTrue(hypeStrategy.isRunning());
    }
}
