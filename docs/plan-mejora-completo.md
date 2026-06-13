# Plan de Mejora Completo — Trading Assistant Bot
## Objetivo: Cubrir TODAS las debilidades críticas y llegar a 9.0/10

---

## 1. ATR-BASED STOPS (Reemplazar SL/TP fijos)

### Problema actual
SL 0.6% fijo en HYPE es arbitrario. En volatilidad alta, 0.6% es ruido. En volatilidad baja, 0.6% es demasiado.

### Solución
```java
// ATR calculado sobre las últimas 14 velas de 5m
atr = calcularATR(klines, 14);

// SL = entry ± (atr × atrMultiplier)
// donde atrMultiplier = 1.5 por defecto
slDistance = atr * 1.5;

// TP = entry ± (slDistance × rewardRatio)
// donde rewardRatio = 2.0 (2:1 R:R)
tpDistance = slDistance * 2.0;
```

### Configuración application.yml
```yaml
stop-loss-type: ATR          # ATR o FIXED
atr-period: 14
atr-multiplier: 1.5
reward-ratio: 2.0
```

### Fallback
Si `use-atr-stop: false`, usa el SL/TP fijo actual (backward compatible).

---

## 2. FILTRO DE SESIÓN (No operar Asia)

### Problema actual
El bot opera 24/7. HYPE en sesión Asia (02:00-08:00 UTC) tiene spreads amplios, bajo volumen, movimientos erráticos.

### Solución
```java
private boolean isTradingSessionActive() {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    int hour = now.getHour();
    
    // Asia: 02:00 - 08:00 UTC → NO operar
    // London: 08:00 - 13:30 UTC → Operar con precaución
    // NY: 13:30 - 21:00 UTC → Operar normal
    // Late NY: 21:00 - 02:00 UTC → Operar con precaución
    
    if (hour >= 2 && hour < 8) {
        logger.info("Asian session — trading disabled");
        return false;
    }
    return true;
}
```

### Configuración
```yaml
trading-hours:
  enabled: true
  asia-start: 2      # 02:00 UTC
  asia-end: 8        # 08:00 UTC
  london-start: 8
  london-end: 13
  ny-start: 13
  ny-end: 21
  # Opcional: ajustar SL/TP más conservador en London/Late NY
```

---

## 3. TREND-DIP PARA SHORT (Simetría LONG/SHORT)

### Problema actual
LONG tiene 3 señales (Mean-Reversion, Breakout, Trend-Dip). SHORT solo 2. Esto crea bias directional.

### Solución
Agregar Trend-Dip SHORT:
```java
boolean trendDipShortCondition = useTrendDipShort
    && channel != null
    && channel.getDirection() == LinearRegressionChannel.ChannelDirection.DOWN
    && channel.getSlopePct() <= -trendDipChannelSlope
    && channel.getPricePosition() > 0.60   // upper 40% del canal
    && rsi > (100 - trendDipRsiThreshold);  // RSI > 55 (relativamente alto)
```

Condición: En canal DOWN, precio en upper zone (>60%), RSI moderadamente alto → shortear el pullback dentro del downtrend.

### Filtros adicionales para Trend-Dip SHORT
- Contexto trend1d no UP (evitar shortear en uptrend diario)
- VWAP, EMA, regression upper half (mismos que mean-reversion SHORT)

---

## 4. SIZE DINÁMICO (Position size por volatilidad)

### Problema actual
Position size 10% fijo. En volatilidad alta (ATR grande), el riesgo en USD es mayor. En volatilidad baja, el riesgo es menor.

### Solución
```java
// Ajustar position-size-pct según ATR relativo
atrCurrent = calcularATR(klines, 14);
atrMedian = calcularATRMedian(30);  // mediana ATR últimos 30 días

if (atrCurrent > atrMedian * 1.5) {
    // Alta volatilidad → reducir size a la mitad
    effectivePositionSize = positionSizePct * 0.5;
} else if (atrCurrent < atrMedian * 0.5) {
    // Baja volatilidad → aumentar size un 50%
    effectivePositionSize = positionSizePct * 1.5;
} else {
    effectivePositionSize = positionSizePct;
}

// Nunca más del max-configurado
effectivePositionSize = Math.min(effectivePositionSize, positionSizePct);
```

