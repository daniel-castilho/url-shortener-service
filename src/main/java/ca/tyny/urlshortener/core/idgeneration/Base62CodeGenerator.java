package ca.tyny.urlshortener.core.idgeneration;

import java.security.SecureRandom;

/**
 * Pure algorithm for generating random Base62 short codes.
 * Uses {@link java.security.SecureRandom} (CSPRNG) for cryptographic randomness.
 * No framework dependencies — pure Java.
 */
public class Base62CodeGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom secureRandom;
    private final int codeLength;

    public Base62CodeGenerator(int codeLength) {
        if (codeLength < 6) {
            throw new IllegalArgumentException("Code length must be at least 6, got: " + codeLength);
        }
        this.codeLength = codeLength;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a random Base62 code of the configured length.
     *
     * @return a string of {@code codeLength} characters from the Base62 alphabet
     */
    public String generate() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public int getCodeLength() {
        return codeLength;
    }

    public String getAlphabet() {
        return ALPHABET;
    }
}
