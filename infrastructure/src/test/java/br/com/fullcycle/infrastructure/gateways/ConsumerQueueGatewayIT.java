package br.com.fullcycle.infrastructure.gateways;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.event.CancelEventUseCase;
import br.com.fullcycle.application.event.SubscribeCustomerToEventUseCase;
import br.com.fullcycle.application.ticket.CancelEventTicketsUseCase;
import br.com.fullcycle.application.ticket.CreateTicketForCustomerUseCase;
import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.customer.CustomerRepository;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventCancelled;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.event.EventTicketId;
import br.com.fullcycle.domain.event.EventTicketReserved;
import br.com.fullcycle.domain.event.ticket.Ticket;
import br.com.fullcycle.domain.event.ticket.TicketId;
import br.com.fullcycle.domain.event.ticket.TicketRepository;
import br.com.fullcycle.domain.event.ticket.TicketStatus;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import br.com.fullcycle.infrastructure.jpa.entities.OutboxEntity;
import br.com.fullcycle.infrastructure.jpa.repositories.OutboxJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OutboxJpaRepository outboxJpaRepository;

    @Autowired
    private SubscribeCustomerToEventUseCase subscribeCustomerToEventUseCase;

    @Autowired
    private CancelEventUseCase cancelEventUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    @DisplayName("Deve completar reserva e cancelamento pela outbox e relay reais com reprocessamento")
    void testCancellationThroughOutboxRelay() throws Exception {
        final var partner = partnerRepository.create(
                Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com")
        );
        final var firstCustomer = customerRepository.create(
                Customer.newCustomer("John Doe", "123.456.789-00", "john@gmail.com")
        );
        final var secondCustomer = customerRepository.create(
                Customer.newCustomer("Jane Doe", "123.456.789-01", "jane@gmail.com")
        );
        final var event = eventRepository.create(
                Event.newEvent("Disney on Ice", "2021-01-01", 100, partner)
        );
        final var otherEvent = eventRepository.create(
                Event.newEvent("Disney Concert", "2021-01-02", 100, partner)
        );
        final var ticketsCreated = new CountDownLatch(3);
        doAnswer(invocation -> {
            final var output = invocation.callRealMethod();
            final CreateTicketForCustomerUseCase.Input input = invocation.getArgument(0);
            if (input.eventId().equals(event.eventId().value())
                    || input.eventId().equals(otherEvent.eventId().value())) {
                ticketsCreated.countDown();
            }
            return output;
        }).when(createTicketForCustomerUseCase).execute(any(CreateTicketForCustomerUseCase.Input.class));
        subscribeCustomerToEventUseCase.execute(new SubscribeCustomerToEventUseCase.Input(
                firstCustomer.customerId().value(), event.eventId().value()
        ));
        subscribeCustomerToEventUseCase.execute(new SubscribeCustomerToEventUseCase.Input(
                secondCustomer.customerId().value(), event.eventId().value()
        ));
        subscribeCustomerToEventUseCase.execute(new SubscribeCustomerToEventUseCase.Input(
                firstCustomer.customerId().value(), otherEvent.eventId().value()
        ));
        Assertions.assertTrue(ticketsCreated.await(10, TimeUnit.SECONDS), "Ticket creation did not complete");
        Assertions.assertEquals(2, ticketRepository.ticketsByEventId(event.eventId()).size());
        Assertions.assertEquals(1, ticketRepository.ticketsByEventId(otherEvent.eventId()).size());
        final var expectedTicketIds = ticketRepository.ticketsByEventId(event.eventId()).stream()
                .map(Ticket::ticketId).collect(Collectors.toSet());
        final var otherTicket = ticketRepository.ticketsByEventId(otherEvent.eventId()).get(0);
        final var completed = observeCancellation();

        final var outbox = new TransactionTemplate(transactionManager).execute(status -> {
            final var output = cancelEventUseCase.execute(
                    new CancelEventUseCase.Input(event.eventId().value())
            );
            Assertions.assertEquals(event.eventId().value(), output.id());
            Assertions.assertEquals("CANCELLED", output.status());
            Assertions.assertTrue(eventRepository.eventOfId(event.eventId()).orElseThrow().cancelled());
            ticketRepository.ticketsByEventId(event.eventId()).forEach(ticket ->
                    Assertions.assertEquals(TicketStatus.PENDING, ticket.status())
            );
            final var cancellation = StreamSupport.stream(outboxJpaRepository.findAll().spliterator(), false)
                    .filter(it -> it.content().contains("event.cancelled"))
                    .filter(it -> it.content().contains(event.eventId().value()))
                    .findFirst().orElseThrow();
            Assertions.assertFalse(cancellation.published());
            return cancellation;
        });

        Assertions.assertNotNull(outbox);
        final var cancellation = mapper.readValue(outbox.content(), EventCancelled.class);
        Assertions.assertEquals(event.eventId().value(), cancellation.eventId());
        Assertions.assertEquals(outbox.id().toString(), cancellation.domainEventId());
        Assertions.assertNotSame(Thread.currentThread(), completed.get(10, TimeUnit.SECONDS));
        assertPublished(outbox);
        assertCancelledTickets(event.eventId(), expectedTicketIds);
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );
        Assertions.assertFalse(eventRepository.eventOfId(otherEvent.eventId()).orElseThrow().cancelled());

        final var replayCompleted = observeCancellation();
        final var replay = outboxJpaRepository.findById(outbox.id()).orElseThrow();
        replay.setPublished(false);
        outboxJpaRepository.save(replay);

        Assertions.assertNotSame(Thread.currentThread(), replayCompleted.get(10, TimeUnit.SECONDS));
        assertPublished(outbox);
        assertCancelledTickets(event.eventId(), expectedTicketIds);
        Assertions.assertEquals(
                TicketStatus.PENDING,
                ticketRepository.ticketOfId(otherTicket.ticketId()).orElseThrow().status()
        );
    }

    private void publishCancellationAndWait(final String content) throws Exception {
        final var completed = observeCancellation();

        queueGateway.publish(content);

        Assertions.assertNotSame(Thread.currentThread(), completed.get(10, TimeUnit.SECONDS));
    }

    private CompletableFuture<Thread> observeCancellation() {
        final var completed = new CompletableFuture<Thread>();
        doAnswer(invocation -> {
            try {
                final var output = invocation.callRealMethod();
                completed.complete(Thread.currentThread());
                return output;
            } catch (Throwable error) {
                completed.completeExceptionally(error);
                throw error;
            }
        }).when(cancelEventTicketsUseCase).execute(any(CancelEventTicketsUseCase.Input.class));

        return completed;
    }

    private void assertPublished(final OutboxEntity outbox) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // The relay holds this row lock until publication is committed.
            final var persisted = entityManager.find(
                    OutboxEntity.class, outbox.id(), LockModeType.PESSIMISTIC_WRITE,
                    Map.of("jakarta.persistence.lock.timeout", 10_000)
            );
            Assertions.assertTrue(persisted.published());
        });
    }

    private void assertCancelledTickets(final EventId eventId, final Set<TicketId> expectedTicketIds) {
        final var tickets = ticketRepository.ticketsByEventId(eventId);
        Assertions.assertEquals(
                expectedTicketIds, tickets.stream().map(Ticket::ticketId).collect(Collectors.toSet())
        );
        tickets.forEach(ticket -> Assertions.assertEquals(TicketStatus.CANCELLED, ticket.status()));
    }
}