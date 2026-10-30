package br.com.fullcycle.infrastructure.gateways;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.ticket.CancelEventTicketsUseCase;
import br.com.fullcycle.application.ticket.CreateTicketForCustomerUseCase;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventCancelled;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventTicketId;
import br.com.fullcycle.domain.event.EventTicketReserved;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketRepository;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:consumer_queue_test;MODE=MYSQL;DATABASE_TO_LOWER=TRUE"
})
class ConsumerQueueGatewayIT extends IntegrationTest {

    @Autowired
    private QueueGateway queueGateway;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ObjectMapper mapper;

    @SpyBean
    private CancelEventTicketsUseCase cancelEventTicketsUseCase;

    @SpyBean
    private CreateTicketForCustomerUseCase createTicketForCustomerUseCase;

    @Test
    @DisplayName("Deve cancelar ingressos pela fila em outra thread e aceitar reprocessamento")
    void testCancelEventTicketsThroughQueue() throws Exception {
        final var eventId = EventId.unique();
        final var firstTicket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), eventId));
        final var secondTicket = ticketRepository.create(Ticket.newTicket(CustomerId.unique(), eventId));
        final var otherTicket = ticketRepository.create(
                Ticket.newTicket(CustomerId.unique(), EventId.unique())
        );
        final var content = mapper.writeValueAsString(new EventCancelled(eventId));

        publishCancellationAndWait(content);

        Assertions.assertEquals(
                TicketStatus.CANCELLED,
                ticketRepository.ticketOfId(firstTicket.ticketId()).orElseThrow().status()
        );
        Assertions.assertEquals(
                TicketStatus.CANCELLED,
                ticketRepository.ticketOfId(secondTicket.ticketId()).orElseThrow().status()
        );
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );

        publishCancellationAndWait(content);

        final var tickets = ticketRepository.ticketsByEventId(eventId);
        Assertions.assertEquals(2, tickets.size());
        tickets.forEach(ticket -> Assertions.assertEquals(TicketStatus.CANCELLED, ticket.status()));
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );
    }

    @Test
    @DisplayName("Deve preservar a criação de ingresso pelo evento de reserva")
    void testCreateTicketThroughQueue() throws Exception {
        final var reservation = new EventTicketReserved(
                EventTicketId.unique(), EventId.unique(), CustomerId.unique()
        );
        final var completed = new CompletableFuture<Thread>();
        doAnswer(invocation -> {
            final var output = invocation.callRealMethod();
            completed.complete(Thread.currentThread());
            return output;
        }).when(createTicketForCustomerUseCase).execute(any(CreateTicketForCustomerUseCase.Input.class));

        queueGateway.publish(mapper.writeValueAsString(reservation));

        Assertions.assertNotSame(Thread.currentThread(), completed.get(10, TimeUnit.SECONDS));
        final var tickets = ticketRepository.ticketsByEventId(EventId.with(reservation.eventId()));
        Assertions.assertEquals(1, tickets.size());
        Assertions.assertEquals(reservation.customerId(), tickets.get(0).customerId().value());
        Assertions.assertEquals(TicketStatus.PENDING, tickets.get(0).status());
    }

    private void publishCancellationAndWait(final String content) throws Exception {
        final var completed = new CompletableFuture<Thread>();
        doAnswer(invocation -> {
            final var output = invocation.callRealMethod();
            completed.complete(Thread.currentThread());
            return output;
        }).when(cancelEventTicketsUseCase).execute(any(CancelEventTicketsUseCase.Input.class));

        queueGateway.publish(content);

        Assertions.assertNotSame(Thread.currentThread(), completed.get(10, TimeUnit.SECONDS));
    }
}