package com.eudistack.audit.domain.policy;

import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.exception.PeriodArchivedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryWindowPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void resolve_withoutBounds_lastSevenDays() {
        QueryWindowPolicy.Window window = QueryWindowPolicy.resolve(null, null, NOW);

        assertThat(window.to()).isEqualTo(NOW);
        assertThat(window.from()).isEqualTo(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void resolve_withoutFrom_sevenDaysBeforeTo() {
        Instant to = NOW.minus(Duration.ofDays(2));

        assertThat(QueryWindowPolicy.resolve(null, to, NOW).from()).isEqualTo(to.minus(Duration.ofDays(7)));
    }

    @Test
    void resolve_moreThan31Days_isBadRequest() {
        assertThatThrownBy(() -> QueryWindowPolicy.resolve(NOW.minus(Duration.ofDays(32)), NOW, NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void resolve_exactly31Days_isAccepted() {
        assertThat(QueryWindowPolicy.resolve(NOW.minus(Duration.ofDays(31)), NOW, NOW).from())
                .isEqualTo(NOW.minus(Duration.ofDays(31)));
    }

    @Test
    void resolve_fromNotBeforeTo_isBadRequest() {
        assertThatThrownBy(() -> QueryWindowPolicy.resolve(NOW, NOW, NOW)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void resolve_hundredDaysAgo_isPeriodArchived() {
        Instant from = NOW.minus(Duration.ofDays(100));

        assertThatThrownBy(() -> QueryWindowPolicy.resolve(from, from.plus(Duration.ofDays(1)), NOW))
                .isInstanceOfSatisfying(PeriodArchivedException.class,
                        e -> assertThat(e.oldestOnline()).isEqualTo(NOW.minus(Duration.ofDays(90))));
    }

    @Test
    void isOnline_ninetyDayBoundary() {
        assertThat(QueryWindowPolicy.isOnline(NOW.minus(Duration.ofDays(89)), NOW)).isTrue();
        assertThat(QueryWindowPolicy.isOnline(NOW.minus(Duration.ofDays(91)), NOW)).isFalse();
    }
}
