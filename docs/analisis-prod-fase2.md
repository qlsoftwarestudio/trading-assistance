# Analisis Trading Assistant - Estado Actual + Plan Produccion + Fase 2

## 1. ESTADO ACTUAL DEL BOT (Jun 13 2026)

### 1.1 Arquitectura
- **Backend**: Spring Boot 3, PostgreSQL (Railway), WebClient (reactive)
- **Exchange**: Binance USD-M Futures Testnet (HYPEUSDT)
- **Notifications**: Telegram Bot
- **Frontend**: React + TypeScript (Vercel)
- **Deployment**: Railway (auto-deploy desde main branch)

### 1.2 Estrategia HYPEUSDT 5m

#### LONG Entry Signals
| Signal | Condicion | Filtros |
|--------|-----------|---------|
| **Mean-Reversion** | RSI < 30 + BuyZone + RSI reversando ↑ | Context support, VWAP ± 1.5%, EMA9 > price, Regression lower half, Anti-Crash |
| **Breakout** | Breakout above + Vol >= 1.0x | Mismo filtrado |
| **Trend-Dip** | Canal regression UP + posicion < 40% + RSI < 45 | Trend1d no DOWN |

**Overrides (bypassan filtros):**
- RSI < 15 (extreme oversold) → bypass EMA filter, regression filter, context filter
- RSI < 20 + Vol > 2.0x + RSI reversando ↑ → volume spike override

#### SHORT Entry Signals
| Signal | Condicion | Filtros |
|--------|-----------|---------|
| **Mean-Reversion** | RSI > 70 (85 en uptrend) + SellZone + RSI reversando ↓ | Context support, VWAP ± 1.5%, EMA9 < price, Anti-Pump, Regression upper half |
| **Breakout** | Breakout below + Vol >= 1.0x | Mismo filtrado |

**Dynamic RSI:** En uptrend fuerte (trend1h=UP + trend4h=UP), RSI overbought sube a 85 y requiere 2 de 3 condiciones fuertes.

**Overrides:**
- RSI > 85 (extreme overbought) → bypass EMA filter, regression filter
- RSI > 80 + Vol > 2.0x + RSI reversando ↓ → volume spike override

#### Contexto Multi-Timeframe
- 1h, 4h, 1d tendencias (EMA-based)
- BTCUSDT correlacion (1h, 1d)
- Volumen relativo
- Confluencia = 2+ timeframes aligned

#### Canal de Regresion Lineal
- 50 velas lookback (~4h)
- Pendiente %, posicion del precio (0-1)
- Direccion: UP / DOWN / FLAT
- Filtro Anti-Crash (canal DOWN fuerte) para LONG
- Filtro Anti-Pump (canal UP fuerte) para SHORT

### 1.3 Risk Management

| Parametro | Valor | Nota |
|-----------|-------|------|
| SL | 0.6% | Fixed |
| TP | 1.2% | Fixed (2:1 R:R) |
| Leverage | 5x | |
| Position Size | 10% balance | |
| Max Hold | 35 min | |
| Max Concurrent Trades | 2 | |
| Max Daily Loss | 5% | |
| SL Cooldown | 10 min | Post-SL no entra mismo side |

### 1.4 Trailing Stop

**Fases:**

| Fase | Activacion | Comportamiento |
|------|------------|----------------|
| **Breakeven** | Movimiento favorable >= 0.4% | SL se mueve a entry + 0.08% min profit |
| **Trailing** | Movimiento >= 0.6% | Trail dinamico: <0.5% → 0.6%, 0.5-1% → 0.4%, >1% → 0.25% |
| **Time-based** | > 10 min activo | Trail 0.5% adicional |

**Peak tracking:** Track del mejor precio favorable (low para SHORT, high para LONG) usando kline high/low y currentPrice.

**Bypass:** Si TP esta dentro del rango ATR proyectado, trailing stop se desactiva para no matar ganancias.

### 1.5 Ordenes Condicionales (SL/TP)

| Ambiente | Comportamiento |
|----------|---------------|
| **Testnet** | IDs dummy (`TESTNET_STOP_MARKET_...`). SL/TP gestionado por polling local cada 10s. Trailing stop actualiza DB + dummy IDs. |
| **Produccion** | Ordenes reales `STOP_MARKET` / `TAKE_PROFIT_MARKET` en Binance. Server-side execution. Trailing stop cancela/reemplaza orden SL en vivo. |

### 1.6 Monitoreo de Trades Abiertos

| Metodo | Frecuencia | Funcion |
|--------|-----------|---------|
| `monitorOpenTradesSLTP()` | Cada 10s | Solo chequea SL/TP local (testnet) |
| `executeStrategy()` | Cada 2 min | Señales, trailing stop, time exit, SL/TP check |
| WebSocket UDS | Real-time | En produccion, notifica cuando orden condicional se ejecuta |

---

## 2. CHECKLIST PARA PRODUCCION

