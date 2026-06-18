# Trading Assistant — Product Overview

## 1. Qué es
Trading Assistant es una plataforma de trading automatizado para Binance Futures que opera múltiples pares de criptomonedas (HYPE, SOL, BTC, ETH) con estrategias de swing basadas en indicadores técnicos (RSI, VWAP, EMA, regresión, volumen relativo, contexto multi-timeframe). Soporta múltiples usuarios (multi-tenant), cada uno con varios bots configurables, API keys encriptadas y planes de precios tiered.

## 2. Stack Tecnológico
- **Backend:** Spring Boot 3.3, Java 21, Gradle, PostgreSQL, JPA/Hibernate
- **Frontend:** React 18, TypeScript, TailwindCSS, shadcn/ui, TanStack Query, Zustand, React Router
- **Infra:** Railway (backend + DB), Vercel (frontend)
- **Exchange:** Binance Futures API (REST + WebSocket)
- **Notificaciones:** Telegram Bot API
- **Auth:** JWT (jjwt 0.12.5) + BCrypt
- **Encriptación:** AES-256 para API keys

## 3. Casos de Uso

### UC-1: Trader individual con 1 bot (Plan FREE/STARTER)
1. Se registra con email/password.
2. Crea 1 bot en Perfil → selecciona par (ej: HYPEUSDT).
3. Sube API Key y Secret de Binance (encriptados en DB).
4. Activa el bot desde Dashboard o Perfil.
5. El bot ejecuta la estrategia swing cada 2 minutos.
6. Recibe notificaciones Telegram de entradas/salidas.
7. Ve métricas en Dashboard (P&L, Win Rate, Profit Factor).

### UC-2: Trader avanzado con 3 bots (Plan PRO)
1. Tiene 3 bots activos: HYPE swing, SOL swing, scalp SOL.
2. Cada bot tiene su propio capital máximo y symbol.
3. Puede pausar/iniciar cada bot individualmente.
4. Ve métricas agregadas o filtradas por symbol.

### UC-3: Administrador de fondos (Plan ENTERPRISE)
1. Gestiona hasta 5 bots con capital ilimitado.
2. Cada bot opera con credenciales Binance independientes.
3. Monitorea rendimiento por bot y por par.

### UC-4: Análisis de rendimiento
1. Ve trades históricos filtrados por symbol.
2. Revisa métricas diarias por par.
3. Compara performance HYPE vs SOL en Performance page.

## 4. Entidades Principales

### User
| Campo | Tipo | Descripción |
|-------|------|-------------|
| id | Long | PK |
| email | String | Único, login |
| passwordHash | String | BCrypt |
| plan | Plan | FREE, STARTER, PRO, ENTERPRISE |
| active | boolean | Estado de cuenta |
| createdAt | DateTime | Alta |

### Bot
| Campo | Tipo | Descripción |
|-------|------|-------------|
| id | Long | PK |
| name | String | Nombre del bot |
| user | User | FK |
| symbol | String | Par a operar (HYPEUSDT, SOLUSDT) |
| apiKeyEncrypted | String | AES-256 |
| apiSecretEncrypted | String | AES-256 |
| enabled | boolean | Config activa |
| running | boolean | Toggle ON/OFF |
| maxCapitalUsd | Double | Límite de capital |

### Trade
| Campo | Tipo | Descripción |
|-------|------|-------------|
| id | Long | PK |
| symbol | String | Par |
| action | String | LONG / SHORT |
| entryPrice | BigDecimal | Precio entrada |
| exitPrice | BigDecimal | Precio salida |
| pnl | BigDecimal | P&L final |
| status | String | OPEN / CLOSED |
| exitReason | String | STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME_EXIT |
| setupType | String | Mean-Reversion, Trend-Dip, Breakout |

### Signal
| Campo | Tipo | Descripción |
|-------|------|-------------|
| id | Long | PK |
| symbol | String | Par |
| action | String | LONG / HOLD |
| price | BigDecimal | Precio señal |
| rsi | BigDecimal | RSI en señal |
| setupType | String | Tipo de setup |
| executed | boolean | Se ejecutó? |

## 5. Planes Tier

| Plan | Max Bots | Max Capital | Precio |
|------|----------|-------------|--------|
| FREE | 1 | $500 | $0 |
| STARTER | 1 | $500 | $29/mes |
| PRO | 3 | $10,000 | $79/mes |
| ENTERPRISE | 5 | Ilimitado | $199/mes |

## 6. Estrategias

