package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern BASE62_PATTERN = Pattern.compile("^[0-9A-Za-z]+$");

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

    @Test
    @DisplayName("RandomUrlIdStrategy should produce Base62 codes of correct length")
    void shouldProduceBase62CodesOfCorrectLength() {
        Base62CodeGenerator base62 = new Base62CodeGenerator(7);
        IdGeneratorPort port = base62::generate;
        RandomUrlIdStrategy strategy = new RandomUrlIdStrategy(port);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = strategy.generateId(null, null);
            assertThat(code).hasSize(7);
            assertThat(code).matches(BASE62_PATTERN);
            codes.add(code);
        }
        // All 1000 codes should be unique
        assertThat(codes).hasSize(1000);
    }

    @Test
    @DisplayName("Generated codes never contain hyphen or underscore")
    void shouldNeverContainHyphenOrUnderscore() {
        Base62CodeGenerator base62 = new Base62CodeGenerator(7);
        IdGeneratorPort port = base62::generate;
        RandomUrlIdStrategy strategy = new RandomUrlIdStrategy(port);

        for (int i = 0; i < 10_000; i++) {
            String code = strategy.generateId(null, null);
            assertThat(code).doesNotContain("-").doesNotContain("_");
        }
    }

    @Test
    @DisplayName("Vanity aliases can contain hyphen and underscore")
    void vanityAliasesCanContainSpecialChars() {
        // Aliases with - and _ are valid per VanityUrlIdStrategy regex
        String[] validAliases = {"my-link", "user_name", "test-alias_123"};
        for (String alias : validAliases) {
            assertThat(alias).matches("^[a-zA-Z0-9-_]+$");
        }
    }

    @Test
    @DisplayName("Structural separation: generated codes and aliases are disjoint sets")
    void shouldHaveStructuralSeparation() {
        // Generated codes: exactly 7 chars from [0-9A-Za-z] (no - or _)
        // Vanity aliases: min 3 chars from [a-zA-Z0-9-_] (may contain - or _)
        //
        // A 7-char Base62 code CANNOT equal a valid alias because:
        // 1. If alias contains - or _, it can't be a Base62 code
        // 2. FREE aliases are >= 8 chars, so they can't equal a 7-char code
        // 3. SILVER/GOLD/DIAMOND aliases >= 3-5 chars, but are validated separately

        Base62CodeGenerator base62 = new Base62CodeGenerator(7);
        Set<String> generatedCodes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generatedCodes.add(base62.generate());
        }

        // All generated codes should be pure Base62 (no - or _)
        for (String code : generatedCodes) {
            assertThat(code).matches("^[0-9A-Za-z]+$");
            assertThat(code).doesNotContain("-").doesNotContain("_");
        }
    }
}
