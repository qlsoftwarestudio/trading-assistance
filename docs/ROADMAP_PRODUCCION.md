# Roadmap: De 8/10 a 9/10 — Producción Real

Estado actual: **8/10** — Beta funcional, multi-símbolo, 2FA completo, thread-safe.

---

## Prioridad ALTA — Bloquean producción real

### 1. WebSockets en frontend (reemplazar polling)

**Problema actual:** el Dashboard hace polling REST cada 5s/8s. En producción con múltiples usuarios esto genera carga innecesaria.

**Solución:** Server-Sent Events (SSE) — más simple que STOMP/SockJS, unidireccional (backend → frontend), sin librerías adicionales.

**Backend** (`DashboardController.java`):
```java
@GetMapping(value = "/dashboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamDashboard(@RequestHeader("Authorization") String authHeader) {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    // registrar emitter en un SseEmitterService
    // el servicio pushea cuando hay nuevo trade o cambio de estado
    return emitter;
}
```

**Frontend** (`Dashboard.tsx`) — reemplazar `refetchInterval`:
```typescript
useEffect(() => {
  const es = new EventSource(`${API_URL}/api/dashboard/stream`, {
    // incluir token via query param o cookie
  });
  es.onmessage = (e) => {
    const data = JSON.parse(e.data);
    queryClient.setQueryData(["summary"], data);
  };
  return () => es.close();
}, []);
```

**Archivos a modificar:**
- `DashboardController.java` — añadir endpoint SSE
- Nuevo `SseEmitterService.java` — gestión de emitters por usuario
- `TradeManager.java` — llamar a `sseService.push(userId, event)` al abrir/cerrar trade
- `Dashboard.tsx` — reemplazar `refetchInterval: 5_000` y `refetchInterval: 8_000`

---

### 2. Binance Mainnet (API keys reales)

**Problema actual:** `BinanceClient` apunta a `https://testnet.binancefuture.com`.

**Cambio mínimo en `BinanceClient.java`:**
```java
@Value("${binance.use-testnet:true}")
private boolean useTestnet;

// En @PostConstruct:
String baseUrl = useTestnet
    ? "https://testnet.binancefuture.com"
    : "https://fapi.binance.com";
```

**Variable de entorno en Railway:**
```
BINANCE_USE_TESTNET=false
BINANCE_API_KEY=<mainnet_key>
BINANCE_API_SECRET=<mainnet_secret>
```

**WebSocket User Data Stream:** el stream de `wss://fapi.binance.com/ws/` para SL/TP ya está implementado. Solo necesita apuntar al endpoint correcto al cambiar de testnet a mainnet.

---

### 3. Flyway — migraciones controladas (reemplazar `ddl-auto=update`)

**Problema actual:** `spring.jpa.hibernate.ddl-auto=update` en producción es peligroso si hay cambios de schema complejos.

**Pasos:**
```xml
<!-- build.gradle -->
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
```

```properties
# application.properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

**Crear:** `src/main/resources/db/migration/V1__initial_schema.sql` con el schema actual.
Cada cambio futuro: `V2__add_column_x.sql`, etc.

---

## Prioridad MEDIA — Mejoran robustez

### 4. Rate limiting en login

Previene ataques de fuerza bruta.

**Dependencia:**
```xml
implementation 'com.github.vladimir-bukhtoyarov:bucket4j-core:8.x'
```

**En `AuthController.login()`:**
```java
private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

