# ─────────────────────────────────────────────
# STAGE 1 — Build
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copier uniquement pom.xml d'abord pour profiter du cache Maven
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q

# Copier les sources et builder
COPY src ./src
RUN ./mvnw package -DskipTests -q

# ─────────────────────────────────────────────
# STAGE 2 — Runtime (image minimale)
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Utilisateur non-root pour la sécurité
RUN addgroup -S enova && adduser -S enova -G enova
USER enova

# Copier uniquement le JAR final
COPY --from=builder /app/target/*.jar app.jar

# Port exposé
EXPOSE 8081

# Variables d'environnement par défaut
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-Xmx512m -Xms256m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
