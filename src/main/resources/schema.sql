-- Trading Assistant Database Schema
-- MVP Personal - Single User

-- Trades table
CREATE TABLE IF NOT EXISTS trades (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('LONG')),
    entry_price DECIMAL(20, 8) NOT NULL,
    exit_price DECIMAL(20, 8),
    quantity DECIMAL(20, 8) NOT NULL,
    invested_amount DECIMAL(20, 8) NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    stop_loss DECIMAL(20, 8),
    take_profit DECIMAL(20, 8),
    pnl DECIMAL(20, 8),
    pnl_percent DECIMAL(10, 4),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    exit_reason VARCHAR(20) CHECK (exit_reason IN ('STOP_LOSS', 'TAKE_PROFIT', 'MANUAL')),
    commission DECIMAL(20, 8) DEFAULT 0,
    binance_order_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Signals table (generated signals)
CREATE TABLE IF NOT EXISTS signals (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('LONG', 'HOLD')),
    price DECIMAL(20, 8) NOT NULL,
    rsi DECIMAL(5, 2),
    session_low DECIMAL(20, 8),
    momentum DECIMAL(10, 4),
    in_buy_zone BOOLEAN,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    executed BOOLEAN DEFAULT FALSE,
    trade_id BIGINT REFERENCES trades(id)
);

-- Daily metrics table
CREATE TABLE IF NOT EXISTS daily_metrics (
    id BIGSERIAL PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    total_trades INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    total_pnl DECIMAL(20, 8) DEFAULT 0,
    win_rate DECIMAL(5, 2),
    profit_factor DECIMAL(10, 4),
    max_drawdown DECIMAL(10, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Balance history for equity curve
CREATE TABLE IF NOT EXISTS balance_history (
    id BIGSERIAL PRIMARY KEY,
    balance DECIMAL(20, 8) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_trades_status ON trades(status);
CREATE INDEX IF NOT EXISTS idx_trades_entry_time ON trades(entry_time);
CREATE INDEX IF NOT EXISTS idx_signals_generated_at ON signals(generated_at);
CREATE INDEX IF NOT EXISTS idx_balance_timestamp ON balance_history(timestamp);
