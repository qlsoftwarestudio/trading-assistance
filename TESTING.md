# 🧪 Testing & Validación - Trading Assistant

Guía completa para verificar que todo funciona correctamente antes de hacer deploy.

---

## ✅ Lista de Verificación Pre-Deploy

### 1. Estructura del Proyecto

```bash
# Verificar que todos los archivos existen
ls -la trading-assistant/
```

**Deberías ver:**
- ✅ `build.gradle`
- ✅ `settings.gradle`
- ✅ `Dockerfile`
- ✅ `docker-compose.yml`
- ✅ `run.sh`
- ✅ `railway.toml`
- ✅ `application.yml`
- ✅ `schema.sql`
- ✅ `.env.example`
- ✅ Carpeta `src/` con archivos Java

### 2. Archivos Java Creados (18 archivos)

```
src/main/java/com/trading/assistant/
├── TradingAssistantApplication.java     ✅ Main class
├── config/
│   ├── SecurityConfig.java              ✅ Seguridad
│   ├── SchedulerConfig.java             ✅ Scheduler
│   └── WebSocketConfig.java             ✅ WebSocket
├── binance/
│   ├── BinanceClient.java               ✅ API Binance
│   └── config/BinanceConfig.java        ✅ Config Binance
├── strategy/
│   ├── Btc15mStrategy.java              ✅ Estrategia 15m
│   ├── IndicatorCalculator.java         ✅ Indicadores técnicos
│   ├── model/Signal.java                ✅ Modelo señales
│   └── repository/SignalRepository.java ✅ Repo señales
├── execution/
│   └── TradeManager.java                ✅ Gestión trades
├── portfolio/
│   ├── PortfolioService.java            ✅ Métricas
│   ├── model/Trade.java                 ✅ Modelo trades
│   ├── model/DailyMetrics.java          ✅ Métricas diarias
│   ├── repository/TradeRepository.java  ✅ Repo trades
│   └── repository/DailyMetricsRepository.java
├── notification/
│   └── TelegramBot.java                 ✅ Notificaciones
└── api/
    └── DashboardController.java         ✅ API REST
```

---

## 🔧 Paso 1: Validar Configuración Gradle

### 1.1 Verificar `build.gradle`

```bash
cat trading-assistant/build.gradle
```

**Verifica que contenga:**
- ✅ `spring-boot-starter-web`
- ✅ `spring-boot-starter-data-jpa`
- ✅ `postgresql` (runtime)
- ✅ `binance-connector-java:3.2.0`
- ✅ `telegrambots:6.9.7.1`
- ✅ `springdoc-openapi-starter-webmvc-ui:2.5.0`

### 1.2 Verificar Java 21

```bash
java -version
# Debería mostrar: openjdk version "21"
```

---

## 🐳 Paso 2: Test con Docker (Local)

### 2.1 Docker Compose Build

```bash
cd trading-assistant

# Verificar que Docker está corriendo
docker --version
docker-compose --version

# Build de la imagen
docker-compose build

# Si hay errores, revisar el Dockerfile
cat Dockerfile
```

### 2.2 Levantar Servicios

```bash
# Opción 1: Script automático
./run.sh

# Opción 2: Manual
docker-compose up -d

# Verificar que los contenedores están corriendo
docker ps

# Deberías ver:
# - trading-postgres (puerto 5433)
# - trading-assistant (puerto 8080)
```

### 2.3 Verificar Logs

```bash
# Ver logs de la aplicación
docker logs -f trading-assistant

# Buscar mensajes:
# ✅ "TradingAssistantApplication started"
# ✅ "Binance client configured"
# ✅ "Strategy: BTCUSD 15m SOLO LONG"
```

### 2.4 Health Check

```bash
# Esperar 30 segundos después del inicio
sleep 30

# Test de salud
curl http://localhost:8080/actuator/health

# Respuesta esperada:
# {"status":"UP"}
```

---

## 📚 Paso 3: Verificar Swagger/OpenAPI

### 3.1 Acceder a Swagger UI

```
http://localhost:8080/swagger-ui.html
```

**Deberías ver:**
- ✅ Interfaz Swagger UI cargada
- ✅ Título: "Trading Assistant API"
- ✅ Endpoints listados:
  - `GET /api/health`
  - `GET /api/dashboard/summary`
  - `GET /api/dashboard/trades`
  - `GET /api/dashboard/signals`
  - `GET /api/strategy/status`
  - `POST /api/strategy/execute`

### 3.2 Test de Endpoints desde Swagger

1. Expandir `GET /api/dashboard/summary`
2. Click en **"Try it out"**
3. Click en **"Execute"**
4. **Respuesta esperada:**
   ```json
   {
     "balance": 2000.00,
     "totalTrades": 0,
     "winningTrades": 0,
     "losingTrades": 0,
     "openTrades": 0,
     "winRate": 0,
     "totalPnl": 0,
     "profitFactor": 0,
     "currentPrice": 45000.00
   }
   ```

---

## 🧪 Paso 4: Test de Funcionalidad

### 4.1 Test Manual de Estrategia

```bash
# Ejecutar estrategia manualmente
curl -X POST http://localhost:8080/api/strategy/execute

# Respuesta esperada:
# {"message":"Strategy executed manually","timestamp":"..."}
```

### 4.2 Verificar Logs de Ejecución

```bash
docker logs trading-assistant | tail -50
```

**Deberías ver en logs:**
```
Executing BTCUSD 15m SOLO LONG strategy...
Indicators - RSI: 45.20, Session Low: 44000.00, Momentum: 0.85%, In Buy Zone: false
No signal. Conditions - RSI Oversold: false, Buy Zone: false, Strong Momentum: true
```