### Configuración
```yaml
position-size-pct: 10.0
position-size-volatility-adjust: true
position-size-min-pct: 5.0
position-size-max-pct: 15.0
```

---

## 5. JOURNAL + AUTO-ADJUST (Aprender de los trades)

### Problema actual
`auto-adjust: false`. El bot nunca aprende qué setups funcionan.

### Solución

#### A. Journal por setup
```java
@Entity
public class TradeJournal {
    String setupType;       // "LONG_MeanReversion", "SHORT_Breakout", etc.
    String context;          // "uptrend", "downtrend", "ranging"
    double rsiAtEntry;
    double volumeAtEntry;
    double atrAtEntry;
    String session;          // "Asia", "London", "NY"
    boolean profitable;
    double pnlPct;
    String exitReason;
}
```

#### B. Auto-adjust (ejecutar cada N trades)
```java
// Cada 20 trades, analizar win rate por setup
Map<String, List<TradeJournal>> bySetup = journal.stream()
    .collect(Collectors.groupingBy(TradeJournal::getSetupType));

for (Map.Entry<String, List<TradeJournal>> entry : bySetup.entrySet()) {
    List<TradeJournal> trades = entry.getValue();
    double winRate = trades.stream().filter(TradeJournal::isProfitable).count() / (double) trades.size();
    
    if (winRate < 0.30) {
        // Desactivar este setup temporalmente
        logger.warn("Setup {} has win rate {}% — disabling", entry.getKey(), winRate * 100);
        disabledSetups.add(entry.getKey());
    }
}
```

#### C. Ajuste de parámetros por performance
```java
// Si LONG mean-reversion tiene win rate bajo en uptrend
// Subir rsi-oversold-threshold (requir RSI más bajo para entrar)
// O bajar el volumen mínimo
```

### Configuración
```yaml
auto-adjust:
  enabled: true
  min-samples: 20
  adjustment-delta-pct: 5.0
  disable-threshold-win-rate: 0.30  # Desactivar setup si WR < 30%
  review-interval-days: 7
```

---

## 6. REEMPLAZAR TIME EXIT (35 min) POR MOMENTUM EXIT

### Problema actual
Time exit es un timer arbitrario. Un trade puede estar a punto de explotar a los 34 min y el bot lo cierra.

### Solución: Momentum Exit
```java
private void checkMomentumExit(Trade trade, Kline currentKline, double currentMomentum) {
    // Si el momentum se ha reducido significativamente desde el entry
    // y no estamos cerca del TP → salir
    
    double entryMomentum = trade.getMomentumAtEntry();  // nuevo campo
    
    if (currentMomentum < entryMomentum * 0.3) {
        // Momentum cayó 70% desde el entry
        // Y precio no avanzó más del 50% hacia el TP
        double progressToTp = Math.abs(currentPrice - entryPrice) / Math.abs(tpPrice - entryPrice);
        
        if (progressToTp < 0.5) {
            logger.info("Momentum exit for Trade {}: momentum dropped 70%, trade stalled", trade.getId());
            closeTrade(trade, currentPrice, "MOMENTUM_EXIT");
        }
    }
}
```

#### Alternativa: Range Contraction Exit
```java
// Si el rango de las últimas 3 velas es menor al 30% del ATR
// → el mercado se durmió, salir
recentRange = calcularRango(klines, 3);
if (recentRange < atr * 0.3 && !cercaDelTP()) {
    closeTrade(trade, currentPrice, "RANGE_CONTRACTION");
}
```

### Configuración
```yaml
exit-strategy:
  type: MOMENTUM          # TIME, MOMENTUM, RANGE, COMBINED
  max-hold-minutes: 35    # Fallback si MOMENTUM no se activa
  momentum-drop-threshold: 0.70   # Salir si momentum cae 70%
  range-contraction-threshold: 0.30  # Salir si rango < 30% ATR
```

---

## 7. VOLUMEN MEJORADO (Delta Volume)

