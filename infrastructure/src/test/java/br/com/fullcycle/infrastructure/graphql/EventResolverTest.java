package br.com.fullcycle.infrastructure.graphql;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.customer.CustomerRepository;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.graphql.test.tester.GraphQlTester;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:event_graphql_test;MODE=MYSQL;DATABASE_TO_LOWER=TRUE"
})
class EventResolverTest extends IntegrationTest {

    @Autowired
    private ExecutionGraphQlService graphQlService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private GraphQlTester graphQlTester;
    private Partner partner;
    private Customer customer;

    @BeforeEach
    void setUp() {
        graphQlTester = ExecutionGraphQlServiceTester.create(graphQlService);
        eventRepository.deleteAll();
        customerRepository.deleteAll();
        partnerRepository.deleteAll();
        partner = partnerRepository.create(
                Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com")
        );
        customer = customerRepository.create(
                Customer.newCustomer("John Doe", "123.456.789-00", "john@gmail.com")
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deve consultar evento por GraphQL com seu estado atual")
    void testGetEvent(final boolean cancelled) {
        final var event = createEvent();
        if (cancelled) {
            event.cancel();
            eventRepository.update(event);
        }

        graphQlTester.document("""
                        query ($id: ID!) {
                            eventOfId(id: $id) { id name date totalSpots status }
                        }
                        """)
                .variable("id", event.eventId().value())
                .execute()
                .path("eventOfId.id").entity(String.class).isEqualTo(event.eventId().value())
                .path("eventOfId.name").entity(String.class).isEqualTo("Disney on Ice")
                .path("eventOfId.date").entity(String.class).isEqualTo("2021-01-01")
                .path("eventOfId.totalSpots").entity(Integer.class).isEqualTo(100)
                .path("eventOfId.status").entity(String.class).isEqualTo(cancelled ? "CANCELLED" : "ACTIVE");
    }

    @Test
    @DisplayName("Deve retornar null ao consultar evento inexistente por GraphQL")
    void testGetEventNotFound() {
        graphQlTester.document("query ($id: ID!) { eventOfId(id: $id) { id status } }")
                .variable("id", EventId.unique().value())
                .execute()
                .errors().verify()
                .path("eventOfId").valueIsNull();
    }

    @Test
    @DisplayName("Deve cancelar evento por GraphQL")
    void testCancelEvent() {
        final var event = createEvent();

        graphQlTester.document("mutation ($id: ID!) { cancelEvent(id: $id) { id status } }")
                .variable("id", event.eventId().value())
                .execute()
                .path("cancelEvent.id").entity(String.class).isEqualTo(event.eventId().value())
                .path("cancelEvent.status").entity(String.class).isEqualTo("CANCELLED");

        Assertions.assertTrue(eventRepository.eventOfId(event.eventId()).orElseThrow().cancelled());
        graphQlTester.document("query ($id: ID!) { eventOfId(id: $id) { status } }")
                .variable("id", event.eventId().value())
                .execute()
                .path("eventOfId.status").entity(String.class).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Deve preservar o tratamento GraphQL ao cancelar evento inexistente")
    void testCancelEventNotFound() {
        final var response = graphQlTester.document(
                        "mutation ($id: ID!) { cancelEvent(id: $id) { id status } }"
                )
                .variable("id", EventId.unique().value())
                .execute();

        assertBusinessError(response);
    }

    @Test
    @DisplayName("Deve rejeitar cancelamento repetido por GraphQL")
    void testCancelEventTwice() {
        final var event = createEvent();
        final var mutation = "mutation ($id: ID!) { cancelEvent(id: $id) { id status } }";
        graphQlTester.document(mutation).variable("id", event.eventId().value())
                .execute().errors().verify();

        final var response = graphQlTester.document(mutation)
                .variable("id", event.eventId().value()).execute();

        assertBusinessError(response);
        Assertions.assertTrue(eventRepository.eventOfId(event.eventId()).orElseThrow().cancelled());
    }

    @Test
    @DisplayName("Deve rejeitar inscrição em evento cancelado por GraphQL")
    void testSubscribeToCancelledEvent() {
        final var event = createEvent();
        event.cancel();
        eventRepository.update(event);

        final var response = subscribeCustomer(event);

        assertBusinessError(response);
        Assertions.assertTrue(eventRepository.eventOfId(event.eventId()).orElseThrow().allTickets().isEmpty());
    }

    @Test
    @DisplayName("Deve preservar createEvent com status opcional no schema")
    void testCreateEvent() {
        final var response = graphQlTester.document("""
                        mutation ($partnerId: ID!) {
                            createEvent(input: {
                                name: "Disney on Ice", date: "2021-01-01",
                                totalSpots: 100, partnerId: $partnerId
                            }) { id name date totalSpots status }
                        }
                        """)
                .variable("partnerId", partner.partnerId().value())
                .execute();

        response.path("createEvent.name").entity(String.class).isEqualTo("Disney on Ice")
                .path("createEvent.date").entity(String.class).isEqualTo("2021-01-01")
                .path("createEvent.totalSpots").entity(Integer.class).isEqualTo(100)
                .path("createEvent.status").valueIsNull();
        final var id = response.path("createEvent.id").entity(String.class).get();
        Assertions.assertFalse(eventRepository.eventOfId(EventId.with(id)).orElseThrow().cancelled());
    }

    @Test
    @DisplayName("Deve preservar a inscrição GraphQL em evento ativo")
    void testSubscribeToActiveEvent() {
        final var event = createEvent();

        subscribeCustomer(event)
                .path("subscribeCustomerToEvent.eventId").entity(String.class).isEqualTo(event.eventId().value())
                .path("subscribeCustomerToEvent.reservationDate").hasValue();

        Assertions.assertEquals(1, eventRepository.eventOfId(event.eventId()).orElseThrow().allTickets().size());
    }

    private GraphQlTester.Response subscribeCustomer(final Event event) {
        return graphQlTester.document("""
                        mutation ($customerId: ID!, $eventId: ID!) {
                            subscribeCustomerToEvent(input: {
                                customerId: $customerId, eventId: $eventId
                            }) { eventId reservationDate }
                        }
                        """)
                .variable("customerId", customer.customerId().value())
                .variable("eventId", event.eventId().value())
                .execute();
    }

    private void assertBusinessError(final GraphQlTester.Response response) {
        response.errors().satisfy(errors -> {
            Assertions.assertEquals(1, errors.size());
            Assertions.assertEquals(ErrorType.INTERNAL_ERROR, errors.get(0).getErrorType());
        });
    }

    private Event createEvent() {
        return eventRepository.create(Event.newEvent("Disney on Ice", "2021-01-01", 100, partner));
    }
}