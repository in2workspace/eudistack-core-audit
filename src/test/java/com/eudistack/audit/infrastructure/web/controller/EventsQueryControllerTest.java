package com.eudistack.audit.infrastructure.web.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eudistack.audit.domain.exception.BadRequestException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.EventPage;
import com.eudistack.audit.domain.port.in.ListEventsPort;
import com.eudistack.audit.domain.port.in.ListRequest;
import com.eudistack.audit.infrastructure.web.ApiRequest;
import com.eudistack.audit.infrastructure.web.TraceIds;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventsQueryController")
class EventsQueryControllerTest {

    private static final Caller CALLER = new Caller("auditor", ActorKind.PERSON, null, null, "VATES-B1", "red-logistica",
            null, List.of());

    private final List<ListRequest> received = new ArrayList<>();
    private final ListEventsPort listEvents = (context, request) -> {
        received.add(request);
        return new EventPage(List.of(), null);
    };
    private final EventsQueryController controller = new EventsQueryController(listEvents, null,
            new TraceIds(new StaticListableBeanFactory().getBeanProvider(Tracer.class)));

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiRequest.CALLER, CALLER);
        request.setAttribute(ApiRequest.HOST_TENANT, "red-logistica");
        return request;
    }

    @Test
    @DisplayName("list_orderParameter_reachesTheUseCaseAndIsRecordedAsReceived")
    void list_orderParameter_reachesTheUseCaseAndIsRecordedAsReceived() {
        // Arrange
        MockHttpServletRequest request = request();
        request.setParameter("order", "desc");

        // Act
        ObjectNode body = controller.list(request).getBody();

        // Assert
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.order()).isEqualTo("desc");
            assertThat(r.receivedFilters().get("order").asText()).isEqualTo("desc");
        });
        assertThat(body.get("next_cursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("list_withoutOrder_reachesTheUseCaseWithoutIt")
    void list_withoutOrder_reachesTheUseCaseWithoutIt() {
        // Arrange
        MockHttpServletRequest request = request();

        // Act
        controller.list(request);

        // Assert
        assertThat(received).singleElement().satisfies(r -> assertThat(r.order()).isNull());
    }

    @Test
    @DisplayName("list_orderSentTwice_isBadRequest")
    void list_orderSentTwice_isBadRequest() {
        // Arrange
        MockHttpServletRequest request = request();
        request.setParameter("order", "asc", "desc");

        // Act + Assert
        assertThatThrownBy(() -> controller.list(request)).isInstanceOf(BadRequestException.class);
    }
}
