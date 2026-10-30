package br.com.fullcycle.application.event;

import br.com.fullcycle.application.repository.InMemoryEventRepository;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventCancelled;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

class CancelEventUseCaseTest {

    private InMemoryEventRepository eventRepository;
    private CancelEventUseCase useCase;

    @BeforeEach
    void setUp() {
        eventRepository = new InMemoryEventRepository();
        useCase = new CancelEventUseCase(eventRepository);
    }

    @Test
    @DisplayName("Deve cancelar um evento ativo")
    void testCancelEvent() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var anEvent = eventRepository.create(
                Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner)
        );

        final var output = useCase.execute(new CancelEventUseCase.Input(anEvent.eventId().value()));

        Assertions.assertEquals(anEvent.eventId().value(), output.id());
        Assertions.assertEquals("CANCELLED", output.status());
        final var savedEvent = eventRepository.eventOfId(anEvent.eventId()).orElseThrow();
        Assertions.assertTrue(savedEvent.cancelled());
        Assertions.assertEquals(1, savedEvent.allDomainEvents().size());
        final var cancellation = Assertions.assertInstanceOf(
                EventCancelled.class, savedEvent.allDomainEvents().iterator().next()
        );
        Assertions.assertEquals(anEvent.eventId().value(), cancellation.eventId());
    }

    @Test
    @DisplayName("Não deve cancelar um evento inexistente")
    void testCancelEventNotFound() {
        final var input = new CancelEventUseCase.Input(EventId.unique().value());

        final var actualError = Assertions.assertThrows(
                ValidationException.class, () -> useCase.execute(input)
        );

        Assertions.assertEquals("Event not found", actualError.getMessage());
    }

    @Test
    @DisplayName("Não deve cancelar um evento já cancelado")
    void testCancelEventAlreadyCancelled() {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 10, aPartner);
        anEvent.cancel();
        eventRepository.create(anEvent);
        final var expectedDomainEvents = Set.copyOf(anEvent.allDomainEvents());

        final var actualError = Assertions.assertThrows(
                ValidationException.class,
                () -> useCase.execute(new CancelEventUseCase.Input(anEvent.eventId().value()))
        );

        Assertions.assertEquals("Event already cancelled", actualError.getMessage());
        final var savedEvent = eventRepository.eventOfId(anEvent.eventId()).orElseThrow();
        Assertions.assertTrue(savedEvent.cancelled());
        Assertions.assertEquals(expectedDomainEvents, savedEvent.allDomainEvents());
    }
}
