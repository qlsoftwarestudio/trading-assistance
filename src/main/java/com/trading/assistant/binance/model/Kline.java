package com.trading.assistant.binance.model;

import java.math.BigDecimal;

/**
 * Modelo para representar una vela (candlestick) de Binance
 * Formato de la API: [timestamp, open, high, low, close, volume, ...]
 */
public class Kline {
    
    private long timestamp;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    
    public Kline() {}
    
    public Kline(long timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
        this.timestamp = timestamp;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }
    
    // Factory method para crear desde array de Binance API
    public static Kline fromBinanceArray(Object[] data) {
        if (data == null || data.length < 6) {
            return null;
        }
        
        try {
            return new Kline(
                Long.parseLong(data[0].toString()),                    // timestamp
                new BigDecimal(data[1].toString()),                     // open
                new BigDecimal(data[2].toString()),                     // high
                new BigDecimal(data[3].toString()),                     // low
                new BigDecimal(data[4].toString()),                     // close
                new BigDecimal(data[5].toString())                      // volume
            );
        } catch (Exception e) {
            return null;
        }
    }
    
    // Getters
    public long getTimestamp() { return timestamp; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public BigDecimal getVolume() { return volume; }
    
    // Setters
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setOpen(BigDecimal open) { this.open = open; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public void setLow(BigDecimal low) { this.low = low; }
    public void setClose(BigDecimal close) { this.close = close; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }
    
    @Override
    public String toString() {
        return String.format("Kline[timestamp=%d, open=%s, high=%s, low=%s, close=%s, volume=%s]",
                timestamp, open, high, low, close, volume);
    }
}
