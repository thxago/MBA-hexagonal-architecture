package br.com.fullcycle.infrastructure.rest.presenters;

import br.com.fullcycle.application.Presenter;
import br.com.fullcycle.application.event.GetEventByIdUseCase;
import br.com.fullcycle.domain.exceptions.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.stream.Stream;

class GetEventByIdPresentersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "CANCELLED"})
    @DisplayName("Deve apresentar JSON completo do evento com HTTP 200")
    void testFullRepresentation(final String status) throws Exception {
        final var output = new GetEventByIdUseCase.Output(
                "event-id", "Disney on Ice", "2021-01-01", 100, status
        );

        final var response = new GetEventByIdResponseEntity().present(Optional.of(output));

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        final var json = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        Assertions.assertTrue(json.isObject());
        Assertions.assertEquals(5, json.size());
        Assertions.assertEquals(output.id(), json.get("id").asText());
        Assertions.assertEquals(output.name(), json.get("name").asText());
        Assertions.assertEquals(output.date(), json.get("date").asText());
        Assertions.assertEquals(output.totalSpots(), json.get("totalSpots").asInt());
        Assertions.assertEquals(status, json.get("status").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "CANCELLED"})
    @DisplayName("Deve apresentar somente id e status em JSON público com HTTP 200")
    void testPublicRepresentation(final String status) throws Exception {
        final var output = new GetEventByIdUseCase.Output(
                "event-id", "Disney on Ice", "2021-01-01", 100, status
        );

        final var response = new PublicGetEventByIdResponseEntity().present(Optional.of(output));

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        final var json = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        Assertions.assertTrue(json.isObject());
        Assertions.assertEquals(2, json.size());
        Assertions.assertEquals(output.id(), json.get("id").asText());
        Assertions.assertEquals(status, json.get("status").asText());
    }

    @ParameterizedTest
    @MethodSource("presenters")
    @DisplayName("Deve apresentar HTTP 404 sem corpo para evento inexistente")
    void testEventNotFound(final Presenter<Optional<GetEventByIdUseCase.Output>, Object> presenter) {
        final var response = Assertions.assertInstanceOf(
                ResponseEntity.class, presenter.present(Optional.empty())
        );

        Assertions.assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Assertions.assertNull(response.getBody());
    }

    @ParameterizedTest
    @MethodSource("presenters")
    @DisplayName("Deve manter o tratamento de erro dos presenters de referência")
    void testError(final Presenter<Optional<GetEventByIdUseCase.Output>, Object> presenter) {
        final var response = Assertions.assertInstanceOf(
                ResponseEntity.class, presenter.present(new ValidationException("Invalid eventId"))
        );

        Assertions.assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Assertions.assertNull(response.getBody());
    }

    private static Stream<Presenter<Optional<GetEventByIdUseCase.Output>, Object>> presenters() {
        return Stream.of(new GetEventByIdResponseEntity(), new PublicGetEventByIdResponseEntity());
    }
}