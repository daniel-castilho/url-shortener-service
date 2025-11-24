package com.example.urlshortener.infra.adapter.output.persistence.exception;

/**
 * Exceção de domínio lançada quando ocorrem erros na camada de persistência.
 * Encapsula exceções específicas do MongoDB ou banco de dados, evitando que
 * detalhes de infraestrutura vazem para o core da aplicação.
 *
 * Segue o padrão de isolamento de infraestrutura proposto pela Clean Architecture.
 */
public class RepositoryException extends RuntimeException {

    /**
     * Construtor com mensagem descritiva.
     *
     * @param message descrição do erro que ocorreu
     */
    public RepositoryException(String message) {
        super(message);
    }

    /**
     * Construtor com mensagem e causa raiz (cause chaining).
     * Permite rastrear a exception original do MongoDB ou banco de dados.
     *
     * @param message descrição do erro que ocorreu
     * @param cause a exceção original (ex: MongoException)
     */
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}

