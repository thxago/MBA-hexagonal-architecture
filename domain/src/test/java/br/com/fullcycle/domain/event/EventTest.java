package br.com.fullcycle.domain.event;

import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.util.Set;

public class EventTest {

    @Test
    @DisplayName("Deve criar um evento")
    public void testCreateEvent() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var expectedDate = "2021-01-01";
        final var expectedName = "Disney on Ice";
        final var expectedTotalSpots = 10;
        final var expectedPartnerId = aPartner.partnerId().value();
        final var expectedTickets = 0;

        // when
        final var actualEvent = Event.newEvent(expectedName, expectedDate, expectedTotalSpots, aPartner);

        // then
        Assertions.assertNotNull(actualEvent.eventId());
        Assertions.assertEquals(expectedDate, actualEvent.date().format(DateTimeFormatter.ISO_LOCAL_DATE));
        Assertions.assertEquals(expectedName, actualEvent.name().value());
        Assertions.assertEquals(expectedTotalSpots, actualEvent.totalSpots());
        Assertions.assertEquals(expectedPartnerId, actualEvent.partnerId().value());
        Assertions.assertEquals(expectedTickets, actualEvent.allTickets().size());
        Assertions.assertFalse(actualEvent.cancelled());
    }

    @Test
    @DisplayName("Não deve criar um evento com nome inválido")
    public void testCreateEventWithInvalidName() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var expectedError = "Invalid value for Name";

        // when
        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> Event.newEvent(null, "2021-01-01", 10, aPartner)
        );

        // then
        Assertions.assertEquals(expectedError, actualError.getMessage());
    }

    @Test
    @DisplayName("Não deve criar um evento com data inválido")
    public void testCreateEventWithInvalidDate() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var expectedError = "Invalid date for Event";

        // when
        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> Event.newEvent("Disney", "20210101", 10, aPartner)
        );

        // then
        Assertions.assertEquals(expectedError, actualError.getMessage());
    }

    @Test
    @DisplayName("Deve reservar um ticket quando é possível")
    public void testReserveTicket() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");

        final var expectedCustomerId = aCustomer.customerId();
        final var expectedDate = "2021-01-01";
        final var expectedName = "Disney on Ice";
        final var expectedTotalSpots = 10;
        final var expectedPartnerId = aPartner.partnerId().value();
        final var expectedTickets = 1;
        final var expectedTicketOrder = 1;
        final var expectedDomainEvent = "event-ticket.reserved";

        final var actualEvent = Event.newEvent(expectedName, expectedDate, expectedTotalSpots, aPartner);

        final var expectedEventId = actualEvent.eventId();

        // when
        final var actualTicket = actualEvent.reserveTicket(aCustomer.customerId());

        // then
        Assertions.assertNotNull(actualTicket.eventTicketId());
        Assertions.assertNull(actualTicket.ticketId());
        Assertions.assertEquals(expectedEventId, actualTicket.eventId());
        Assertions.assertEquals(expectedCustomerId, actualTicket.customerId());

        Assertions.assertEquals(expectedDate, actualEvent.date().format(DateTimeFormatter.ISO_LOCAL_DATE));
        Assertions.assertEquals(expectedName, actualEvent.name().value());
        Assertions.assertEquals(expectedTotalSpots, actualEvent.totalSpots());
        Assertions.assertEquals(expectedPartnerId, actualEvent.partnerId().value());
        Assertions.assertEquals(expectedTickets, actualEvent.allTickets().size());

        final var actualEventTicket = actualEvent.allTickets().iterator().next();
        Assertions.assertEquals(expectedTicketOrder, actualEventTicket.ordering());
        Assertions.assertEquals(expectedEventId, actualEventTicket.eventId());
        Assertions.assertEquals(expectedCustomerId, actualEventTicket.customerId());
        Assertions.assertEquals(actualTicket.ticketId(), actualEventTicket.ticketId());

        final var actualDomainEvents = actualEvent.allDomainEvents().iterator().next();
        Assertions.assertEquals(expectedDomainEvent, actualDomainEvents.type());
    }

    @Test
    @DisplayName("Não deve reservar um ticket quando o evento está esgotado")
    public void testReserveTicketWhenEventIsSoldOut() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");

        final var aCustomer2 =
                Customer.newCustomer("John1 Doe", "111.456.789-01", "john1.doe@gmail.com");


        final var expectedTotalSpots = 1;
        final var expectedError = "Event sold out";

        final var actualEvent = Event.newEvent("Disney on Ice", "2021-01-01", expectedTotalSpots, aPartner);

        actualEvent.reserveTicket(aCustomer.customerId());

        // when
        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> actualEvent.reserveTicket(aCustomer2.customerId())
        );

        // then
        Assertions.assertEquals(expectedError, actualError.getMessage());
    }

    @Test
    @DisplayName("Não deve reservar dois tickets para um mesmo cliente")
    public void testReserveTwoTicketsForTheSameClient() throws Exception {
        // given
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");

        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");

        final var expectedTotalSpots = 1;
        final var expectedError = "Email already registered";

        final var actualEvent = Event.newEvent("Disney on Ice", "2021-01-01", expectedTotalSpots, aPartner);

        actualEvent.reserveTicket(aCustomer.customerId());

        // when
        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> actualEvent.reserveTicket(aCustomer.customerId())
        );

        // then
        Assertions.assertEquals(expectedError, actualError.getMessage());
    }

    @Test
    @DisplayName("Deve cancelar um evento e registrar o evento de domínio")
    public void testCancelEvent() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        final var aReservation = anEvent.reserveTicket(aCustomer.customerId());
        final var previousDomainEvents = Set.copyOf(anEvent.allDomainEvents());

        anEvent.cancel();

        Assertions.assertTrue(anEvent.cancelled());
        Assertions.assertEquals(Set.of(aReservation), anEvent.allTickets());
        Assertions.assertEquals(previousDomainEvents.size() + 1, anEvent.allDomainEvents().size());
        Assertions.assertTrue(anEvent.allDomainEvents().containsAll(previousDomainEvents));
        final var cancellation = anEvent.allDomainEvents().stream()
                .filter(EventCancelled.class::isInstance)
                .map(EventCancelled.class::cast)
                .findFirst()
                .orElseThrow();
        Assertions.assertNotNull(cancellation.domainEventId());
        Assertions.assertEquals("event.cancelled", cancellation.type());
        Assertions.assertEquals(anEvent.eventId().value(), cancellation.eventId());
        Assertions.assertNotNull(cancellation.occurredOn());
    }

    @Test
    @DisplayName("Não deve cancelar um evento duas vezes")
    public void testCancelEventTwice() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        anEvent.cancel();
        final var expectedDomainEvents = Set.copyOf(anEvent.allDomainEvents());

        final var actualError = Assertions.assertThrows(ValidationException.class, anEvent::cancel);

        Assertions.assertEquals("Event already cancelled", actualError.getMessage());
        Assertions.assertTrue(anEvent.cancelled());
        Assertions.assertEquals(expectedDomainEvents, anEvent.allDomainEvents());
    }

    @Test
    @DisplayName("Não deve reservar um ticket em evento cancelado")
    public void testReserveTicketWhenEventIsCancelled() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        anEvent.cancel();
        final var expectedDomainEvents = Set.copyOf(anEvent.allDomainEvents());

        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> anEvent.reserveTicket(aCustomer.customerId())
        );

        Assertions.assertEquals("Event is cancelled", actualError.getMessage());
        Assertions.assertTrue(anEvent.allTickets().isEmpty());
        Assertions.assertEquals(expectedDomainEvents, anEvent.allDomainEvents());
    }

    @Test
    @DisplayName("Deve restaurar um evento cancelado sem emitir eventos de domínio")
    public void testRestoreCancelledEvent() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        final var aReservation = anEvent.reserveTicket(aCustomer.customerId());

        final var restoredEvent = Event.restore(
                anEvent.eventId().value(), anEvent.name().value(), anEvent.date().toString(),
                anEvent.totalSpots(), aPartner.partnerId().value(), Set.of(aReservation), true
        );

        Assertions.assertTrue(restoredEvent.cancelled());
        Assertions.assertEquals(Set.of(aReservation), restoredEvent.allTickets());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
        final var cancellationError = Assertions.assertThrows(ValidationException.class, restoredEvent::cancel);
        Assertions.assertEquals("Event already cancelled", cancellationError.getMessage());
        final var reservationError = Assertions.assertThrows(
                ValidationException.class,
                () -> restoredEvent.reserveTicket(aCustomer.customerId())
        );
        Assertions.assertEquals("Event is cancelled", reservationError.getMessage());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
    }

    @Test
    @DisplayName("Deve restaurar um evento ativo sem emitir cancelamento")
    public void testRestoreActiveEvent() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);

        final var restoredEvent = Event.restore(
                anEvent.eventId().value(), anEvent.name().value(), anEvent.date().toString(),
                anEvent.totalSpots(), aPartner.partnerId().value(), null, false
        );

        Assertions.assertFalse(restoredEvent.cancelled());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
        restoredEvent.cancel();
        Assertions.assertTrue(restoredEvent.cancelled());
        Assertions.assertEquals(1, restoredEvent.allDomainEvents().size());
        Assertions.assertInstanceOf(EventCancelled.class, restoredEvent.allDomainEvents().iterator().next());
    }

    @Test
    @DisplayName("Deve manter a restauração anterior compatível com eventos ativos")
    public void testRestoreEventWithoutCancellationState() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var aCustomer =
                Customer.newCustomer("John Doe", "123.456.789-01", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);

        final var restoredEvent = Event.restore(
                anEvent.eventId().value(), anEvent.name().value(), anEvent.date().toString(),
                anEvent.totalSpots(), aPartner.partnerId().value(), null
        );

        Assertions.assertFalse(restoredEvent.cancelled());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
        final var reservation = restoredEvent.reserveTicket(aCustomer.customerId());
        Assertions.assertEquals(Set.of(reservation), restoredEvent.allTickets());
        Assertions.assertInstanceOf(EventTicketReserved.class, restoredEvent.allDomainEvents().iterator().next());
    }
}
