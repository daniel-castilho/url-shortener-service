package ca.tyny.urlshortener.infra.adapter.output.persistence.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryExceptionTest {

    @Test
    @DisplayName("Should create exception with message")
    void shouldCreateExceptionWithMessage() {
        RepositoryException ex = new RepositoryException("Database error");

        assertThat(ex.getMessage()).isEqualTo("Database error");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("Connection refused");
        RepositoryException ex = new RepositoryException("Database error", cause);

        assertThat(ex.getMessage()).isEqualTo("Database error");
        assertThat(ex.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should be a RuntimeException")
    void shouldBeRuntimeException() {
        RepositoryException ex = new RepositoryException("error");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
