package br.com.fullcycle.application.usecases;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.event.CancelEventUseCase;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventCancelled;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.event.EventTicketReserved;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import br.com.fullcycle.infrastructure.jpa.repositories.OutboxJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.StreamSupport;

@Transactional
class CancelEventUseCaseIT extends IntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private OutboxJpaRepository outboxJpaRepository;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Deve persistir e restaurar um evento ativo")
    void testRestoreActiveEvent() {
        final var aPartner = partnerRepository.create(
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com")
        );
        final var anEvent = eventRepository.create(
                Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner)
        );
        entityManager.flush();
        entityManager.clear();

        final var restoredEvent = eventRepository.eventOfId(anEvent.eventId()).orElseThrow();

        Assertions.assertFalse(restoredEvent.cancelled());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
    }

    @Test
    @DisplayName("Deve persistir o cancelamento e seu evento na outbox")
    void testCancelEventAndWriteOutbox() throws Exception {
        final var aPartner = partnerRepository.create(
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com")
        );
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        final var reservation = anEvent.reserveTicket(CustomerId.unique());
        eventRepository.create(anEvent);
        entityManager.flush();
        entityManager.clear();
        final var useCase = new CancelEventUseCase(eventRepository);

        final var output = useCase.execute(new CancelEventUseCase.Input(anEvent.eventId().value()));
        entityManager.flush();
        entityManager.clear();

        Assertions.assertEquals(anEvent.eventId().value(), output.id());
        Assertions.assertEquals("CANCELLED", output.status());
        final var restoredEvent = eventRepository.eventOfId(anEvent.eventId()).orElseThrow();
        Assertions.assertTrue(restoredEvent.cancelled());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
        Assertions.assertEquals(anEvent.name(), restoredEvent.name());
        Assertions.assertEquals(anEvent.date(), restoredEvent.date());
        Assertions.assertEquals(anEvent.totalSpots(), restoredEvent.totalSpots());
        Assertions.assertEquals(aPartner.partnerId(), restoredEvent.partnerId());
        Assertions.assertEquals(1, restoredEvent.allTickets().size());
        Assertions.assertEquals(
                reservation.eventTicketId(), restoredEvent.allTickets().iterator().next().eventTicketId()
        );

        final var cancellations = StreamSupport.stream(outboxJpaRepository.findAll().spliterator(), false)
                .filter(it -> it.content().contains("event.cancelled"))
                .filter(it -> it.content().contains(anEvent.eventId().value()))
                .toList();
        Assertions.assertEquals(1, cancellations.size());
        final var outbox = cancellations.get(0);
        final var cancellation = mapper.readValue(outbox.content(), EventCancelled.class);
        Assertions.assertEquals("event.cancelled", cancellation.type());
        Assertions.assertEquals(anEvent.eventId().value(), cancellation.eventId());
        Assertions.assertEquals(outbox.id().toString(), cancellation.domainEventId());
        Assertions.assertNotNull(cancellation.occurredOn());
        Assertions.assertFalse(outbox.published());

        final var reservations = StreamSupport.stream(outboxJpaRepository.findAll().spliterator(), false)
                .filter(it -> it.content().contains("event-ticket.reserved"))
                .filter(it -> it.content().contains(anEvent.eventId().value()))
                .toList();
        Assertions.assertEquals(1, reservations.size());
        final var reserved = mapper.readValue(reservations.get(0).content(), EventTicketReserved.class);
        Assertions.assertEquals(reservation.eventTicketId().value(), reserved.eventTicketId());
        Assertions.assertEquals(anEvent.eventId().value(), reserved.eventId());

        final var expectedOutboxCount = outboxJpaRepository.count();
        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> useCase.execute(new CancelEventUseCase.Input(anEvent.eventId().value()))
        );
        Assertions.assertEquals("Event already cancelled", actualError.getMessage());
        Assertions.assertTrue(eventRepository.eventOfId(anEvent.eventId()).orElseThrow().cancelled());
        Assertions.assertEquals(expectedOutboxCount, outboxJpaRepository.count());
    }

    @Test
    @DisplayName("Deve considerar ativo um evento inserido sem o novo estado")
    void testDefaultCancellationState() {
        final var aPartner = partnerRepository.create(
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com")
        );
        final var eventId = EventId.unique();
        entityManager.createNativeQuery(
                        "insert into events (id, name, date, total_spots, partner_id) "
                                + "values (:id, :name, :date, :totalSpots, :partnerId)"
                )
                .setParameter("id", UUID.fromString(eventId.value()))
                .setParameter("name", "Disney on Ice")
                .setParameter("date", java.time.LocalDate.parse("2021-01-01"))
                .setParameter("totalSpots", 10)
                .setParameter("partnerId", UUID.fromString(aPartner.partnerId().value()))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        final var restoredEvent = eventRepository.eventOfId(eventId).orElseThrow();

        Assertions.assertFalse(restoredEvent.cancelled());
        Assertions.assertTrue(restoredEvent.allDomainEvents().isEmpty());
    }
}