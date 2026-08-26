package ca.tyny.urlshortener.core.ports.outgoing;

import ca.tyny.urlshortener.core.model.ShortUrl;

import java.util.Optional;

/**
 * Porto de saída que define o contrato para persistência de URLs encurtadas.
 *
 * Esta é uma abstração que permite o core da aplicação (domain layer)
 * permanecer independente de detalhes de infraestrutura (qual banco de dados é
 * usado).
 *
 * Segue o padrão Ports & Adapters (Clean Architecture):
 * - Port: Esta interface agnóstica de banco de dados
 * - Adapter: Implementação concreta (ex: MongoUrlRepository)
 *
 * Responsabilidades:
 * - Definir operações de persistência de URLs
 * - Ser agnóstico quanto ao banco de dados específico
 * - Ser testável (implementações mock podem ser facilmente criadas)
 *
 * @author URL Shortener Team
 */
public interface UrlRepositoryPort {

    /**
     * Persiste uma URL encurtada.
     *
     * @param shortUrl a URL encurtada a ser salva
     * @throws IllegalArgumentException se os dados forem inválidos
     * @throws RuntimeException         (ou subclasses específicas) em caso de erro
     *                                  de persistência
     */
    void save(ShortUrl shortUrl);

    /**
     * Recupera uma URL encurtada por seu identificador único.
     *
     * @param id o identificador único da URL encurtada
     * @return Optional contendo a URL se encontrada, ou empty se não existir
     * @throws RuntimeException (ou subclasses específicas) em caso de erro ao
     *                          consultar
     */
    Optional<ShortUrl> findById(String id);

    /**
     * Verifica se uma URL encurtada existe por seu identificador.
     *
     * @param id o identificador único
     * @return true se existir, false caso contrário
     */
    boolean existsById(String id);

    /**
     * Incrementa atomicamente em 1 o contador de cliques de uma URL encurtada.
     *
     * @param id o identificador único da URL encurtada
     */
    default void incrementClickCount(String id) {
        incrementClickCount(id, 1L);
    }

    /**
     * Incrementa atomicamente o contador de cliques de uma URL encurtada.
     *
     * A implementação deve usar um incremento atômico do lado do armazenamento
     * (ex.: {@code $inc} no MongoDB), nunca leitura-seguida-de-escrita, para não
     * perder incrementos sob concorrência. Se o código não existir, é no-op.
     *
     * @param id    o identificador único da URL encurtada
     * @param delta quanto somar ao contador (&gt; 0)
     */
    void incrementClickCount(String id, long delta);
}