### Problema actual
`relativeVolume = volActual / volPromedio`. No distingue si el volumen es compras o ventas.

### Solución
```java
// Usar taker buy/sell volume ratio de Binance
// O estimar con el delta de volumen (aproximación)

// Si el precio sube en una vela, asumir que el volumen fue mayoritariamente compras
if (close > open) {
    buyVolume = volume * 0.6;  // estimación
    sellVolume = volume * 0.4;
} else {
    buyVolume = volume * 0.4;
    sellVolume = volume * 0.6;
}

volumeDelta = buyVolume - sellVolume;

// Para LONG: preferir delta positivo (más compras que ventas)
// Para SHORT: preferir delta negativo (más ventas que compras)
```

### Configuración
```yaml
use-volume-delta: true
volume-delta-min-ratio: 0.2   # Delta debe ser > 20% del volumen total
```

---

## 8. RESUMEN DE CAMBIOS

| # | Cambio | Archivos afectados | Complejidad | Impacto |
|---|--------|-------------------|-------------|---------|
| 1 | ATR stops | `TradeManager`, `application.yml` | Media | **Crítico** |
| 2 | Filtro sesión | `HypeStrategy`, `application.yml` | Baja | Alto |
| 3 | Trend-Dip SHORT | `HypeStrategy` | Media | Medio |
| 4 | Size dinámico | `TradeManager`, `application.yml` | Media | Alto |
| 5 | Journal + auto-adjust | Nuevos archivos: `TradeJournal`, `TradeJournalRepository`, `AutoAdjustService` | Alta | **Crítico** |
| 6 | Momentum exit | `TradeManager`, `Trade` entity | Media | Alto |
| 7 | Delta volume | `HypeStrategy`, `MarketContextAnalyzer` | Media | Medio |

---

## 9. PUNTUACIÓN PROYECTADA

| Versión | Cambios | Puntaje | Estado |
|---------|---------|---------|--------|
| **Actual** | — | 6.5/10 | "Bot casero decente" |
| **+ ATR stops** | #1 | 7.5/10 | "Ya respeta la volatilidad" |
| **+ Filtro sesión** | #2 | 8.0/10 | "No opera en sesiones muertas" |
| **+ Trend-Dip SHORT** | #3 | 8.2/10 | "Estrategia simétrica" |
| **+ Size dinámico** | #4 | 8.5/10 | "Gestiona riesgo como un pro" |
| **+ Momentum exit** | #6 | 8.8/10 | "Sale por lectura, no por timer" |
| **+ Delta volume** | #7 | 9.0/10 | "Lee el order flow" |
| **+ Journal + auto-adjust** | #5 | **9.5/10** | **"Aprende y mejora solo"** |

### Versión Final: 9.5/10
> "Este bot ya no es un bot casero. Es un sistema de trading que respeta la volatilidad, aprende de sus errores, y opera solo cuando hay condiciones reales. Un scalper pro podría usarlo sin modificar nada."

---

## 10. PLAN DE IMPLEMENTACIÓN

### Sprint 1 (Esta semana): Fundamentos
- [ ] Implementar ATR stops (#1)
- [ ] Implementar filtro de sesión (#2)
- [ ] Implementar Trend-Dip SHORT (#3)
- [ ] Pushear y validar en testnet (mínimo 15 trades)

### Sprint 2 (Próxima semana): Risk Management
- [ ] Implementar size dinámico (#4)
- [ ] Implementar momentum exit (#6)
- [ ] Pushear y validar en testnet (mínimo 15 trades más)

### Sprint 3 (Semana 3): Inteligencia
- [ ] Implementar journal + auto-adjust (#5)
- [ ] Implementar delta volume (#7)
- [ ] Pushear y validar en testnet (mínimo 20 trades)

### Sprint 4 (Semana 4): Producción
- [ ] Revisar métricas finales: win rate, profit factor, max drawdown
- [ ] Ajustar parámetros según journal
- [ ] Cambiar a producción con size conservador (5%, leverage 3x)

**Total: 4 semanas para pasar de 6.5 a 9.5/10**

---

*Documento generado el 13 Jun 2026*
