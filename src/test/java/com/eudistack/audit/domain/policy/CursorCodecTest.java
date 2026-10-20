package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.CursorPosition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static CursorCodec.CursorState state() {
        return new CursorCodec.CursorState("red-logistica", "f1", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-08T00:00:00Z"),
                new CursorPosition(Instant.parse("2026-10-02T10:00:00.123456Z"), UUID.randomUUID(), "red_logistica"));
    }

    @Test
    void encodeDecode_roundTrip() {
        CursorCodec.CursorState state = state();

        assertThat(codec.decode(codec.encode(state))).isEqualTo(state);
    }

    @Test
    void decode_tampered_isBadRequest() {
        String cursor = codec.encode(state());
        String tampered = cursor.substring(0, 5) + (cursor.charAt(5) == 'A' ? 'B' : 'A') + cursor.substring(6);

        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void decode_otherKey_isBadRequest() {
        CursorCodec other = new CursorCodec("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> other.decode(codec.encode(state()))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void decode_garbage_isBadRequest() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor")).isInstanceOf(BadRequestException.class);
    }
}
