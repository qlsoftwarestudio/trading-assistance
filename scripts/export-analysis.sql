-- Exportar trades y señales para análisis de estrategia
-- Ejecutar en la DB de Railway (PostgreSQL probablemente)

-- Trades completos con P&L
SELECT
    id,
    symbol,
    action,
    entry_price,
    exit_price,
    quantity,
    invested_amount,
    entry_time,
    exit_time,
    stop_loss,
    take_profit,
    pnl,
    pnl_percent,
    exit_reason,
    status,
    created_at
FROM trades
WHERE status = 'CLOSED'
ORDER BY entry_time ASC;

-- Señales con indicadores en el momento de detección
SELECT
    id,
    symbol,
    action,
    price,
    rsi,
    session_low,
    session_high,
    momentum,
    buy_zone,
    sell_zone,
    trend_1h,
    trend_4h,
    trend_1d,
    relative_volume,
    btc_correlation,
    btc_trend_1d,
    confluence,
    distance_to_support_pct,
    distance_to_resistance_pct,
    signal_time,
    status
FROM signals
ORDER BY signal_time ASC;
