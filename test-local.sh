#!/bin/bash

# Script de Testing Local para Trading Assistant
# Ejecuta todas las validaciones automáticamente

set -e

echo "🧪 =========================================="
echo "🧪 Trading Assistant - Testing Local"
echo "🧪 =========================================="
echo ""

# Colores para output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Contador de tests
TESTS_PASSED=0
TESTS_FAILED=0

# Función para imprimir resultado
print_result() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✅ PASS${NC}: $2"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}❌ FAIL${NC}: $2"
        ((TESTS_FAILED++))
    fi
}

# Función para imprimir info
print_info() {
    echo -e "${YELLOW}ℹ️  INFO${NC}: $1"
}

echo "📋 PASO 1: Verificar estructura del proyecto"
echo "==========================================="

# Verificar archivos esenciales
FILES=(
    "build.gradle"
    "settings.gradle"
    "Dockerfile"
    "docker-compose.yml"
    "run.sh"
    "railway.toml"
    "src/main/resources/application.yml"
    "src/main/resources/schema.sql"
)

for file in "${FILES[@]}"; do
    if [ -f "$file" ]; then
        print_result 0 "Archivo existe: $file"
    else
        print_result 1 "Archivo falta: $file"
    fi
done

echo ""
echo "📁 PASO 2: Contar archivos Java"
echo "================================"

JAVA_COUNT=$(find src/main/java -name "*.java" 2>/dev/null | wc -l)
print_info "Archivos Java encontrados: $JAVA_COUNT"

if [ "$JAVA_COUNT" -eq 18 ]; then
    print_result 0 "Cantidad correcta de archivos Java (18)"
else
    print_result 1 "Se esperaban 18 archivos Java, se encontraron $JAVA_COUNT"
fi

echo ""
echo "🐳 PASO 3: Verificar Docker"
echo "=========================="

# Verificar Docker instalado
if command -v docker &> /dev/null; then
    print_result 0 "Docker instalado"
    docker --version
else
    print_result 1 "Docker no instalado"
fi

# Verificar Docker Compose
if command -v docker-compose &> /dev/null; then
    print_result 0 "Docker Compose instalado"
    docker-compose --version
else
    print_result 1 "Docker Compose no instalado"
fi

echo ""
echo "☕ PASO 4: Verificar Java"
echo "========================"

if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
    print_info "Java versión: $JAVA_VERSION"
    
    if [[ "$JAVA_VERSION" == 21* ]] || [[ "$JAVA_VERSION" == "21"* ]]; then
        print_result 0 "Java 21 detectado"
    else
        print_result 1 "Se requiere Java 21, se encontró $JAVA_VERSION"
    fi
else
    print_result 1 "Java no instalado"
fi

echo ""
echo "🔨 PASO 5: Validar build.gradle"
echo "================================"

# Verificar dependencias clave en build.gradle
DEPENDENCIES=(
    "spring-boot-starter-web"
    "postgresql"
    "binance-connector-java"
    "telegrambots"
    "springdoc-openapi"
)

for dep in "${DEPENDENCIES[@]}"; do
    if grep -q "$dep" build.gradle; then
        print_result 0 "Dependencia encontrada: $dep"
    else
        print_result 1 "Dependencia faltante: $dep"
    fi
done

echo ""
echo "🚀 PASO 6: Build de Gradle (Opcional)"
echo "======================================="

if [ -f "gradlew" ]; then
    print_info "Ejecutando Gradle build..."
    if ./gradlew build -x test --no-daemon > /tmp/gradle-build.log 2>&1; then
        print_result 0 "Gradle build exitoso"
    else
        print_result 1 "Gradle build falló (ver /tmp/gradle-build.log)"
        tail -20 /tmp/gradle-build.log
    fi
else
    print_info "gradlew no encontrado, saltando build de Gradle"
fi

echo ""
echo "🐋 PASO 7: Docker Compose Config"
echo "=================================="

# Validar docker-compose.yml
if docker-compose config > /dev/null 2>&1; then
    print_result 0 "docker-compose.yml es válido"
else
    print_result 1 "docker-compose.yml tiene errores de sintaxis"
fi

echo ""
echo "📊 RESUMEN DE TESTS"
echo "===================="
echo -e "${GREEN}✅ Tests Pasados: $TESTS_PASSED${NC}"
echo -e "${RED}❌ Tests Fallidos: $TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}🎉 ¡Todos los tests pasaron! Listo para deploy.${NC}"
    echo ""
    echo "Siguientes pasos:"
    echo "1. ./run.sh - Levantar localmente"
    echo "2. curl http://localhost:8080/actuator/health"
    echo "3. Abrir http://localhost:8080/swagger-ui.html"
    echo "4. Seguir DEPLOY.md para Railway"
    exit 0
else
    echo -e "${RED}⚠️  Algunos tests fallaron. Corregir antes de deploy.${NC}"
    echo ""
    echo "Revisa:"
    echo "- Archivos faltantes"
    echo "- Dependencias en build.gradle"
    echo "- Versión de Java (debe ser 21)"
    exit 1
fi
