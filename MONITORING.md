# Guía de Monitoreo - Trading Assistant

> Guardá esta página. Usala para revisar cómo va tu bot cada 1-2 días durante la semana de testnet.

---

## 1. Comandos útiles (cURL)

Reemplazá `https://TU-APP.railway.app` por tu URL real de Railway.

### Health check
```bash
curl https://TU-APP.railway.app/api/health
```
**Esperado:** `{"status":"UP","service":"trading-assistant"}`

### Estado de la estrategia
```bash
curl https://TU-APP.railway.app/api/strategy/status
```
**Esperado:** `Enabled: true`, `Symbol: HYPEUSDT`, `Context: true`

### Resumen del portfolio
```bash
curl https://TU-APP.railway.app/api/dashboard/summary
```
**Muestra:** balance, trades totales, ganadores, perdedores, P&L, win rate.

### Trades (paginado)
```bash
curl "https://TU-APP.railway.app/api/dashboard/trades?page=0&size=10"
```
**Muestra:** lista de trades con entry/exit, P&L, estado.

### Trades abiertos
```bash
curl https://TU-APP.railway.app/api/dashboard/trades/open
```
**Muestra:** trades que aún no cerraron (están en riesgo de SL/TP).

### Señales recientes
```bash
curl https://TU-APP.railway.app/api/dashboard/signals
```
**Muestra:** últimas 50 señales generadas, si fueron ejecutadas o rechazadas por contexto.

### Métricas diarias
```bash
curl https://TU-APP.railway.app/api/dashboard/metrics
```
**Muestra:** resumen del día (trades, ganancias, profit factor).

### Forzar ejecución de estrategia (test)
```bash
curl -X POST https://TU-APP.railway.app/api/strategy/execute
```
**Nota:** Solo genera un trade si las condiciones técnicas se cumplen en ese momento.

### Test de Telegram
```bash
curl -X POST https://TU-APP.railway.app/api/telegram/test
```
**Esperado:** mensaje de prueba en tu celular.

### Backtest rápido
```bash
curl -X POST "https://TU-APP.railway.app/api/backtest?limit=500"
```

---

## 2. Métricas clave a observar

Durante la semana de testnet, fijate en estos números:

| Métrica | Qué indica | Bueno / Malo |
|---------|-----------|--------------|
| **Win Rate** | % de trades ganadores | > 30% con ratio 1:3 ya es rentable |
| **Profit Factor** | Ganancias brutas / Pérdidas brutas | > 1.0 es rentable, > 1.5 es bueno |
| **Total Trades** | Cuántas operaciones hizo el bot | 5-10 por semana en 15m es normal |
| **P&L total** | Suma de ganancias y pérdidas | Positivo al final de la semana |
| **Trades LONG vs SHORT** | Balance de direcciones | Debería haber de ambos si el mercado oscila |
| **Rechazos por contexto** | Señales filtradas por trend/volumen | Si son muchos, el filtro está muy estricto |

### Señales de alerta (revisar logs si ves esto)

- `Error getting balance: 401 Unauthorized` → API keys vencidas o mal configuradas.
- `Error setting leverage: 401` → Lo mismo, o la cuenta testnet no tiene fondos.
- `LONG rejected by market context` / `SHORT rejected` → Normal. Significa que los filtros están funcionando.
- `Telegram API error` → Revisar token y chat ID.

---

## 3. Rutina sugerida (5 min por día)

1. **Revisá Telegram** → ¿Llegó alguna notificación de entrada/salida?
2. **Health check** → `curl /api/health` para confirmar que está vivo.
3. **Trades abiertos** → `curl /api/dashboard/trades/open` para ver qué está en juego.
4. **Resumen** → `curl /api/dashboard/summary` para ver P&L y win rate.
5. **Logs de Railway** → Buscá errores rojos (`401`, `500`, timeouts).

---

## 4. Antes de pasar a producción (checklist)

- [ ] 7 días corridos en testnet sin errores críticos
- [ ] Win rate > 30% y Profit Factor > 1.0
- [ ] Recibiste notificaciones de Telegram de entradas y salidas
- [ ] Viste trades LONG y SHORT (confirma que ambos lados funcionan)
- [ ] Creaste API keys de producción en Binance
- [ ] Transferiste USDT a tu **Futures Wallet**
- [ ] Cambiaste variables en Railway:
  ```
  BINANCE_BASE_URL=https://fapi.binance.com
  BINANCE_TESTNET=false
  BINANCE_API_KEY=<produccion>
  BINANCE_SECRET_KEY=<produccion>
  ```
- [ ] Empezaste con poco capital ($100-$200)

---

*Documento generado el 6 de junio de 2026. Actualizalo si cambiás la estrategia o los parámetros.*
