package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void canonicalize_sortsKeysAndRemovesWhitespace() throws Exception {
        String canonical = CanonicalJson.canonicalize(json("{ \"b\": [1, {\"z\":true, \"a\":null}], \"a\": \"x\" }"));

        assertThat(canonical).isEqualTo("{\"a\":\"x\",\"b\":[1,{\"a\":null,\"z\":true}]}");
    }

    @Test
    void canonicalize_rfc8785SortingByUtf16CodeUnits() throws Exception {
        // RFC 8785 §3.2.3 example keys.
        String canonical = CanonicalJson.canonicalize(json(
                "{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"\\ufb33\":\"Hebrew Letter Dalet With Dagesh\","
                        + "\"1\":\"One\",\"\\ud83d\\ude00\":\"Emoji: Grinning Face\",\"\\u0080\":\"Control\","
                        + "\"\\u00f6\":\"Latin Small Letter O With Diaeresis\"}"));

        assertThat(canonical).startsWith("{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\"");
        assertThat(canonical.indexOf("Emoji")).isLessThan(canonical.indexOf("Hebrew"));
    }

    @Test
    void canonicalize_escapesOnlyWhatItMust() throws Exception {
        assertThat(CanonicalJson.canonicalize(json("\"a\\u0007b/é\\\"\"")))
                .isEqualTo("\"a\\u0007b/é\\\"\"");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "-0.0, 0",
            "1, 1",
            "1.0, 1",
            "100, 100",
            "1.5, 1.5",
            "0.000001, 0.000001",
            "1e-7, 1e-7",
            "1e21, 1e+21",
            "123456789012345678901, 123456789012345680000",
            "4.50, 4.5"})
    void formatNumber_followsEcmaScript(String input, String expected) throws Exception {
        assertThat(CanonicalJson.formatNumber(json(input))).isEqualTo(expected);
    }

    @Test
    void sha256_sameContentDifferentLayout_sameHash() throws Exception {
        byte[] first = CanonicalJson.sha256(json("{\"a\":1,\"b\":{\"c\":\"d\"}}"));
        byte[] second = CanonicalJson.sha256(json("{ \"b\" : { \"c\" : \"d\" } , \"a\" : 1.0 }"));

        assertThat(first).isEqualTo(second);
    }
}