### 2.1 Binance API
- [ ] Cambiar `BINANCE_BASE_URL` a `https://fapi.binance.com`
- [ ] Cambiar `BINANCE_WS_URL` a `wss://fstream.binance.com`
- [ ] Cambiar `BINANCE_TESTNET` a `false`
- [ ] Verificar que `BINANCE_API_KEY` y `BINANCE_SECRET_KEY` sean de cuenta real (no testnet)
- [ ] Verificar que la cuenta tenga habilitado Futures
- [ ] Verificar que el simbolo HYPEUSDT esté disponible en USD-M Futures real
- [ ] Considerar empezar con `position-size-pct` bajo (5% o menos) para validar

### 2.2 Riesgo
- [ ] Revisar que `max-daily-loss-pct: 5.0` sea aceptable
- [ ] Considerar empezar con `leverage: 3` en vez de 5
- [ ] Verificar balance minimo en cuenta ($50-100 recomendado para empezar)
- [ ] Activar notificaciones Telegram para todos los eventos
- [ ] Considerar `max-concurrent-trades: 1` inicialmente

### 2.3 Infraestructura
- [ ] Verificar que PostgreSQL en Railway tenga suficiente storage
- [ ] Revisar logs de Railway (no hay persistencia de logs gratis)
- [ ] Considerar agregar monitoreo (health checks, alertas)
- [ ] Verificar rate limits de Binance (el bot no hace muchas requests)

### 2.4 Validacion de Estrategia
- [ ] Win rate > 45% en testnet por al menos 30 trades
- [ ] Profit factor > 1.5
- [ ] Perdida promedio < ganancia promedio
- [ ] Trailing stop no mate trades ganadores prematuramente
- [ ] Time exit no cierre trades que despues hubieran llegado a TP

### 2.5 Data Integrity
- [ ] Verificar que trades cerrados tengan `exitPrice`, `exitReason`, `commission` correctos
- [ ] Revisar que `tradePeakPrices` ConcurrentHashMap no crezca infinitamente (memory leak)
- [ ] Verificar que `lastSlTime` map se limpie (no hay cleanup actual)

---

## 3. PLAN FASE 2: Multi-Tenant + Multi-Pair

### 3.1 Arquitectura Multi-Tenant

```
User (tenant)
  ├── Bots[]
  │     ├── Symbol: HYPEUSDT, SOLUSDT, BTCUSDT, etc.
  │     ├── Strategy config (parametros custom)
  │     ├── Exchange creds (encriptadas)
  │     ├── Estado: RUNNING / PAUSED / STOPPED
  │     └── Trade history
  ├── Portfolio dashboard
  ├── Stats (win rate, profit factor, Sharpe, etc.)
  └── Alerts (Telegram/Email/Web push)
```

### 3.2 Cambios de Base de Datos

**Nuevas tablas:**
- `users` (id, email, created_at, plan/free/premium)
- `user_bots` (id, user_id, symbol, strategy_params_json, exchange_creds_encrypted, status)
- `bot_trades` (id, bot_id, symbol, action, entry_price, exit_price, ...)
- `bot_stats` (id, bot_id, date, win_rate, profit_factor, total_trades, pnl)

**Modificaciones:**
- `Trade` entity: agregar `bot_id` (nullable para backward compat)
- `Signal` entity: agregar `bot_id`
- Encriptar API keys (AES-256) antes de guardar

### 3.3 Cambios de Backend

| Componente | Cambio |
|------------|--------|
| **BinanceClient** | Debe ser instanciado por bot (no singleton). Cada bot tiene su propio WebClient con creds del usuario. |
| **TradeManager** | Uno por bot activo. Spring `@Scheduled` no escala bien para N bots. Considerar Quartz o custom scheduler. |
| **HypeStrategy** | Parametrizable por bot. Cada bot puede tener SL/TP diferentes, RSI thresholds diferentes, etc. |
| **MarketContextAnalyzer** | Cachear resultados por symbol para no hacer N requests identicas. |
| **WebSocket** | Un listenKey por bot. Manejar reconexiones independientes. |

### 3.4 Scheduler Architecture

**Opcion A: Spring @Scheduled + ThreadPool**
```java
// application.yml
spring:
  task:
    scheduling:
      pool:
        size: 20  // Max 20 bots concurrentes
```
Problema: no es dinamico (bots se agregan/eliminan en runtime).

**Opcion B: Quartz Scheduler (recomendado)**
- Jobs dinamicos: crear/eliminar triggers en runtime
- Clustering support (si escalas a multiples instancias)
- JobDataMap para pasar bot_id, symbol, etc.

**Opcion C: Custom Executor + Runnable**
```java
private final Map<String, ScheduledFuture<?>> botTasks = new ConcurrentHashMap<>();

// Al crear bot:
ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
    () -> runBot(botId), 0, 2, TimeUnit.MINUTES);
botTasks.put(botId, task);

// Al detener bot:
botTasks.remove(botId).cancel(false);
```

