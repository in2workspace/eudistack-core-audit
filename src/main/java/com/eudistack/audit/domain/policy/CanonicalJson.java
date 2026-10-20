package com.eudistack.audit.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * JSON Canonicalization Scheme (RFC 8785): object members sorted by UTF-16 code units, no
 * whitespace, minimal string escaping and ECMAScript number formatting. Two semantically equal
 * events always give the same bytes, whatever key order or spacing the producer used.
 */
public final class CanonicalJson {

    private static final HexFormat HEX = HexFormat.of();
    private static final BigDecimal UPPER_PLAIN = new BigDecimal("1e21");
    private static final BigDecimal LOWER_PLAIN = new BigDecimal("1e-6");

    private CanonicalJson() {
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    public static byte[] sha256(JsonNode node) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalize(node).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Algorithm-prefixed digest in the catalogue {@code hash} form: {@code sha256:<hex>}.
     */
    public static String hashRef(byte[] digest) {
        return "sha256:" + HEX.formatHex(digest);
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeString(names.get(i), out);
                out.append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            writeString(node.asText(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(formatNumber(node));
        } else {
            writeString(node.asText(), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * ECMAScript {@code Number.prototype.toString} for the value as an IEEE-754 double, which is
     * what RFC 8785 §3.2.2.3 prescribes. Integers that fit exactly are printed without decimals.
     */
    static String formatNumber(JsonNode node) {
        double value = node.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value == 0) {
            return "0";
        }
        // Shortest round-trip digits (Double.toString is shortest since JDK 19).
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        BigDecimal abs = decimal.abs();
        if (abs.compareTo(UPPER_PLAIN) < 0 && abs.compareTo(LOWER_PLAIN) >= 0) {
            return decimal.toPlainString();
        }
        String sign = decimal.signum() < 0 ? "-" : "";
        BigInteger unscaled = abs.unscaledValue();
        String digits = unscaled.toString();
        int exponent = digits.length() - 1 - abs.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }
}
