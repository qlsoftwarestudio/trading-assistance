# Trading Assistant - BTCUSD 15m SOLO LONG

Bot de trading automático para BTCUSD en timeframe 15 minutos con estrategia SOLO LONG.

## 🎯 Estrategia

- **Timeframe**: 15 minutos
- **Tipo**: SOLO LONG (no shorts)
- **Indicadores**: RSI(5), Session Low(12), Momentum
- **Profit Factor**: 3.66
- **Win Rate**: 50%
- **CAGR**: 42.14%

## 🚀 Inicio Rápido

### 1. Requisitos

- Java 21
- Docker & Docker Compose
- Cuenta Binance (Testnet para pruebas)

### 2. Configuración

Copiar variables de entorno:

```bash
cp .env.example .env
# Editar .env con tus API keys
```

Variables necesarias:
```
BINANCE_API_KEY=tu_api_key
BINANCE_SECRET_KEY=tu_secret_key
BINANCE_TESTNET=true
TELEGRAM_BOT_TOKEN=tu_token
TELEGRAM_CHAT_ID=tu_chat_id
```

### 3. Ejecución Local

```bash
# Levantar con Docker Compose
./run.sh

# O manualmente
docker-compose up -d
```

La aplicación estará disponible en:
- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

### 4. Endpoints API

| Endpoint | Descripción |
|----------|-------------|
| `GET /api/dashboard/summary` | Resumen del portfolio |
| `GET /api/dashboard/trades` | Lista de trades |
| `GET /api/dashboard/trades/open` | Trades abiertos |
| `GET /api/dashboard/signals` | Señales recientes |
| `GET /api/dashboard/metrics` | Métricas diarias |
| `GET /api/strategy/status` | Estado de la estrategia |
| `POST /api/strategy/execute` | Ejecutar manualmente |

## 📊 Proyección de Ganancias

Con $2,000 inicial + $100/mes:

| Años | Capital Final | Invertido | Ganancia |
|------|--------------|-----------|----------|
| 5 | $20,665 | $8,000 | +$12,665 |
| 10 | $96,752 | $14,000 | +$82,752 |
| 15 | $451,262 | $20,000 | +$431,262 |

## 🔧 Configuración de Estrategia

En `application.yml`:

```yaml
trading:
  strategy:
    symbol: BTCUSDT
    timeframe: 15m
    rsi-length: 5
    rsi-oversold: 30
    lookback-bars: 12
    killzone-threshold: 1.0
    min-momentum: 0.8
    stop-loss-pct: 2.0
    take-profit-pct: 8.0
    position-size-pct: 20.0
```

## 🏗️ Arquitectura

```
trading-assistant/
├── binance/          # Integración Binance API
├── strategy/         # Estrategia 15m SOLO LONG
├── execution/        # Gestión de trades
├── portfolio/        # Métricas y analytics
├── notification/     # Telegram alerts
└── api/              # REST API
```

## 📝 Notas

- Iniciar siempre en **Testnet** para validar
- Paper trading 1 semana antes de live
- Monitorear logs: `docker logs -f trading-assistant`

## 📄 Licencia

Proyecto personal - Uso exclusivo.
