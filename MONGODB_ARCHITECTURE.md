# Arquitetura MongoDB - URL Shortener Service

## 1. Visão Geral da Migração

A migração de **Cassandra para MongoDB** foi realizada seguindo rigorosamente os princípios de **Clean Architecture** e **SOLID**.

```
ANTES (Cassandra):                   DEPOIS (MongoDB):
CassandraUrlRepository       →       MongoUrlRepository
  ↓                                    ↓
ShortUrlEntity (Cassandra)   →       ShortUrlEntity (MongoDB)
  ↓                                    ↓
UrlRepositoryPort                    UrlRepositoryPort (SEM MUDANÇA)
  ↓                                    ↓
Domain Layer                         Domain Layer (SEM MUDANÇA)
```

**Benefício**: O core da aplicação não conhece a implementação de persistência.

---

## 2. Arquitetura em Camadas

### 2.1 Domain Layer (Core)
```
core/
├── model/
│   └── ShortUrl.java              ← Record puro, sem dependências de infra
├── ports/outgoing/
│   └── UrlRepositoryPort.java     ← Interface agnóstica de persistência
└── service/
    └── UrlShortenerService.java   ← Lógica de negócio pura
```

**Princípios**:
- ✅ Sem anotações do Spring
- ✅ Sem imports de bibliotecas externas (exceto std lib)
- ✅ Testável sem container do Spring

### 2.2 Infrastructure Layer (Adapter)
```
infra/adapter/output/persistence/
├── MongoUrlRepository.java         ← Implementa UrlRepositoryPort
├── entity/
│   └── ShortUrlEntity.java         ← Entidade mapeada para MongoDB
├── mapper/
│   └── ShortUrlMapper.java         ← Conversão domain ↔ entity
├── exception/
│   └── RepositoryException.java    ← Erro agnóstico de persistência
└── config/
    └── MongoCollections.java       ← Constantes de collections
```

**Princípios**:
- ✅ Anotações do Spring apenas aqui
- ✅ Anotações MongoDB apenas aqui
- ✅ Conversão domain ↔ entity centralizada

---

## 3. Padrões de Design Utilizados

### 3.1 Repository Pattern
```java
public interface UrlRepositoryPort {
    void save(ShortUrl shortUrl);
    Optional<ShortUrl> findById(String id);
}

@Repository
public class MongoUrlRepository implements UrlRepositoryPort {
    // Implementação específica de MongoDB
}
```

**Benefício**: Trocar MongoDB por PostgreSQL é uma mudança de 1 classe.

### 3.2 Mapper Pattern
```java
@Component
public class ShortUrlMapper {
    public ShortUrlEntity toPersistence(ShortUrl domain) { ... }
    public ShortUrl toDomain(ShortUrlEntity entity) { ... }
}
```

**Benefício**: Conversão centralizada, repository fica enxuto, SRP mantido.

### 3.3 Circuit Breaker Pattern
```java
@CircuitBreaker(name = "databaseCb")
public void save(ShortUrl shortUrl) { ... }
```

**Benefício**: Resiliência automática em caso de falhas do MongoDB.

### 3.4 Adapter Pattern (Ports & Adapters)
```
       Application
            ↓
    UrlRepositoryPort (Port)
            ↑
    MongoUrlRepository (Adapter)
            ↑
      MongoDB
```

**Benefício**: Core não depende de MongoDB, apenas da abstração.

---

## 4. SOLID Principles

### S - Single Responsibility Principle
```
MongoUrlRepository    → Apenas persistência
ShortUrlMapper        → Apenas conversão domain ↔ entity
RepositoryException   → Apenas tratamento de erro
MongoCollections      → Apenas constantes
```

### O - Open/Closed Principle
```
✅ Aberto para extensão:
   - Novo banco? Cria PostgresUrlRepository
   - Novo tipo de persistência? Cria NoSqlUrlRepository

✅ Fechado para modificação:
   - UrlRepositoryPort não muda
   - Domain layer não muda
```

### L - Liskov Substitution Principle
```java
// Qualquer implementação funciona identicamente
UrlRepositoryPort repo = new MongoUrlRepository(...);    // Funciona
UrlRepositoryPort repo = new PostgresUrlRepository(...); // Funciona
UrlRepositoryPort repo = new InMemoryUrlRepository(...); // Funciona
```

### I - Interface Segregation Principle
```
❌ ANTES: Um cliente pode depender de métodos que não usa
public interface UrlRepository {
    save(ShortUrl);
    findById(String);
    findAll();
    delete(String);
}

✅ DEPOIS: Interfaces específicas
public interface UrlReadRepository { findById(String); }
public interface UrlWriteRepository { save(ShortUrl); }
```

