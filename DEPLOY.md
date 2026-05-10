# 🚀 Deployment Guide - Trading Assistant

Guía completa para deployar Trading Assistant en Railway y empezar a operar.

## 📋 Pre-requisitos

Antes de empezar, necesitas:

1. **Cuenta GitHub** - Para alojar el código
2. **Cuenta Railway** - Para hosting (https://railway.app)
3. **Cuenta Binance** - Para trading
4. **API Keys de Binance Testnet** - Para pruebas

---

## 🔧 Paso 1: Preparar API Keys

### 1.1 Binance Testnet (Para Pruebas)

1. Ve a https://testnet.binance.vision/
2. Inicia sesión con tu cuenta GitHub
3. Genera **API Key** y **Secret Key**
4. Guarda ambos valores (no se muestran de nuevo)

### 1.2 Telegram Bot (Opcional pero recomendado)

1. Abre Telegram y busca **@BotFather**
2. Envía `/newbot`
3. Sigue las instrucciones (nombre y username)
4. Recibirás tu **Bot Token** (ej: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)
5. Guarda el token

6. Busca **@userinfobot** en Telegram
7. Envía `/start`
8. Recibirás tu **Chat ID** (ej: `123456789`)
9. Guarda el chat ID

---

## 🐙 Paso 2: Subir a GitHub

```bash
# En el directorio trading-assistant
git init
git add .
git commit -m "Initial commit - MVP Trading Assistant"

# Crear repositorio en GitHub primero, luego:
git remote add origin https://github.com/tu-usuario/trading-assistant.git
git push -u origin main
```

---

## 🚂 Paso 3: Deploy en Railway

### 3.1 Crear Proyecto

1. Ve a https://railway.app/dashboard
2. Click **"New Project"**
3. Selecciona **"Deploy from GitHub repo"**
4. Busca y selecciona `trading-assistant`
5. Click **"Deploy"**

### 3.2 Agregar PostgreSQL

1. En tu proyecto Railway, click **"New"**
2. Selecciona **"Database"** → **"Add PostgreSQL"**
3. Railway creará automáticamente la DB

### 3.3 Configurar Variables de Entorno

Ve a tu servicio en Railway → **Variables** → **New Variable**:

```
# Railway genera automáticamente:
DATABASE_URL=${{Postgres.DATABASE_URL}}
DATABASE_USERNAME=${{Postgres.DATABASE_USERNAME}}
DATABASE_PASSWORD=${{Postgres.DATABASE_PASSWORD}}

# Agregar manualmente:
BINANCE_API_KEY=tu_binance_api_key
BINANCE_SECRET_KEY=tu_binance_secret_key
BINANCE_TESTNET=true
BINANCE_BASE_URL=https://testnet.binance.vision

TELEGRAM_BOT_TOKEN=tu_telegram_bot_token
TELEGRAM_CHAT_ID=tu_chat_id
TELEGRAM_ENABLED=true

JWT_SECRET=generar_random_string_largo_aqui_32_chars_min

TRADING_STRATEGY_ENABLED=true
```

### 3.4 Deploy Automático

Railway detectará el `railway.toml` y hará deploy automático.

Espera a que el deploy esté **"Healthy"** (verde).

---

## 🌐 Paso 4: Obtener URL y Probar

### 4.1 URL de la App

1. En Railway, ve a tu servicio
2. Click en **Settings** → **Domains**
3. Railway te da una URL automática: `https://trading-assistant-production.up.railway.app`

### 4.2 Probar Endpoints

```bash
# Health check
curl https://tu-url.railway.app/actuator/health

# Dashboard summary
curl https://tu-url.railway.app/api/dashboard/summary

# Strategy status
curl https://tu-url.railway.app/api/strategy/status
```

### 4.3 Swagger UI

Abre en navegador:
```
https://tu-url.railway.app/swagger-ui.html
```

---

## 🔒 Paso 5: Configurar Dominio Personalizado (Opcional)

1. En Railway → **Settings** → **Domains**
2. Click **"Generate Domain"** o **"Custom Domain"**
3. Si usas custom domain, configura el CNAME en tu DNS

---

## 📊 Paso 6: Monitoreo y Logs

### 6.1 Ver Logs en Railway

1. Ve a tu servicio en Railway
2. Click en **"Deployments"**
3. Click en el deploy activo
4. Click **"View Logs"**

### 6.2 Logs Importantes a Verificar

```
✅ Trading Assistant started successfully
✅ Binance client configured for testnet
✅ Strategy executed - RSI: 28.5, Buy Zone: true, Momentum: 1.2%
🟢 LONG SIGNAL DETECTED!
✅ LONG trade executed successfully
✅ Trade X closed. Reason: TAKE_PROFIT, P&L: $31.81 (7.78%)
```

---

## 🧪 Paso 7: Testing en Testnet (1 semana)

### 7.1 Verificar Funcionamiento

1. El bot ejecuta cada 15 minutos automáticamente
2. Revisa logs cada día
3. Verifica trades en `/api/dashboard/trades`
4. Chequea que las métricas se calculan en `/api/dashboard/metrics`

### 7.2 Métricas a Validar

Después de 1 semana (aprox. 672 ejecuciones):

- ✅ Trades ejecutados: 5-15 trades
- ✅ Win Rate: ~50%
- ✅ Profit Factor: >2.0
- ✅ Sin errores críticos en logs

---

## 🚀 Paso 8: Switch a Producción (Live Trading)

### 8.1 Cambiar a Binance Live

1. Genera API Keys en https://www.binance.com/en/my/settings/api-management
2. **IMPORTANTE**: Restringe IP (usa la IP de Railway)
3. Actualiza variables en Railway:

```
BINANCE_API_KEY=tu_live_api_key
BINANCE_SECRET_KEY=tu_live_secret
BINANCE_TESTNET=false
BINANCE_BASE_URL=https://api.binance.com
```

### 8.2 Redeploy

Railway redeployará automáticamente con las nuevas variables.

### 8.3 Verificación Final

```bash
# Confirmar que está en modo live
curl https://tu-url.railway.app/api/strategy/status

# Debería mostrar: "Binance: LIVE"
```

---

## 📱 Paso 9: Configurar Notificaciones Telegram

### 9.1 Enviar mensaje de prueba

```bash
curl -X POST https://api.telegram.org/bot[TU_TOKEN]/sendMessage \
  -d chat_id=[TU_CHAT_ID] \
  -d text="🚀 Trading Assistant conectado!"
```

### 9.2 Verificar en app

Los mensajes deberían llegar cuando:
- 🟢 Se ejecuta un LONG
- 🎯 Se cierra por Take Profit
- 🛑 Se cierra por Stop Loss
- 🔄 Cambios de estado

---

## 🔧 Troubleshooting

### Problema: "Binance not configured"

**Solución**: Verifica que las variables `BINANCE_API_KEY` y `BINANCE_SECRET_KEY` estén configuradas en Railway.

### Problema: "Database connection failed"

**Solución**: 
1. Verifica que PostgreSQL esté agregado al proyecto
2. Railway debería auto-generar `DATABASE_URL`
3. Si no, crea la variable manualmente

### Problema: "Application failed to start"

**Solución**: Revisa logs en Railway:
1. Ve a Deployments
2. Click en el deploy fallido
3. View Logs → busca errores

### Problema: "403 Forbidden" en Swagger

**Solución**: En `SecurityConfig.java`, las rutas Swagger deben ser públicas (ya está configurado).

---

## 💰 Costos en Railway

| Servicio | Tier | Costo/mes |
|----------|------|-----------|
| **App** | Starter | $5 |
| **PostgreSQL** | Starter | $0-5 |
| **Total** | | **$5-10/mes** |

**Tier Gratis**: Railway ofrece $5 créditos mensuales gratis (suficiente para Starter).

---

## 📞 Soporte

Si tienes problemas:

1. **Railway Docs**: https://docs.railway.app/
2. **Binance API Docs**: https://binance-docs.github.io/apidocs/
3. **Spring Boot Docs**: https://spring.io/projects/spring-boot

---

## ✅ Checklist Final

Antes de considerar el deploy exitoso:

- [ ] API Keys de Binance Testnet configuradas
- [ ] Telegram Bot configurado (opcional)
- [ ] Código en GitHub
- [ ] Proyecto creado en Railway
- [ ] PostgreSQL agregado al proyecto
- [ ] Variables de entorno configuradas
- [ ] Deploy exitoso (Healthy status)
- [ ] Endpoints respondiendo correctamente
- [ ] Swagger UI accesible
- [ ] Logs mostrando ejecución normal
- [ ] 1 semana de testnet sin errores
- [ ] Switch a Binance Live completado

---

**¡Listo para operar! 🚀**

Recuerda: Siempre empieza con Testnet, nunca operes con dinero real sin validar primero.
