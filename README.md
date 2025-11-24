# 🚀 High-Performance URL Shortener

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-green) ![Undertow](https://img.shields.io/badge/Undertow-High_Perf-blue) ![GraalVM](https://img.shields.io/badge/GraalVM-Native-orange)

Um encurtador de URLs ultra-rápido construído com **Spring Boot 3.5.7**, **Undertow** (substituindo o Tomcat) e preparado para **GraalVM Native Image**. Este projeto segue os princípios da **Clean Architecture** para garantir manutenibilidade e desacoplamento.

---

## 🏗️ Arquitetura

O projeto está estruturado para isolar o domínio da infraestrutura:

*   **🟢 Core (Domain)**: Regras de negócio puras, sem dependências de framework.
*   **🔵 Infra (Adapter)**: Implementações do Spring, Banco de Dados (Cassandra), Cache (Redis) e Controladores Web.

### 📂 Estrutura de Diretórios

```
src/main/java/com/example/urlshortener
├── core          # 🧠 Domínio (Puro Java)
│   ├── model     # Entidades de Domínio
│   ├── ports     # Interfaces (Entrada/Saída)
│   └── service   # Casos de Uso
└── infra         # ⚙️ Infraestrutura (Spring Boot)
    ├── adapter   # Implementações dos Ports (Web, DB, Redis)
    └── config    # Configurações (Undertow, Cassandra, etc.)
```

---

## 🛠️ Tech Stack

*   **Java 21**: Aproveitando as últimas features e Virtual Threads.
*   **Spring Boot 3.5.7**: Framework base.
*   **Undertow**: Servidor Web de alta performance (Non-blocking I/O).
*   **Virtual Threads (Project Loom)**: Concorrência leve e escalável.
*   **Apache Cassandra**: Banco de dados NoSQL para alta disponibilidade e escrita massiva.
*   **Redis**: Cache, geração de IDs atômicos e Bloom Filter.
*   **Redisson**: Cliente Redis avançado com suporte a Bloom Filters.
*   **Caffeine**: Cache local em memória (L1) para URLs quentes.
*   **Hashids**: Ofuscação de IDs sequenciais em códigos curtos.
*   **GraalVM**: Suporte para compilação nativa (AOT) para startup instantâneo e baixo consumo de memória.

---

## 🛡️ High-Scale Features

Este projeto foi otimizado para suportar **100 milhões de escritas/dia** e **1 bilhão de leituras/dia**:

### Protection Patterns

- **Bloom Filter**: Previne ataques de Cache Penetration (IDs inválidos não chegam ao banco)
- **TTL Jitter**: Evita Cache Stampede adicionando aleatoriedade ao tempo de expiração
- **Caffeine L1 Cache**: Cache local de 5 segundos para os 100 links mais acessados

### ID Generation Strategy

- **Counter-Based Shuffle**: Redis fornece IDs sequenciais em blocos de 1.000
- **Hashids Encoding**: IDs são ofuscados em códigos de 7+ caracteres (ex: `vE1GpYK`)
- **Zero Collision**: Unicidade matemática garantida sem lookup de banco

### Async Analytics

- **Fire-and-Forget**: Cliques são rastreados sem bloquear o redirecionamento
- **Batch Processing**: Worker processa eventos em lotes a cada 5 segundos
- **Queue Capacity**: 100k eventos em memória para absorver picos de tráfego

---

## 🚀 Como Rodar

### Pré-requisitos

*   Java 21 JDK
*   Maven
*   Docker & Docker Compose

### 🔧 Build e Execução

1.  **Clone o repositório:**
    ```bash
    git clone https://github.com/seu-usuario/url-shortener-service.git
    cd url-shortener-service
    ```

2.  **Suba a infraestrutura (Cassandra + Redis):**
    ```bash
    docker-compose up -d
    ```
    *Aguarde alguns instantes para o Cassandra inicializar e criar o keyspace.*

3.  **Compile o projeto:**
    ```bash
    mvn clean install
    ```

4.  **Rode a aplicação:**
    ```bash
    mvn spring-boot:run
    ```

### ⚡ Build Nativo (GraalVM)

Para gerar um binário nativo ultra-otimizado:

```bash
mvn -Pnative native:compile
./target/url-shortener-service
```

---

## 🔌 API Endpoints

### Encurtar URL

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

### Redirecionar (Acessar URL Curta)

`GET /{id}`

**Exemplo:**
```bash
curl -v http://localhost:8080/vE1GpYK
# HTTP/1.1 302 Found
# Location: https://www.google.com/search?q=spring+boot+undertow
```

**Logs (primeira vez):**
```
Cache Miss for ID: vE1GpYK. Fetching from DB...
Processing batch of 1 click events...
```

**Logs (segunda vez):**
```
Cache Hit for ID: vE1GpYK
```

---

## 📖 API Documentation (Swagger)

A documentação interativa da API está disponível via **Swagger UI**:

**Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

**OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

A interface permite:
- Testar todos os endpoints diretamente do navegador
- Visualizar schemas de request/response
- Entender os códigos de status HTTP
- Ver exemplos de uso

---

## 🧪 Testes

O projeto possui cobertura completa de **testes unitários** e **testes de integração**.

### Testes Unitários

Testam componentes isolados usando mocks:
- `UrlShortenerServiceTest`: Lógica de negócio
- `RangeAwareIdGeneratorTest`: Geração de IDs
- `RedisUrlCacheTest`: Cache multi-nível
- `UrlControllerTest`: Endpoints REST

```bash
mvn test -Dtest="*Test"
```

### Testes de Integração

Usam **Testcontainers** para subir Redis e Cassandra reais em Docker:
- `UrlShortenerIntegrationTest`: Fluxo E2E completo
- `RedisIntegrationTest`: Persistência e batching de IDs
- `CassandraIntegrationTest`: Persistência de URLs

```bash
mvn test -Dtest="*IntegrationTest"
```

**Requisitos:**
- Docker rodando (para Testcontainers)

### Rodar Todos os Testes

```bash
mvn test
```

---

## ⚙️ Configuração

As principais configurações estão em `src/main/resources/application.yml`.

*   **Undertow**: Tunado para performance com buffer direto.
*   **Virtual Threads**: Habilitadas globalmente (`spring.threads.virtual.enabled: true`).
*   **Cassandra/Redis**: Configurados para `localhost` por padrão.

---

Feito com ❤️ e performance extrema.