### 4.3 Verificar Base de Datos

```bash
# Conectar a PostgreSQL
docker exec -it trading-postgres psql -U trading -d tradingassistant

# Ver tablas creadas
\dt

# Deberías ver:
# trades
# signals
# daily_metrics
# balance_history

# Ver señales generadas
SELECT * FROM signals ORDER BY generated_at DESC LIMIT 5;

# Salir
\q
```

---

## 🔍 Paso 5: Validación de Dependencias

### 5.1 Binance Client (Modo Demo)

```bash
# Sin API keys configuradas, debería funcionar en modo demo
# Verificar en application.yml que BINANCE_TESTNET=true
```

### 5.2 Telegram Bot (Opcional)

```bash
# Si no configuraste Telegram, debería funcionar sin errores
# Los mensajes se loguean pero no se envían
docker logs trading-assistant | grep -i telegram

# Debería mostrar:
# Telegram notifications disabled or not configured
```

---

## ⚠️ Paso 6: Errores Comunes y Soluciones

### Error: "Connection refused" a PostgreSQL

**Síntoma:** La app no conecta a la base de datos

**Solución:**
```bash
# Verificar que PostgreSQL está corriendo
docker ps | grep postgres

# Reiniciar servicios
docker-compose down
docker-compose up -d

# Esperar 10 segundos y verificar
sleep 10
docker logs trading-assistant | grep "Database"
```

### Error: "Port already in use"

**Síntoma:** Puerto 8080 o 5433 ocupado

**Solución:**
```bash
# Encontrar proceso usando el puerto
lsof -i :8080

# Matar proceso o cambiar puerto en docker-compose.yml
```

### Error: "Failed to start bean 'webServerStartStop'"

**Síntoma:** Conflicto de puertos o errores en configuración

**Solución:**
```bash
# Limpiar todo
docker-compose down -v
docker system prune -f

# Rebuild limpio
docker-compose build --no-cache
docker-compose up -d
```

### Error: Gradle build falla

**Síntoma:** `./gradlew build` no compila

**Solución:**
```bash
# Limpiar Gradle
./gradlew clean

# Verificar permisos
chmod +x gradlew

# Build con stacktrace para ver errores
./gradlew build --stacktrace
```

---

## 🎯 Paso 7: Tests Automáticos (GitHub Actions)

### 7.1 Simular GitHub Actions Localmente

```bash
# Instalar act (GitHub Actions local)
# https://github.com/nektos/act

# Ejecutar workflow
act push
```

### 7.2 Tests Unitarios Básicos

```bash
# Correr tests
./gradlew test

# Ver reporte
ls build/reports/tests/test/index.html
```

---

## ✅ Checklist Final de Validación

Antes de hacer deploy a Railway, verifica:

### Estructura ✅
- [ ] 18 archivos Java creados
- [ ] Todos los archivos de configuración presentes
- [ ] `build.gradle` tiene todas las dependencias

### Docker Local ✅
- [ ] `docker-compose build` exitoso
- [ ] Contenedores corriendo sin errores
- [ ] PostgreSQL accesible en puerto 5433

### Aplicación ✅
- [ ] Health check: `{"status":"UP"}`
- [ ] Swagger UI cargando en `:8080/swagger-ui.html`
- [ ] Endpoints respondiendo JSON correcto
- [ ] Logs mostrando ejecución de estrategia

### Base de Datos ✅
- [ ] Tablas creadas automáticamente
- [ ] Señales guardándose en `signals`
- [ ] Conexión estable sin timeouts

### Funcionalidad ✅
- [ ] Estrategia ejecuta cada 15 minutos
- [ ] Indicadores calculándose (RSI, Session Low, Momentum)
- [ ] Telegram en modo log (si no configurado)
- [ ] Sin errores de NullPointerException

---

## 📊 Resultados Esperados

### Primer Ejecución (Demo Mode)

```
Balance inicial: $2000.00
Current Price: ~$45000.00
Trades: 0
Señales generadas: 1 cada 15 min (HOLD o LONG)
```

### Después de 1 hora (4 ejecuciones)

```
Señales en DB: 4
Trades ejecutados: 0-1 (depende de condiciones de mercado)
Estado: HEALTHY
```

---

## 🚀 Siguiente Paso: Deploy a Railway

Si todo pasó las validaciones:

1. **Subir a GitHub:**
   ```bash
   git add .
   git commit -m "MVP ready for testing"
   git push origin main
   ```

2. **Seguir DEPLOY.md** para deploy en Railway

3. **Validar en Testnet** por 1 semana

4. **Switch a Live** cuando estés seguro

---

## 📞 Troubleshooting Avanzado

### Debug en tiempo real:

```bash
# Ver todas las señales generadas
curl http://localhost:8080/api/dashboard/signals

# Ejecutar estrategia manual y ver logs
curl -X POST http://localhost:8080/api/strategy/execute && \
docker logs trading-assistant --tail 20

# Monitorear trades abiertos
curl http://localhost:8080/api/dashboard/trades/open
```

### Inspeccionar base de datos:

```bash
# Entrar a PostgreSQL
docker exec -it trading-postgres psql -U trading -d tradingassistant

# Queries útiles:
SELECT COUNT(*) FROM signals;
SELECT * FROM trades WHERE status = 'OPEN';
SELECT * FROM daily_metrics ORDER BY date DESC LIMIT 1;
```

---

**¡Todo listo para deploy! 🚀**
