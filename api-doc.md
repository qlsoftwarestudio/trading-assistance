# Trading Assistant API Documentation

## Base URL

```
http://localhost:8080/api
```

## Dashboard Endpoints

### Health Check

```
GET /api/health
```

**Response:**
```json
{
  "status": "UP",
  "service": "trading-assistant",
  "version": "1.0.0"
}
```

---

### Portfolio Summary

```
GET /api/dashboard/summary
```

**Response:**
```json
{
  "balance": 4789.43,
  "totalTrades": 42,
  "winRate": 0.57,
  "profitFactor": 1.34,
  "totalPnl": 234.50,
  "openTrades": 1,
  "dailyPnl": 12.30
}
```

**Description:** Returns balance, P&L, win rate, profit factor, and other portfolio metrics.

---

### All Trades (Paginated)

```
GET /api/dashboard/trades?page=0&size=20
```

**Parameters:**
| Name | Type | Default | Description |
|------|------|---------|-------------|
| page | int | 0 | Page number |
| size | int | 20 | Items per page |

**Response:** Page of `Trade` objects with pagination metadata.

---

### Open Trades

```
GET /api/dashboard/trades/open
```

**Response:** Array of currently open `Trade` objects.

---

### Recent Signals

```
GET /api/dashboard/signals
```

**Response:** Last 50 generated `Signal` objects.

**Signal fields:**
```json
{
  "id": 1,
  "symbol": "HYPEUSDT",
  "action": "LONG",
  "price": 59.248,
  "rsi": 28.45,
  "sessionLow": 58.92,
  "sessionHigh": 60.15,
  "momentum": 0.025,
  "inBuyZone": true,
  "inSellZone": false,
  "generatedAt": "2026-06-13T02:00:00",
  "executed": true
}
```

---

### Daily Metrics

```
GET /api/dashboard/metrics
```

**Response:** Latest `DailyMetrics` object with daily performance data.

---

### Strategy Status

```
GET /api/strategy/status
```

**Response:**
```json
{
  "strategy": "Strategy: HYPEUSDT 5m SCALPING | Enabled: true | Symbol: HYPEUSDT | RSI(7) < 30 / > 70 | ...",
  "status": "ACTIVE"
}
```

---

### Execute Strategy Manually

```
POST /api/strategy/execute
```

**Description:** Manually trigger strategy execution with real kline data.

**Response:**
```json
{
  "message": "Strategy executed manually with real kline data",
  "timestamp": "2026-06-13T02:05:30.123"
}
```

---

### Monitor Trades

```
POST /api/trades/monitor
```

**Description:** Manually trigger trade monitoring (SL/TP checks).

**Response:**
```json
{
  "message": "Trade monitoring executed",
  "timestamp": "2026-06-13T02:05:30.123"
}
```

---

### Run Backtest

```
POST /api/backtest?limit=500
```

**Parameters:**
| Name | Type | Default | Description |
|------|------|---------|-------------|
| limit | int | 500 | Number of klines to backtest |

**Response:** `BacktestResult` with:
- totalTrades
- winRate
- profitFactor
- totalPnl
- avgWin / avgLoss
- maxDrawdown

---

### Walk-Forward Backtest

```
POST /api/backtest/walk-forward?limit=500
```

**Description:** Train on 70% of data, test on 30% to detect overfitting.

**Response:** Same as `/backtest` but with walk-forward validation.

---

### Test Telegram

```
POST /api/telegram/test
```

**Description:** Send a test message to Telegram to verify configuration.

**Response:**
```json
{
  "message": "Test notification sent. Check your Telegram.",
  "timestamp": "2026-06-13T02:05:30.123"
}
```

---

## Admin Endpoints

### Get Strategy Configuration

```
GET /api/admin/config
```

**Description:** Returns all current trading strategy and system configuration values.

**Response:**
```json
{
  "symbol": "HYPEUSDT",
  "timeframe": "5m",
  "rsiOversold": 30.0,
  "rsiOverbought": 70.0,
  "stopLossPct": 0.6,
  "takeProfitPct": 1.2,
  "positionSizePct": 10.0,
  "leverage": 5,
  "maxConcurrentTrades": 2,
  "maxHoldMinutes": 35,
  "trailingStopPct": 0.6,
  "useAtrStop": true,
  "useVwapFilter": true,
  "useEmaFilter": true,
  "useRegressionFilter": true,
  "useTrendDipLong": true,
  "sessionFilterEnabled": false,
  "momentumExitEnabled": true,
  "positionSizeVolatilityAdjust": true
}
```

---

### Get Bot Health

```
GET /api/admin/health
```

**Description:** Returns strategy state, open trades count, last signal time, and connection status.

**Response:**
```json
{
  "strategyState": "ACTIVE",
  "openTrades": 1,
  "lastSignalTime": "2026-06-13T02:00:00",
  "binanceConnected": true,
  "telegramEnabled": true,
  "dbConnected": true
}
```

---

## Actuator Endpoints

```
GET /actuator/health       # Health status with details
GET /actuator/info         # Application info
GET /actuator/metrics      # JVM and application metrics
```

---

## Models

### Trade

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Trade ID |
| symbol | String | HYPEUSDT |
| action | String | LONG / SHORT |
| entryPrice | BigDecimal | Entry price |
| exitPrice | BigDecimal | Exit price (null if open) |
| quantity | BigDecimal | Position quantity |
| stopLoss | BigDecimal | Stop loss price |
| takeProfit | BigDecimal | Take profit price |
| investedAmount | BigDecimal | Margin used |
| status | String | OPEN / CLOSED |
| entryTime | LocalDateTime | Entry timestamp |
| exitTime | LocalDateTime | Exit timestamp |
| exitReason | String | STOP_LOSS / TAKE_PROFIT / TIME_EXIT / MOMENTUM_EXIT / MANUAL |
| pnl | BigDecimal | P&L in USD |
| pnlPercent | BigDecimal | P&L percentage |

### Signal

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Signal ID |
| symbol | String | HYPEUSDT |
| action | String | LONG / SHORT |
| price | BigDecimal | Price at signal generation |
| rsi | BigDecimal | RSI value |
| sessionLow | BigDecimal | Session low |
| sessionHigh | BigDecimal | Session high |
| momentum | BigDecimal | Momentum percentage |
| inBuyZone | boolean | Price in buy zone |
| inSellZone | boolean | Price in sell zone |
| generatedAt | LocalDateTime | Signal timestamp |
| executed | boolean | Whether a trade was opened |
| projectionNote | String | ATR projection + channel info |

---

## Error Responses

All endpoints return standard HTTP status codes:

| Status | Meaning |
|--------|---------|
| 200 | Success |
| 400 | Bad Request (e.g., not enough data for backtest) |
| 404 | Not Found (e.g., no daily metrics yet) |
| 500 | Internal Server Error |

**Error body:**
```json
{
  "error": "Description of the error"
}
```

---

## WebSocket (Binance User Data Stream)

The bot connects to Binance WebSocket for real-time order updates:

```
wss://testnet.binancefuture.com/ws/<listenKey>
```

Events handled:
- `executionReport`: SL/TP execution
- `ORDER_TRADE_UPDATE`: Position updates

---

## Rate Limits

Binance API rate limits apply:
- 1200 request weight per minute for REST API
- The bot stays well below this limit with 2-minute strategy intervals

---

*Document generated on Jun 13, 2026*