### 3.5 Multi-Pair Consideraciones

| Par | Volatilidad | ATR | SL/TP Recomendado | Nota |
|-----|-------------|-----|-------------------|------|
| **HYPEUSDT** | Alta | ~0.18 | 0.6% / 1.2% | Meme coin, movimientos bruscos |
| **SOLUSDT** | Media-Alta | ~0.8 | 1.0% / 2.0% | Mas estable, mejor R:R |
| **BTCUSDT** | Media | ~200 | 0.8% / 1.6% | Liquidez infinita, spreads bajos |
| **ETHUSDT** | Media | ~15 | 0.8% / 1.6% | Similar a BTC |

**Parametros que DEBEN ser por-symbol:**
- `stop-loss-pct`, `take-profit-pct`
- `trailing-stop-pct`, `trailing-activation-pct`
- `rsi-oversold`, `rsi-overbought` (varian por volatilidad)
- `position-size-pct` (ajustar por precio del par)
- `leverage` (máximo varia por par en Binance)

**Parametros globales:**
- `max-daily-loss-pct`, `max-concurrent-trades`
- Estructura de la estrategia (mean-rev, breakout, trend-dip)

### 3.6 Exchange Agnostico (Fase 2.5)

**Interfaz `ExchangeClient`:**
```java
public interface ExchangeClient {
    String placeMarketOrder(String side, BigDecimal quantity);
    String placeStopLossOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice);
    String placeTakeProfitOrder(...);
    boolean cancelOrder(String orderId);
    BigDecimal getCurrentPrice();
    List<Kline> getKlines(String timeframe, int limit);
    double getBalance(String asset);
}
```

**Implementaciones:**
- `BinanceClient implements ExchangeClient` (ya existe)
- `BybitClient implements ExchangeClient`
- `OKXClient implements ExchangeClient`

### 3.7 Frontend Cambios

| Feature | Descripcion |
|---------|-------------|
| **Dashboard Multi-Bot** | Lista de bots con status, P&L, win rate |
| **Bot Creator** | Formulario para crear bot: select symbol, ajustar parametros, conectar exchange |
| **Bot Detail** | Grafico de equity, trade history, stats |
| **Copy Trading** | (Premium) Copiar trades de bots publicos |
| **Alert Config** | Configurar que notificaciones recibir |

### 3.8 Plan de Implementacion Fase 2

**Fase 2.1: Foundation (2-3 semanas)**
- [ ] Migrar a multi-tenant DB schema
- [ ] Auth (JWT) + User registration/login
- [ ] Encriptar API keys
- [ ] Refactorizar BinanceClient a no-singleton
- [ ] Agregar `bot_id` a todas las entidades

**Fase 2.2: Multi-Bot (2 semanas)**
- [ ] Crear Bot CRUD API
- [ ] Scheduler dinamico (Quartz o custom)
- [ ] Un TradeManager por bot
- [ ] WebSocket handler por bot

**Fase 2.3: Multi-Pair (2 semanas)**
- [ ] Parametros por-symbol en DB
- [ ] Validar estrategia en BTC, ETH, SOL
- [ ] Ajustar parametros default por par
- [ ] Symbol selector en frontend

**Fase 2.4: Exchange Agnostico (2 semanas)**
- [ ] Interfaz ExchangeClient
- [ ] Refactorizar BinanceClient
- [ ] Implementar BybitClient (opcional)

**Fase 2.5: Frontend + Monetizacion (3-4 semanas)**
- [ ] Dashboard multi-bot
- [ ] Bot creator/configurator
- [ ] Stats y analytics
- [ ] Stripe integration (free/premium tiers)

**Total estimado: 11-13 semanas (3 meses)**

---

## 4. RIESGOS Y MITIGACIONES

### 4.1 Riesgos Actuales

| Riesgo | Severidad | Mitigacion |
|--------|-----------|------------|
| **Testnet != Produccion** | Alto | Validar en prod con montos minimos, monitorear SL/TP real |
| **Memory leak (tradePeakPrices)** | Medio | Agregar cleanup cuando trade se cierra |
| **Rate limits Binance** | Medio | El bot hace pocos requests, pero en multi-bot escala |
| **WebSocket desconexion** | Medio | Reconexion automatica ya implementada |
| **Telegram rate limits** | Bajo | No enviar mensajes duplicados, batch si es necesario |

### 4.2 Riesgos Fase 2

| Riesgo | Severidad | Mitigacion |
|--------|-----------|------------|
| **Seguridad API keys** | Critico | Encriptar en DB, nunca loggear, rotacion automatica |
| **Multi-tenancy data isolation** | Critico | Row-level security o prefix en tablas |
| **Scheduler overload** | Alto | Thread pool limitado, degradacion graceful |
| **DB performance** | Medio | Indices por bot_id + symbol, paginacion |

---

*Documento generado el 13 Jun 2026*
