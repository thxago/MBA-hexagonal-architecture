package br.com.fullcycle.application.ticket;

import br.com.fullcycle.application.repository.InMemoryTicketRepository;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketId;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class CancelEventTicketsUseCaseTest {

    private InMemoryTicketRepository ticketRepository;
    private CancelEventTicketsUseCase useCase;

    @BeforeEach
    void setUp() {
        ticketRepository = new InMemoryTicketRepository();
        useCase = new CancelEventTicketsUseCase(ticketRepository);
    }

    @Test
    @DisplayName("Deve cancelar todos os ingressos do evento sem alterar outros eventos")
    void testCancelEventTickets() {
        final var eventId = EventId.unique();
        for (final var status : TicketStatus.values()) {
            ticketRepository.create(new Ticket(
                    TicketId.unique(), CustomerId.unique(), eventId, status,
                    status == TicketStatus.PAID ? Instant.now() : null, Instant.now()
            ));
        }
        final var otherTicket = ticketRepository.create(
                Ticket.newTicket(CustomerId.unique(), EventId.unique())
        );

        final var output = useCase.execute(new CancelEventTicketsUseCase.Input(eventId.value()));

        Assertions.assertEquals(eventId.value(), output.eventId());
        Assertions.assertEquals(4, output.cancelledTickets());
        ticketRepository.ticketsByEventId(eventId).forEach(ticket ->
                Assertions.assertEquals(
                        TicketStatus.CANCELLED,
                        ticketRepository.ticketOfId(ticket.ticketId()).orElseThrow().status()
                )
        );
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );
    }

    @Test
    @DisplayName("Deve aceitar evento sem ingressos")
    void testCancelEventWithoutTickets() {
        final var eventId = EventId.unique();

        final var output = useCase.execute(new CancelEventTicketsUseCase.Input(eventId.value()));

        Assertions.assertEquals(eventId.value(), output.eventId());
        Assertions.assertEquals(0, output.cancelledTickets());
    }

    @Test
    @DisplayName("Deve reprocessar o cancelamento de ingressos sem erro")
    void testCancelEventTicketsTwice() {
        final var eventId = EventId.unique();
        final var ticket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), eventId));
        final var input = new CancelEventTicketsUseCase.Input(eventId.value());
        useCase.execute(input);

        final var output = useCase.execute(input);

        Assertions.assertEquals(1, output.cancelledTickets());
        Assertions.assertEquals(1, ticketRepository.ticketsByEventId(eventId).size());
        Assertions.assertEquals(
                TicketStatus.CANCELLED,
                ticketRepository.ticketOfId(ticket.ticketId()).orElseThrow().status()
        );
    }
}