### Swing Strategy (HypeStrategy)
- **Timeframe:** 5m
- **Indicadores:** RSI(7), VWAP(20), EMA(9), regresión lineal, volumen relativo, delta volume
- **Setups:** Mean-Reversion, Trend-Dip, Breakout
- **Gestión de riesgo:** SL 0.6%, TP 1.2%, leverage 5x, trailing stop dinámico, breakeven lock
- **Filtros:** Contexto multi-timeframe (1h/4h/1d), confluence, volumen mínimo
- **Max hold:** 45 min (HYPE), 90 min (SOL), configurable por symbol

### Hunter/Scalp Strategy (ScalpStrategy)
- **Timeframe:** 1m
- **Estado:** DESACTIVADO por default (testnet data = basura)
- **SL/TP:** 0.1% / 0.3%

## 7. API Endpoints

### Auth
| Método | Endpoint | Body | Respuesta |
|--------|----------|------|-----------|
| POST | /api/auth/register | `{email, password, plan?}` | `{token, email, plan, maxBots}` |
| POST | /api/auth/login | `{email, password}` | `{token, email, plan, maxBots}` |

### Bots (requiere Bearer token)
| Método | Endpoint | Body | Respuesta |
|--------|----------|------|-----------|
| GET | /api/bots | — | `[{id, name, symbol, enabled, running, maxCapitalUsd}]` |
| POST | /api/bots | `{name, symbol, apiKey, apiSecret, maxCapitalUsd?}` | `{id, message}` |
| POST | /api/bots/{id}/toggle | — | `{id, running, message}` |
| DELETE | /api/bots/{id} | — | `{message}` |

### Dashboard
| Método | Endpoint | Query | Respuesta |
|--------|----------|-------|-----------|
| GET | /api/dashboard/summary | `?symbol=` | `{balance, totalTrades, winningTrades, losingTrades, openTrades, winRate, totalPnl, profitFactor, maxDrawdown, currentPrice}` |
| GET | /api/dashboard/trades | `?page=&size=&symbol=` | `Page<Trade>` |
| GET | /api/dashboard/trades/open | `?symbol=` | `List<Trade>` |
| GET | /api/dashboard/signals | `?symbol=` | `List<Signal>` |
| GET | /api/dashboard/metrics | `?symbol=` | `DailyMetrics` |
| GET | /api/dashboard/metrics/history | `?symbol=` | `List<DailyMetrics>` |

### Strategy Control
| Método | Endpoint | Respuesta |
|--------|----------|-----------|
| GET | /api/strategy/status | `{swing: {running, description}, hunter: {running}, timestamp}` |
| POST | /api/strategy/toggle | `{swingRunning, message, timestamp}` |
| POST | /api/strategy/hunter/toggle | `{hunterRunning, message, timestamp}` |
| POST | /api/strategy/execute | `{message, timestamp}` |
| POST | /api/trades/monitor | `{message, timestamp}` |

### Backtest
| Método | Endpoint | Query | Respuesta |
|--------|----------|-------|-----------|
| POST | /api/backtest | `?limit=` | `BacktestResult` |
| POST | /api/backtest/walk-forward | `?limit=` | `BacktestResult` |

### Health
| Método | Endpoint | Respuesta |
|--------|----------|-----------|
| GET | /api/health | `{status, service, version}` |

## 8. Configuración Clave (application.yml)

```yaml
trading:
  strategy:
    symbols: HYPEUSDT,SOLUSDT
    timeframe: 5m
    rsi-length: 7
    rsi-oversold: 30
    stop-loss-pct: 0.6
    take-profit-pct: 1.2
    leverage: 5
    position-size-pct: 10.0
    trailing-stop-pct: 0.6
    max-hold-minutes: 45
    hunter:
      mode-enabled: false

app:
  jwt:
    secret: ${JWT_SECRET}
    expiration: 86400000
  encryption:
    key: ${ENCRYPTION_KEY}
```

## 9. Seguridad
- **JWT:** Tokens con expiración de 24h. Refresh manual (logout/login).
- **API Keys:** Encriptadas con AES-256 antes de persistir en PostgreSQL.
- **Passwords:** Hasheadas con BCrypt.
- **CORS:** Configurado para orígenes específicos (localhost, Vercel).

## 10. Métricas de Performance Confirmadas (Producción HYPE)
| Métrica | Valor |
|---------|-------|
| Win Rate | 57.5% |
| Profit Factor | 3.09 |
| Total P&L | +$114.03 |
| Trades | 40 |
| Max Drawdown | $15.72 |
| Best Setup | Mean-Reversion (66.7% WR) |

## 11. Roadmap Futuro
- WebSocket real-time para precios y trades
- Backtest multi-par con descarga automática de datos
- Stop-Market nativas de Binance
- Machine learning para score de señales
- Mobile app (React Native)

---
Generated: 2026-06-18
Version: 2.0 (Multi-Pair + Multi-Tenant)
