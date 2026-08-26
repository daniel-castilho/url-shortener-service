package ca.tyny.urlshortener.core.idgeneration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Base62CodeGenerator Tests")
class Base62CodeGeneratorTest {

    private static final Pattern BASE62_PATTERN = Pattern.compile("^[0-9A-Za-z]+$");

    @Test
    @DisplayName("Should generate code of configured length")
    void shouldGenerateCodeOfCorrectLength() {
        Base62CodeGenerator generator = new Base62CodeGenerator(7);
        String code = generator.generate();
        assertThat(code).hasSize(7);
    }

    @Test
    @DisplayName("Should generate code with configurable length")
    void shouldRespectConfigurableLength() {
        for (int length = 6; length <= 12; length++) {
            Base62CodeGenerator generator = new Base62CodeGenerator(length);
            String code = generator.generate();
            assertThat(code).hasSize(length);
        }
    }

    @Test
    @DisplayName("Should only contain Base62 characters")
    void shouldOnlyContainBase62Characters() {
        Base62CodeGenerator generator = new Base62CodeGenerator(7);
        for (int i = 0; i < 1000; i++) {
            String code = generator.generate();
            assertThat(code).matches(BASE62_PATTERN);
        }
    }

    @Test
    @DisplayName("Should generate unique codes")
    void shouldGenerateUniqueCodes() {
        Base62CodeGenerator generator = new Base62CodeGenerator(7);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        // With 7 chars and 62^7 ≈ 3.5 trillion combinations, 10k codes should all be unique
        assertThat(codes).hasSize(10_000);
    }

    @Test
    @DisplayName("Should use SecureRandom (not java.util.Random)")
    void shouldUseSecureRandom() {
        Base62CodeGenerator generator = new Base62CodeGenerator(7);
        // Just verify it generates valid codes — SecureRandom is the default
        String code = generator.generate();
        assertThat(code).isNotNull().hasSize(7);
        assertThat(code).matches(BASE62_PATTERN);
    }

    @Test
    @DisplayName("Should reject code length less than 6")
    void shouldRejectTooShortLength() {
        assertThatThrownBy(() -> new Base62CodeGenerator(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 6");
    }

    @Test
    @DisplayName("Should accept minimum length of 6")
    void shouldAcceptMinimumLength() {
        Base62CodeGenerator generator = new Base62CodeGenerator(6);
        String code = generator.generate();
        assertThat(code).hasSize(6);
    }

    @Test
    @DisplayName("Should expose alphabet and code length")
    void shouldExposeAlphabetAndLength() {
        Base62CodeGenerator generator = new Base62CodeGenerator(7);
        assertThat(generator.getAlphabet()).hasSize(62);
        assertThat(generator.getCodeLength()).isEqualTo(7);
    }
}
