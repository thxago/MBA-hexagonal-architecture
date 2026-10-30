package br.com.fullcycle.infrastructure.repositories;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketRepository;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Transactional
class TicketDatabaseRepositoryIT extends IntegrationTest {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Deve buscar somente os ingressos do evento e restaurar o cancelamento")
    void testTicketsByEventIdAndCancellation() {
        final var eventId = EventId.unique();
        final var firstTicket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), eventId));
        final var cancelledTicket = Ticket.newTicket(CustomerId.unique(), eventId);
        cancelledTicket.cancel();
        ticketRepository.create(cancelledTicket);
        final var otherTicket = ticketRepository.create(
                Ticket.newTicket(CustomerId.unique(), EventId.unique())
        );
        entityManager.flush();
        entityManager.clear();

        final var tickets = ticketRepository.ticketsByEventId(eventId);

        Assertions.assertEquals(
                Set.of(firstTicket.ticketId(), cancelledTicket.ticketId()),
                tickets.stream().map(Ticket::ticketId).collect(Collectors.toSet())
        );
        Assertions.assertEquals(
                TicketStatus.CANCELLED,
                ticketRepository.ticketOfId(cancelledTicket.ticketId()).orElseThrow().status()
        );
        final var restoredTicket = tickets.stream()
                .filter(it -> it.ticketId().equals(firstTicket.ticketId()))
                .findFirst().orElseThrow();
        restoredTicket.cancel();
        ticketRepository.update(restoredTicket);
        entityManager.flush();
        entityManager.clear();

        ticketRepository.ticketsByEventId(eventId).forEach(ticket ->
                Assertions.assertEquals(TicketStatus.CANCELLED, ticket.status())
        );
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );
        Assertions.assertTrue(ticketRepository.ticketsByEventId(EventId.unique()).isEmpty());
    }
}