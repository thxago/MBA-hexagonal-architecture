package br.com.fullcycle.application.event;

import br.com.fullcycle.application.repository.InMemoryEventRepository;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GetEventByIdUseCaseTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deve consultar o evento por id com seu estado atual")
    void testGetEventById(final boolean cancelled) {
        final var aPartner =
                Partner.newPartner("John Doe", "41.536.538/0001-00", "john.doe@gmail.com");
        final var anEvent = Event.newEvent("Disney on Ice", "2021-01-01", 100, aPartner);
        if (cancelled) {
            anEvent.cancel();
        }
        final var eventRepository = new InMemoryEventRepository();
        eventRepository.create(anEvent);
        final var useCase = new GetEventByIdUseCase(eventRepository);

        final var output = useCase.execute(new GetEventByIdUseCase.Input(anEvent.eventId().value()));

        Assertions.assertEquals(
                new GetEventByIdUseCase.Output(
                        anEvent.eventId().value(), "Disney on Ice", "2021-01-01",
                        100, cancelled ? "CANCELLED" : "ACTIVE"
                ),
                output.orElseThrow()
        );
    }

    @Test
    @DisplayName("Deve retornar vazio ao consultar evento inexistente")
    void testGetEventNotFound() {
        final var useCase = new GetEventByIdUseCase(new InMemoryEventRepository());

        final var output = useCase.execute(new GetEventByIdUseCase.Input(EventId.unique().value()));

        Assertions.assertTrue(output.isEmpty());
    }
}