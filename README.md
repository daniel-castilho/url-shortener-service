# 🚀 High-Performance URL Shortener

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-green) ![Undertow](https://img.shields.io/badge/Undertow-High_Perf-blue) ![GraalVM](https://img.shields.io/badge/GraalVM-Native-orange)

An ultra-fast URL shortener built with **Spring Boot 3.5.7**, **Undertow** (replacing Tomcat), and ready for **GraalVM Native Image**. This project follows **Clean Architecture** principles to ensure maintainability and decoupling.

---

## 🏗️ Architecture

The project is structured to isolate the domain from infrastructure:

*   **🟢 Core (Domain)**: Pure business rules, no framework dependencies.
*   **🔵 Infra (Adapter)**: Spring implementations, Database (Cassandra), Cache (Redis), and Web Controllers.

### 📂 Directory Structure

```
src/main/java/com/example/urlshortener
├── core                           # 🧠 Domain (Pure Java)
│   ├── exception                  # Domain Exceptions
│   │   └── UrlNotFoundException.java
│   ├── model                      # Domain Entities
│   │   ├── ClickEvent.java
│   │   └── ShortUrl.java
│   ├── ports                      # Interfaces (Input/Output)
│   │   ├── incoming               # Use Cases
│   │   │   ├── GetUrlUseCase.java
│   │   │   └── ShortenUrlUseCase.java
│   │   └── outgoing               # Repository Ports
│   │       ├── AnalyticsPort.java
│   │       ├── IdGeneratorPort.java
│   │       ├── UrlCachePort.java
│   │       └── UrlRepositoryPort.java
│   └── service                    # Use Case Implementations
│       └── UrlShortenerService.java
└── infra                          # ⚙️ Infrastructure (Spring Boot)
    ├── Application.java           # Main Spring Boot Application
    ├── adapter                    # Port Implementations
    │   ├── input                  # Inbound Adapters
    │   │   └── rest               # REST Controllers + DTOs
    │   │       ├── UrlController.java
    │   │       ├── advice/GlobalExceptionHandler.java
    │   │       └── dto/{ShortenRequest, ShortenResponse}.java
    │   └── output                 # Outbound Adapters
    │       ├── analytics          # Async Analytics
    │       │   ├── AsyncAnalyticsAdapter.java
    │       │   └── ClickBatchWorker.java
    │       ├── persistence        # Cassandra Repository
    │       │   ├── CassandraUrlRepository.java
    │       │   └── UrlEntity.java
    │       └── redis              # Redis Adapters
    │           ├── RangeAwareIdGenerator.java
    │           └── RedisUrlCache.java
    ├── config                     # Spring Configurations
    │   ├── CassandraConfig.java
    │   ├── OpenApiConfig.java
    │   ├── RedisConfig.java
    │   ├── ShortCodeConfig.java
    │   └── UndertowConfig.java
    └── observability              # Metrics & Monitoring
        ├── MetricsService.java
        └── MicrometerMetricsAdapter.java
```

---

## 🛠️ Tech Stack

*   **Java 21**: Leveraging the latest features and Virtual Threads.
*   **Spring Boot 3.5.7**: Base framework.
*   **Undertow**: High-performance web server (Non-blocking I/O).
*   **Virtual Threads (Project Loom)**: Lightweight and scalable concurrency.
*   **Apache Cassandra**: NoSQL database for high availability and massive writes.
*   **Redis**: Cache, atomic ID generation, and Bloom Filter.
*   **Redisson**: Advanced Redis client with Bloom Filter support.
*   **Caffeine**: In-memory local cache (L1) for hot URLs.
*   **Hashids**: Sequential ID obfuscation into short codes.
*   **GraalVM**: Native compilation (AOT) support for instant startup and low memory consumption.

---

## 🛡️ High-Scale Features

This project is optimized to support **100 million writes/day** and **1 billion reads/day**:

### Protection Patterns

- **Bloom Filter**: Prevents Cache Penetration attacks (invalid IDs don't reach the database)
- **TTL Jitter**: Avoids Cache Stampede by adding randomness to expiration time
- **Caffeine L1 Cache**: 5-second local cache for the top 100 most accessed links

### ID Generation Strategy

- **Counter-Based Shuffle**: Redis provides sequential IDs in batches of 1,000
- **Hashids Encoding**: IDs are obfuscated into 7+ character codes (e.g., `vE1GpYK`)
- **Zero Collision**: Mathematical uniqueness guaranteed without database lookup

### Async Analytics

- **Fire-and-Forget**: Clicks are tracked without blocking redirection
- **Batch Processing**: Worker processes events in batches every 5 seconds
- **Queue Capacity**: 100k events in memory to absorb traffic spikes

---

## 🚀 How to Run

### Prerequisites

*   Java 21 JDK
*   Maven
*   Docker & Docker Compose

### 🔧 Build and Execution

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/url-shortener-service.git
    cd url-shortener-service
    ```

2.  **Start infrastructure (Cassandra + Redis):**
    ```bash
    docker-compose up -d
    ```
    *Wait a few moments for Cassandra to initialize and create the keyspace.*

3.  **Compile the project:**
    ```bash
    mvn clean install
    ```

4.  **Run the application:**
    ```bash
    mvn spring-boot:run
    ```

### ⚡ Native Build (GraalVM)

To generate an ultra-optimized native binary:

```bash
mvn -Pnative native:compile
./target/url-shortener-service
```

### 🐳 Docker Deployment

**Build Docker Image:**
```bash
docker build -t url-shortener:latest .
```

**Run with Docker Compose (Recommended):**
```bash
docker-compose up -d
```

This will start:
- Cassandra (port 9042)
- Redis (port 6379)
- URL Shortener Service (port 8080)

**Run Standalone Container:**
```bash
docker run -d \
  -p 8080:8080 \
  -e SPRING_CASSANDRA_CONTACT_POINTS=cassandra:9042 \
  -e SPRING_DATA_REDIS_HOST=redis \
  --name url-shortener \
  url-shortener:latest
```

**Health Check:**
```bash
curl http://localhost:8080/actuator/health
```

---

## 🔌 API Endpoints

### Shorten URL

`POST /api/v1/urls`

**Request Body:**
```json
{
  "originalUrl": "https://www.google.com/search?q=spring+boot+undertow"
}
```

**Response:**
```json
{
  "id": "vE1GpYK",
  "shortUrl": "http://localhost:8080/vE1GpYK"
}
```

### Redirect (Access Short URL)

`GET /{id}`

**Example:**
```bash
curl -v http://localhost:8080/vE1GpYK
# HTTP/1.1 302 Found
# Location: https://www.google.com/search?q=spring+boot+undertow
```

**Logs (first time):**
```
Cache Miss for ID: vE1GpYK. Fetching from DB...
Processing batch of 1 click events...
```

**Logs (second time):**
```
Cache Hit for ID: vE1GpYK
```

---

## 📖 API Documentation (Swagger)

Interactive API documentation is available via **Swagger UI**:

**Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

**OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

The interface allows you to:
- Test all endpoints directly from the browser
- View request/response schemas
- Understand HTTP status codes
- See usage examples

---

## 📊 Observability & Metrics

The application exposes **custom business metrics** via Micrometer for monitoring and observability.

### Available Metrics

**Business Metrics:**
- `urls.shortened.total` - Total number of URLs shortened
- `cache.hits.total` - Cache hit count (Redis L2)
- `cache.misses.total` - Cache miss count
- `bloomfilter.rejections.total` - Requests blocked by Bloom Filter (cache penetration protection)

**Access Metrics:**
```bash
# Prometheus format (for Grafana)
curl http://localhost:8080/actuator/prometheus

# Individual metric
curl http://localhost:8080/actuator/metrics/urls.shortened.total

# All available metrics
curl http://localhost:8080/actuator/metrics
```

### Grafana Dashboard

Import the metrics into Grafana for real-time monitoring:
1. Configure Prometheus to scrape `/actuator/prometheus`
2. Create dashboard with panels for:
   - URL shortening rate (requests/sec)
   - Cache hit ratio (hits / (hits + misses))
   - Bloom Filter effectiveness
   - Response time percentiles (p50, p95, p99)

---

## 🧪 Tests

The project has complete coverage of **unit tests** and **integration tests**.

### Unit Tests

Test isolated components using mocks:
- `UrlShortenerServiceTest`: Business logic
- `RangeAwareIdGeneratorTest`: ID generation
- `RedisUrlCacheTest`: Multi-level cache
- `UrlControllerTest`: REST endpoints

```bash
mvn test -Dtest="*Test"
```

### Integration Tests

Use **Testcontainers** to spin up real Redis and Cassandra in Docker:
- `UrlShortenerIntegrationTest`: Complete E2E flow
- `RedisIntegrationTest`: ID persistence and batching
- `CassandraIntegrationTest`: URL persistence

```bash
mvn test -Dtest="*IntegrationTest"
```

**Requirements:**
- Docker running (for Testcontainers)

### Run All Tests

```bash
mvn test
```

---

## ⚙️ Configuration

Main configurations are in `src/main/resources/application.yml`.

*   **Undertow**: Tuned for performance with direct buffers.
*   **Virtual Threads**: Enabled globally (`spring.threads.virtual.enabled: true`).
*   **Cassandra/Redis**: Configured for `localhost` by default.
*   **Rate Limiter**: Configurable via `application.yml`.
    ```yaml
    rate-limiter:
      limit: 60      # Requests per window
      window: PT1M   # Window duration (ISO-8601 format, e.g., 1 Minute)
    ```

---

Made with ❤️ and extreme performance.
