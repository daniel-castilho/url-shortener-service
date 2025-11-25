package com.example.urlshortener.core.idgeneration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeUrlIdGeneratorTest {

    @Mock
    private UrlIdGenerationStrategy strategy1;
    @Mock
    private UrlIdGenerationStrategy strategy2;

    private CompositeUrlIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new CompositeUrlIdGenerator(Arrays.asList(strategy1, strategy2));
    }

    @Test
    @DisplayName("Should use the first supported strategy")
    void shouldUseFirstSupportedStrategy() {
        // Given
        when(strategy1.supports("alias")).thenReturn(false);
        when(strategy2.supports("alias")).thenReturn(true);
        when(strategy2.generateId("alias", "user")).thenReturn("generated-id");

        // When
        String result = generator.generateId("alias", "user");

        // Then
        assertThat(result).isEqualTo("generated-id");
        verify(strategy1).supports("alias");
        verify(strategy2).supports("alias");
        verify(strategy2).generateId("alias", "user");
        verify(strategy1, never()).generateId(any(), any());
    }

    @Test
    @DisplayName("Should throw exception when no strategy supports")
    void shouldThrowWhenNoStrategy() {
        // Given
        when(strategy1.supports(any())).thenReturn(false);
        when(strategy2.supports(any())).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> generator.generateId("alias", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No valid strategy found");
    }
}
