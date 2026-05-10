#!/bin/bash

set -e

echo "🔌 Creando network si no existe..."
docker network create trading-network >/dev/null 2>&1 || true

echo "🗄️ Levantando PostgreSQL para Trading Assistant..."
docker rm -f trading-postgres >/dev/null 2>&1 || true

docker run -d \
  --name trading-postgres \
  --network trading-network \
  -e POSTGRES_DB=tradingassistant \
  -e POSTGRES_USER=trading \
  -e POSTGRES_PASSWORD=trading123 \
  -p 5433:5432 \
  -v trading-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine

echo "⏳ Esperando a que Postgres inicie..."
sleep 10

echo "🏗️ Build de Trading Assistant..."
docker build -t trading-assistant .

echo "🚀 Levantando Trading Assistant..."
docker rm -f trading-assistant >/dev/null 2>&1 || true

docker run -d \
  --name trading-assistant \
  --network trading-network \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://trading-postgres:5432/tradingassistant \
  -e SPRING_DATASOURCE_USERNAME=trading \
  -e SPRING_DATASOURCE_PASSWORD=trading123 \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=update \
  -e BINANCE_TESTNET=true \
  trading-assistant

echo "✅ Trading Assistant corriendo en http://localhost:8080"
echo "📊 PostgreSQL disponible en puerto 5433"
echo "📚 Swagger UI: http://localhost:8080/swagger-ui.html"
echo ""
echo "Endpoints disponibles:"
echo "  GET  /actuator/health"
echo "  GET  /api/dashboard/summary"
echo "  GET  /api/dashboard/trades"