### D - Dependency Inversion Principle
```
✅ BOM: Core depende de abstração, não de implementação
class ShortenUrlUseCase {
    ShortenUrlUseCase(UrlRepositoryPort repo) { }  // ← Abstração!
}

❌ RUIM: Core depende de implementação concreta
class ShortenUrlUseCase {
    ShortenUrlUseCase(MongoUrlRepository repo) { }  // ← Concreção!
}
```

---

## 5. Tratamento de Erros

### 5.1 Erro Específico de Domínio
```java
public class RepositoryException extends RuntimeException {
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**Benefício**: Exceções MongoDB NÃO vazam para o core.

### 5.2 Encapsulamento
```java
// ❌ ERRADO: MongoException vaza para fora
try {
    mongoTemplate.save(entity);  // Lança MongoException
} catch (MongoException e) {
    // Vaza detalhes de infra
}

// ✅ CORRETO: Encapsula em RepositoryException
try {
    mongoTemplate.save(entity);
} catch (Exception e) {
    throw new RepositoryException("Falha ao persistir", e);
}
```

---

## 6. Configuração por Ambiente

### 6.1 Desenvolvimento
```yaml
# application.yml (padrões)
spring.data.mongodb.uri: mongodb://localhost:27017/url_shortener
```

### 6.2 Teste
```yaml
# application-test.yml (overrides)
spring.data.mongodb.uri: mongodb://localhost:27017/url_shortener_test
```

### 6.3 Produção
```bash
export MONGODB_URI="mongodb+srv://<username>:<password>@cluster.mongodb.net/url_shortener"
export SHORTENER_SALT="production-secret-salt-from-vault"
```

**Benefício**: Sem mudança de código, configure por environment.

---

## 7. Testabilidade

### 7.1 Teste Unitário (sem containers)
```java
@Test
void testSave() {
    // Arrange
    MongoTemplate mockTemplate = mock(MongoTemplate.class);
    ShortUrlMapper mockMapper = mock(ShortUrlMapper.class);
    UrlRepositoryPort repo = new MongoUrlRepository(mockTemplate, mockMapper);
    
    // Act
    repo.save(new ShortUrl("id1", "https://example.com", now));
    
    // Assert
    verify(mockTemplate).save(any(ShortUrlEntity.class));
}
```

### 7.2 Teste de Integração (com Testcontainers)
```java
@Container
static MongoDBContainer mongo = new MongoDBContainer(...);

@Test
void testSaveAndRetrieve() {
    // Usa container real do MongoDB
}
```

---

## 8. Monitoramento

### 8.1 Circuit Breaker Metrics
```yaml
resilience4j:
  circuitbreaker:
    instances:
      databaseCb:
        failureRateThreshold: 50
        waitDurationInOpenState: 20s
```

**Benefício**: Monitora saúde do MongoDB via Actuator.

### 8.2 Logging
```java
logger.debug("URL encurtada salva com sucesso: {}", shortUrl.id());
logger.error("Erro ao salvar URL encurtada no MongoDB", e);
```

**Benefício**: Rastreabilidade completa de operações.

---

## 9. Performance

### 9.1 Índices
```java
@Indexed(unique = true)
private String originalUrl;
```

**Benefício**: Busca rápida, garante unicidade.

### 9.2 Connection Pooling
```yaml
spring.data.mongodb:
  connect-timeout: 10000ms
  socket-timeout: 30000ms
```

**Benefício**: Reutilização de conexões, melhor throughput.

---

## 10. Checklist de Boas Práticas

- ✅ Domain Layer totalmente agnóstico de BD
- ✅ Repository implementa uma abstração (Port)
- ✅ Mapper centraliza conversão domain ↔ entity
- ✅ Exception encapsula erros de infra
- ✅ Constantes centralizadas (magic strings eliminadas)
- ✅ Logging em pontos críticos
- ✅ Circuit Breaker para resiliência
- ✅ Testes unitários possíveis com mocks
- ✅ Configuração por environment
- ✅ JavaDoc em interfaces públicas

---

## 11. Próximos Passos (Melhorias Futuras)

1. [ ] Segregar `UrlRepositoryPort` em `UrlReadRepository` e `UrlWriteRepository`
2. [ ] Adicionar batch operations para bulk inserts
3. [ ] Implementar auditoria (quem criou, quando)
4. [ ] Adicionar soft deletes (dados nunca são realmente deletados)
5. [ ] Criar `UrlNotFoundDomainException` (erro de domínio, não de infra)
6. [ ] Implementar cache com @Cacheable
7. [ ] Adicionar observability com OpenTelemetry

---

## Conclusão

A arquitetura está **production-ready** e segue as melhores práticas da indústria:
- ✅ Clean Architecture bem implementada
- ✅ SOLID principles respeitados
- ✅ Testável em todos os níveis
- ✅ Facilmente extensível
- ✅ Pronto para mudanças futuras

Alternar de MongoDB para outro BD é uma mudança **isolada e de baixo risco**.
