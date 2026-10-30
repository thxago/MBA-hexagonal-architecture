package br.com.fullcycle.domain.event.ticket;

import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventTicketId;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Set;

public class TicketTest {

    @Test
    @DisplayName("Deve criar um ticket")
    public void testReserveTicket() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");

        final var anEvent =
                Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);

        final var expectedTickets = 1;
        final var expectedTicketOrder = 1;
        final var expectedTicketStatus = TicketStatus.PENDING;
        final var expectedEventId = anEvent.eventId();
        final var expectedCustomerId = aCustomer.customerId();

        // when
        final var actualTicket = Ticket.newTicket(aCustomer.customerId(), anEvent.eventId());

        // then
        Assertions.assertNotNull(actualTicket.ticketId());
        Assertions.assertNotNull(actualTicket.reservedAt());
        Assertions.assertNull(actualTicket.paidAt());
        Assertions.assertEquals(expectedEventId, actualTicket.eventId());
        Assertions.assertEquals(expectedCustomerId, actualTicket.customerId());
        Assertions.assertEquals(expectedTicketStatus, actualTicket.status());
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("Deve cancelar um ticket restaurado de forma idempotente preservando seus dados")
    public void testCancelRestoredTicket(final TicketStatus status) {
        final var expectedTicketId = TicketId.unique();
        final var expectedCustomerId = CustomerId.unique();
        final var expectedEventId = EventId.unique();
        final var expectedReservedAt = Instant.parse("2026-10-29T10:00:00Z");
        final var expectedPaidAt = status == TicketStatus.PAID
                ? Instant.parse("2026-10-29T10:05:00Z") : null;
        final var aTicket = new Ticket(
                expectedTicketId, expectedCustomerId, expectedEventId,
                status, expectedPaidAt, expectedReservedAt
        );

        aTicket.cancel();
        Assertions.assertEquals(TicketStatus.CANCELLED, aTicket.status());
        aTicket.cancel();

        Assertions.assertEquals(TicketStatus.CANCELLED, aTicket.status());
        Assertions.assertEquals(expectedTicketId, aTicket.ticketId());
        Assertions.assertEquals(expectedCustomerId, aTicket.customerId());
        Assertions.assertEquals(expectedEventId, aTicket.eventId());
        Assertions.assertEquals(expectedPaidAt, aTicket.paidAt());
        Assertions.assertEquals(expectedReservedAt, aTicket.reservedAt());
        Assertions.assertTrue(aTicket.allDomainEvents().isEmpty());
    }

    @Test
    @DisplayName("Deve cancelar um ticket novo sem alterar o evento de criação")
    public void testCancelNewTicketPreservesDomainEvents() {
        final var aTicket = Ticket.newTicket(
                EventTicketId.unique(), CustomerId.unique(), EventId.unique()
        );
        final var expectedDomainEvents = Set.copyOf(aTicket.allDomainEvents());
        Assertions.assertEquals(1, expectedDomainEvents.size());
        Assertions.assertInstanceOf(TicketCreated.class, expectedDomainEvents.iterator().next());
        Assertions.assertEquals(TicketStatus.PENDING, aTicket.status());

        aTicket.cancel();
        aTicket.cancel();

        Assertions.assertEquals(TicketStatus.CANCELLED, aTicket.status());
        Assertions.assertEquals(expectedDomainEvents, aTicket.allDomainEvents());
    }
}