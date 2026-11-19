package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.CursorPosition;
import com.eudistack.audit.domain.model.SortOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final CursorCodec codec = new CursorCodec(SECRET);

    private static CursorCodec.CursorState state() {
        return state(SortOrder.ASC);
    }

    private static CursorCodec.CursorState state(SortOrder order) {
        return new CursorCodec.CursorState("red-logistica", "f1", order, Instant.parse("2026-10-01T00:00:00Z"),
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

    @Nested
    @DisplayName("order of the listing")
    class OrderOfTheListing {

        private ObjectNode payload() {
            ObjectNode node = new ObjectMapper().createObjectNode();
            node.put("v", 1);
            node.put("s", "red-logistica");
            node.put("f", "f1");
            node.put("from", "2026-10-01T00:00:00Z");
            node.put("to", "2026-10-08T00:00:00Z");
            node.put("at", "2026-10-02T10:00:00Z");
            node.put("id", UUID.randomUUID().toString());
            return node;
        }

        private String signed(ObjectNode node) throws Exception {
            byte[] body = CanonicalJson.canonicalize(node).getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(body) + "." + encoder.encodeToString(mac.doFinal(body));
        }

        @Test
        @DisplayName("decode_descendingCursor_keepsTheOrder")
        void decode_descendingCursor_keepsTheOrder() {
            // Arrange
            CursorCodec.CursorState state = state(SortOrder.DESC);

            // Act
            CursorCodec.CursorState decoded = codec.decode(codec.encode(state));

            // Assert
            assertThat(decoded.order()).isEqualTo(SortOrder.DESC);
            assertThat(decoded).isEqualTo(state);
        }

        @Test
        @DisplayName("decode_cursorIssuedBeforeOrderExisted_isAscending")
        void decode_cursorIssuedBeforeOrderExisted_isAscending() throws Exception {
            // Arrange
            String legacy = signed(payload());

            // Act
            CursorCodec.CursorState decoded = codec.decode(legacy);

            // Assert
            assertThat(decoded.order()).isEqualTo(SortOrder.ASC);
        }

        @Test
        @DisplayName("decode_signedCursorWithAnUnknownOrder_isBadRequest")
        void decode_signedCursorWithAnUnknownOrder_isBadRequest() throws Exception {
            // Arrange
            ObjectNode node = payload();
            node.put("o", "sideways");
            String cursor = signed(node);

            // Act + Assert
            assertThatThrownBy(() -> codec.decode(cursor)).isInstanceOf(BadRequestException.class);
        }
    }
}