private Bucket getBucket(String ip) {
    return buckets.computeIfAbsent(ip, k ->
        Bucket.builder()
            .addLimit(Bandwidth.simple(5, Duration.ofMinutes(1)))
            .build());
}
// Rechazar si !bucket.tryConsume(1)
```

---

### 5. Per-user strategy toggle

**Problema actual:** el botón "Pausar" del dashboard detiene la estrategia para **todos** los usuarios.

**Solución:** agregar campo `running` a la entidad `Bot`, y en el loop de `HypeStrategy`:
```java
// Ya existe bot.isEnabled() y bot.isRunning()
// El toggle del dashboard debería cambiar solo los bots del usuario autenticado
```

Modificar `DashboardController.toggleSwingStrategy()` para filtrar por `userId` del token.

---

### 6. Gestión de riesgo global por usuario

**Problema actual:** si un usuario tiene 3 bots (plan PRO), pueden abrirse 3 posiciones simultáneas sin límite combinado de capital.

**Solución en `TradeManager.executeEntry()`:**
```java
double totalExposed = tradeRepository
    .findByUserIdAndStatus(userId, TradeStatus.OPEN)
    .stream()
    .mapToDouble(t -> t.getCapital().doubleValue())
    .sum();

double maxCapital = userRepository.findById(userId)
    .map(u -> u.getPlan().getMaxCapitalUsd())
    .orElse(500.0);

if (maxCapital > 0 && totalExposed + signal.getCapital() > maxCapital) {
    logger.warn("Capital máximo alcanzado para userId={}. Trade bloqueado.", userId);
    return;
}
```

---

### 7. Tests de integración mínimos

Para no deployar código roto:

```java
// TradeManagerIntegrationTest.java
@SpringBootTest
@Transactional
class TradeManagerIntegrationTest {
    @Test
    void openAndCloseTrade_shouldUpdateDailyMetrics() { ... }

    @Test
    void capitalLimit_shouldBlockNewTrade() { ... }
}
```

**Frontend (Vitest):**
```typescript
// Login.test.tsx
test("muestra pantalla OTP cuando twoFactorRequired=true", async () => { ... });
```

---

## Prioridad BAJA — Pulido final

### 8. Restricción de registro (invitación)

Actualmente `/api/auth/register` es público. Para cerrar el acceso:

```java
// AuthController.register()
@Value("${app.registration.invite-code:}")
private String inviteCode;

if (!inviteCode.isBlank() && !inviteCode.equals(request.get("inviteCode"))) {
    return ResponseEntity.status(403).body(Map.of("error", "Código de invitación inválido"));
}
```

Variable: `APP_REGISTRATION_INVITE_CODE=<secreto>` en Railway.

---

### 9. Alertas de error por Telegram al admin

Cuando el backend lanza una excepción crítica (ej. fallo de Binance API), notificar al admin:

```java
// En HypeStrategy.catch():
telegramBot.sendAdminAlert("⚠️ Error en estrategia: " + e.getMessage());
```

---

### 10. Vista de performance por símbolo

Actualmente el calendario de performance mezcla todos los bots. Agregar filtro por símbolo en:
- `GET /api/dashboard/daily-metrics?symbol=HYPEUSDT`
- Frontend: selector de símbolo en la página Performance

---

## Checklist de deploy a producción

```
[ ] 1. WebSocket/SSE para Dashboard y Trades
[ ] 2. Binance mainnet API keys configuradas en Railway
[ ] 3. BINANCE_USE_TESTNET=false en variables de entorno
[ ] 4. Flyway migraciones (reemplazar ddl-auto=update)
[ ] 5. Rate limiting en /api/auth/login
[ ] 6. Per-user strategy toggle
[ ] 7. Capital limit check en TradeManager
[ ] 8. Invite code para registro (opcional)
[ ] 9. Al menos 1 test de integración backend + 1 frontend
[ ] 10. Smoke test manual: login → crear bot → activar → verificar trade simulado
```

---

## Estimación de esfuerzo

| Item | Esfuerzo | Impacto |
|------|---------|---------|
| SSE en Dashboard | 4-6h | Alto |
| Mainnet switch | 1h | Crítico |
| Flyway | 2-3h | Alto |
| Rate limiting | 1-2h | Medio |
| Per-user toggle | 2h | Medio |
| Capital limit | 1h | Alto |
| Tests mínimos | 4-6h | Medio |
| Invite code | 1h | Bajo |

**Total estimado:** ~2-3 días de desarrollo para llegar a 9/10 en producción real.
