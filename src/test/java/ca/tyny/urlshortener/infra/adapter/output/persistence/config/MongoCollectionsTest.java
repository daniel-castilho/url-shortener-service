package ca.tyny.urlshortener.infra.adapter.output.persistence.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoCollectionsTest {

    @Test
    @DisplayName("Should have SHORT_URLS constant")
    void shouldHaveShortUrlsConstant() {
        assertThat(MongoCollections.SHORT_URLS).isEqualTo("short_urls");
    }

    @Test
    @DisplayName("Should not be instantiable")
    void shouldNotBeInstantiable() {
        assertThatThrownBy(() -> {
            try {
                var constructor = MongoCollections.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(AssertionError.class);
    }
}
