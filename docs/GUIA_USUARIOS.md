# Guía de Alta y Uso — Trading Assistant

## 1. Planes disponibles

| Plan | Bots | Capital máx. | Precio sugerido |
|------|------|-------------|-----------------|
| **FREE** | 1 | $500 USD | Gratis (demo/prueba) |
| **STARTER** | 1 | $500 USD | Acceso básico |
| **PRO** | 3 | $10.000 USD | Multi-símbolo |
| **ENTERPRISE** | 5 | Ilimitado | Full acceso |

> Los límites de bots y capital son aplicados en el backend. El plan se asigna en el momento del registro.

---

## 2. Alta de usuario (Admin)

### Opción A — Llamada directa a la API

```bash
curl -X POST https://TU_BACKEND_URL/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "usuario@email.com",
    "password": "ClaveSegura123",
    "plan": "PRO"
  }'
```

**Respuesta exitosa:**
```json
{
  "token": "eyJhbG...",
  "userId": 5,
  "email": "usuario@email.com",
  "plan": "PRO",
  "maxBots": 3
}
```

### Opción B — Via interfaz (si se habilita página de registro)
Actualmente el registro está abierto en `/api/auth/register` sin autenticación. Cualquiera que conozca la URL puede registrarse. Si se quiere restringir, agregar token de invitación (ver Roadmap).

### Planes válidos para el campo `plan`:
- `FREE`
- `STARTER`
- `PRO`
- `ENTERPRISE`

---

## 3. Primeros pasos del usuario (flujo completo)

### Paso 1 — Login
El usuario ingresa con email y contraseña en la pantalla de login.
- Si tiene **2FA activo**: verá una segunda pantalla pidiendo el código de 6 dígitos de Google Authenticator / Authy.

### Paso 2 — Crear un Bot
En la sección **Perfil**:
1. Click en "Nuevo Bot"
2. Completar:
   - **Nombre**: ej. `Bot HYPE`
   - **Símbolo**: `HYPEUSDT` o `SOLUSDT` (futuros Binance)
   - **Capital**: monto en USD a usar (no puede superar el límite del plan)
3. El bot queda creado en estado **inactivo**

### Paso 3 — Activar el Bot
- Usar el toggle en la tarjeta del bot para activarlo
- El bot se suma al ciclo de estrategia global (cada 2 minutos)

### Paso 4 — Configurar Telegram (opcional pero recomendado)
Para recibir alertas de trades en tiempo real:
1. Ir a **Perfil → Telegram**
2. Buscar `@userinfobot` en Telegram → enviar `/start` → copiar tu Chat ID
3. Pegar el Chat ID en el campo y guardar

```bash
# También se puede configurar via API:
curl -X PUT https://TU_BACKEND_URL/api/users/me/telegram \
  -H "Authorization: Bearer TU_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chatId": "123456789"}'
```

### Paso 5 — Activar 2FA (recomendado)
1. Ir a **Perfil → Seguridad**
2. Click en "Activar 2FA"
3. Escanear el QR con Google Authenticator o Authy
4. Ingresar el código de 6 dígitos para confirmar

```bash
# Paso 1: obtener QR
curl -X POST https://TU_BACKEND_URL/api/auth/2fa/setup \
  -H "Authorization: Bearer TU_TOKEN"
# Devuelve: { "secret": "...", "otpauthUrl": "otpauth://totp/..." }

# Paso 2: confirmar con primer OTP
curl -X POST https://TU_BACKEND_URL/api/auth/2fa/enable \
  -H "Authorization: Bearer TU_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"secret": "SECRET_DEL_PASO_1", "otp": "123456"}'
```

---

## 4. Secciones de la app — ejemplos de uso

### Dashboard
- Resumen de capital, PnL total, trades abiertos
- Botón **Iniciar/Pausar estrategia** (global)
- Tabla de últimos trades con símbolo, acción, PnL

### Trades
- Historial completo con filtros: ALL / OPEN / CLOSED
- Ver precio de entrada, SL, TP, motivo de salida

### Performance
- **Calendario mensual** de resultados
- Cada celda muestra: PnL del día, Win Rate del día, cantidad de trades
- Los días sin actividad no se muestran

### Market
- Vista de mercado y señales recientes

### Config
- Parámetros ajustables de la estrategia (RSI thresholds, ATR, etc.)

### Perfil
- Gestión de bots (crear, activar/desactivar, eliminar)
- Configurar Telegram Chat ID
- Activar/desactivar 2FA

---

## 5. Qué hace el bot automáticamente

El bot ejecuta la estrategia **cada 2 minutos** para cada símbolo activo:

1. **Analiza contexto de mercado** (tendencias 1h/4h/1d, volumen relativo, correlación BTC)
2. **Calcula indicadores técnicos** en velas de 15m: RSI, EMA, VWAP, canal de regresión lineal, volatilidad ATR
3. **Evalúa entrada LONG o SHORT** según múltiples condiciones
4. **Ejecuta la orden** vía Binance Futures Testnet (modo real con API keys reales cuando estén configuradas)
5. **Gestiona la posición**: trailing stop, time exit, SL/TP automáticos
6. **Notifica por Telegram** cuando abre o cierra una posición

---

## 6. Limitaciones actuales (modo testnet)

- Las órdenes se ejecutan en **Binance Futures Testnet** (dinero simulado)
- Para operar con dinero real, el usuario debe:
  1. Generar API keys en Binance Futures mainnet
  2. Configurarlas en la sección Config (encriptadas en backend)
- Los parámetros están calibrados para **HYPEUSDT** y funcionan de forma similar en **SOLUSDT**

---

## 7. Soporte

Para issues o preguntas, el admin puede verificar el estado del sistema:

```bash
# Health check
curl https://TU_BACKEND_URL/api/health

# Estado de la estrategia (requiere auth)
curl https://TU_BACKEND_URL/api/strategy/status \
  -H "Authorization: Bearer TU_TOKEN"
```
