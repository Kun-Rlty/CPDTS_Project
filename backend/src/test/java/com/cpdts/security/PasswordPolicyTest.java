package com.cpdts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Boundary tests for the provisional password policy, with emphasis on the
 * BCrypt 72-UTF-8-BYTE limit: multibyte characters (CJK, emoji) must be
 * measured in bytes, because that is the unit the hash function enforces.
 */
class PasswordPolicyTest {

    private static final String CJK = "\u4f60\u597d"; // 2 chars, 6 bytes each

    @Test
    void asciiBoundariesAreEnforcedInBytesAndCharsIdentically() {
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate("a".repeat(12)));
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate("a".repeat(72)));
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 bytes");
    }

    @Test
    void multibytePasswordAtExactlyThe72ByteLimitIsAccepted() {
        // 24 CJK chars = 72 UTF-8 bytes: at the limit, must pass.
        String password = CJK.repeat(12);
        assertThat(password.length()).isEqualTo(24);
        assertThat(utf8Bytes(password)).isEqualTo(72);
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate(password));

        // 18 emoji (surrogate pairs) = 72 UTF-8 bytes: also at the limit.
        String emoji = "\ud83d\ude00".repeat(18);
        assertThat(emoji.length()).isEqualTo(36);
        assertThat(utf8Bytes(emoji)).isEqualTo(72);
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate(emoji));
    }

    @Test
    void multibytePasswordBeyondTheByteLimitIsRejectedEvenWhenShortInCharacters() {
        // 25 CJK chars = 75 bytes but only 25 chars: a CHAR-based check would
        // wrongly accept it; the byte-based check must reject it.
        String password = CJK.repeat(12) + "\u4f60";
        assertThat(password.length()).isEqualTo(25);
        assertThat(utf8Bytes(password)).isEqualTo(75);
        assertThatThrownBy(() -> PasswordPolicy.validate(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 bytes");

        // 19 emoji = 76 bytes (38 chars).
        String emoji = "\ud83d\ude00".repeat(19);
        assertThat(utf8Bytes(emoji)).isEqualTo(76);
        assertThatThrownBy(() -> PasswordPolicy.validate(emoji))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void utf8LengthMeasuresBytesNotCharacters() {
        assertThat(PasswordPolicy.utf8Length(null)).isZero();
        assertThat(PasswordPolicy.utf8Length("abc")).isEqualTo(3);
        assertThat(PasswordPolicy.utf8Length(CJK)).isEqualTo(6);   // 2 chars -> 6 bytes
        assertThat(PasswordPolicy.utf8Length("\u00e9\u00e9")).isEqualTo(4); // 2 chars -> 4 bytes
        assertThat(PasswordPolicy.utf8Length("\ud83d\ude00")).isEqualTo(4); // 2 chars -> 4 bytes
    }

    @Test
    void nullAndEmptyPasswordsAreRejected() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
