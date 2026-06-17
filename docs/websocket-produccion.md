# WebSocket Migration — Fase de Producción

**Contexto:** El bot actualmente usa polling REST para todo. Para producción con dinero real, hay que migrar los flujos críticos a WebSocket para eliminar latencia y evitar perder eventos.

---

## ¿Por qué no en testnet?

Binance Futures Testnet tiene soporte limitado/inestable de WebSocket:
- El endpoint es distinto: `wss://stream.binancefuture.com` (vs `wss://fstream.binance.com` en prod)
- El `userDataStream` (listenKey) falla o pierde conexión frecuentemente en testnet
- Ya lo intentamos en testnet — no funcionó de forma confiable
- **En producción con la API real esto funciona estable y es el estándar de la industria**

---

## Streams a implementar

### 1. `hypeusdt@kline_1m` — Para ScalpStrategy (Hunter)
**Problema actual:** ScalpStrategy hace polling cada 15s → puede evaluar RSI en mitad de una vela  
**Fix:** Recibir evento `isClosed: true` → ejecutar análisis exactamente al cierre de cada vela de 1m  
**Archivo a modificar:** `ScalpStrategy.java` — reemplazar `@Scheduled(fixedRate = 15000)` con listener de evento  

### 2. `hypeusdt@kline_5m` — Para HypeStrategy (Swing)
**Problema actual:** HypeStrategy hace polling cada 10s → señales pueden generarse mid-candle  
**Fix:** Trigger en cierre de vela 5m  
**Archivo a modificar:** `HypeStrategy.java`

### 3. `<listenKey>@userDataStream` — Para TradeManager (SL/TP hits + trailing)
**Problema actual:** TradeManager detecta SL/TP vía polling local del precio + lógica interna  
**Fix:** `ORDER_TRADE_UPDATE` event llega en ms cuando Binance ejecuta el orden → cierre inmediato  
**Beneficio:** Cero riesgo de perder un cierre de trade por restart del server o polling lento  
**Archivo a modificar:** `TradeManager.java` — agregar `onOrderUpdate(event)` handler  

---

## Arquitectura propuesta

```
BinanceWebSocketService.java (nuevo)
├── connectKlineStream("1m")  → ScalpStrategy.onKlineClosed(klines)
├── connectKlineStream("5m")  → HypeStrategy.onKlineClosed(klines)
└── connectUserDataStream()   → TradeManager.onOrderUpdate(event)
    ├── renovar listenKey cada 30min (scheduled)
    └── reconexión automática en caso de disconnect
```

Librerías disponibles en Spring Boot:
- `spring-boot-starter-websocket` 
- O directamente `java.net.http.WebSocket` (Java 11+, ya disponible)

---

## Tareas pendientes para implementar

- [ ] Crear `BinanceWebSocketService.java` con reconexión automática y ping/pong cada 20min
- [ ] Agregar `listenKey` management: POST para crear, PUT para renovar cada 30min, DELETE al shutdown
- [ ] Refactorizar `ScalpStrategy` para usar event-driven en vez de `@Scheduled`
- [ ] Refactorizar `HypeStrategy` para trigger en cierre de vela 5m
- [ ] Agregar `onOrderUpdate` en `TradeManager` para cerrar trades al recibir fill event
- [ ] Tests de reconexión (simular disconnect)

---

## Impacto esperado en producción

| Flujo | Latencia actual | Con WebSocket |
|---|---|---|
| Hunter entry (scalp) | 0-15s de delay | < 1s desde cierre de vela |
| Swing entry | 0-10s de delay | < 1s desde cierre de vela |
| SL/TP detection | polling local cada 2min | ms desde fill en Binance |
| Trailing stop update | polling cada 2min | Precio en tiempo real |

---

## Referencia de endpoints

```
Producción:
  WebSocket base: wss://fstream.binance.com
  Kline stream:   wss://fstream.binance.com/ws/hypeusdt@kline_1m
  User data:      wss://fstream.binance.com/ws/<listenKey>
  
  Crear listenKey:  POST  https://fapi.binance.com/fapi/v1/listenKey
  Renovar:          PUT   https://fapi.binance.com/fapi/v1/listenKey
  Cerrar:           DELETE https://fapi.binance.com/fapi/v1/listenKey

Testnet (no confiable):
  WebSocket base: wss://stream.binancefuture.com
```
