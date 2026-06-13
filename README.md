# Trading Assistant - HYPEUSDT 5m Scalping Bot

Bot de trading automatizado para Binance USD-M Futures. Opera **HYPEUSDT en timeframe 5 minutos** con estrategia **LONG/SHORT simétrica** y gestión de riesgo adaptativa.

## 🎯 Estado Actual (Jun 2026)

| Métrica | Valor |
|---------|-------|
| **Par** | HYPEUSDT |
| **Timeframe** | 5 minutos |
| **Dirección** | LONG + SHORT (simétrico) |
| **Leverage** | 5x |
| **R:R** | 2:1 (TP = 2× SL) |
| **Puntuación** | **8.5/10** |

## 🚀 Inicio Rápido

### Requisitos

- Java 21
- PostgreSQL (Railway) / Docker Compose
- Cuenta Binance Futures (Testnet para pruebas)

### Variables de Entorno

```bash
cp .env.example .env
```

```
BINANCE_API_KEY=tu_api_key_testnet
BINANCE_SECRET_KEY=tu_secret_key
BINANCE_BASE_URL=https://testnet.binancefuture.com
BINANCE_WS_URL=wss://testnet.binancefuture.com/ws
BINANCE_TESTNET=true

DB_HOST=localhost
DB_USER=trading
DB_PASSWORD=trading123

TELEGRAM_BOT_TOKEN=tu_token
TELEGRAM_CHAT_ID=tu_chat_id
TELEGRAM_ENABLED=true
```

### Ejecución

```bash
# Local
./gradlew bootRun

# Docker
./run.sh
```

La aplicación estará en:
- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

## 🧠 Estrategia

### Señales de Entrada

| Señal | LONG | SHORT |
|-------|------|-------|
| **Mean-Reversion** | RSI < 30 + BuyZone | RSI > 70 + SellZone |
| **Breakout** | Rompe arriba + Vol ≥ 1.0x | Rompe abajo + Vol ≥ 1.0x |
| **Trend-Dip** | Pullback en canal UP | Bounce en canal DOWN |

### Filtros

- **Volumen mínimo**: 0.5x para mean-reversion (evita traps)
- **VWAP**: Precio dentro de ±1.5%
- **EMA9**: Confirmación de tendencia
- **Contexto**: 1h/4h/1d trend + BTC correlación
- **Canal Regresión**: Anti-Crash (LONG), Anti-Pump (SHORT)

### Risk Management

| Parámetro | Valor | Nota |
|-----------|-------|------|
| SL | **ATR × 1.5** (adaptativo) | Antes: fijo 0.6% |
| TP | **2× SL** (2:1 R:R) | Siempre |
| Leverage | 5x | |
| Position Size | 10% base | **Dinámico** por volatilidad |
| Max Hold | 35 min | Fallback |
| Max Daily Loss | 5% | Hard stop |
| SL Cooldown | 10 min | Post-SL |

### Trailing Stop (3 fases)

| Fase | Trigger | Acción |
|------|---------|--------|
| **Breakeven** | +0.4% favorable | SL → entry + 0.08% |
| **Trailing** | +0.6% favorable | Trail dinámico: 0.6% → 0.4% → 0.25% |
| **Time-based** | >10 min | Trail adicional 0.5% |

### Momentum Exit

Si el momentum cae **70% desde el entry** y el progreso hacia TP < 50%, cierra temprano.

## 🏗️ Arquitectura

```
trading-assistant/
├── binance/          # Binance API client (WebClient reactive)
├── strategy/         # HYPEUSDT 5m strategy + indicators
├── execution/        # TradeManager (entries, SL/TP, trailing)
├── portfolio/        # Métricas, analytics, DailyMetrics
├── notification/     # Telegram Bot
├── api/              # REST API (Dashboard + Admin)
└── docs/             # Documentación
```

## � Documentación

- [API Documentation](api-doc.md) — Endpoints REST
- [Análisis Producción + Fase 2](docs/analisis-prod-fase2.md) — Estado actual y plan
- [Plan Mejora Completo](docs/plan-mejora-completo.md) — Roadmap a 9.5/10

## 🔧 Configuración Principal

```yaml
trading:
  strategy:
    symbol: HYPEUSDT
    timeframe: 5m
    rsi-length: 7
    rsi-oversold: 30
    rsi-overbought: 70
    use-atr-stop: true
    atr-period: 14
    atr-multiplier: 1.5
    trailing-stop-pct: 0.6
    position-size-pct: 10.0
    leverage: 5
    max-concurrent-trades: 2
    max-hold-minutes: 35
    # Dynamic sizing
    position-size-volatility-adjust: true
    # Momentum exit
    momentum-exit-enabled: true
    momentum-exit-drop-pct: 70.0
```

## 📝 Notas

- **Testnet**: Polling local 10s para SL/TP (Binance testnet no soporta ordenes condicionales)
- **Producción**: Ordenes condicionales reales en Binance (server-side)
- Monitorear logs: `docker logs -f trading-assistant`

## 📄 Licencia

Proyecto personal - Uso exclusivo.
