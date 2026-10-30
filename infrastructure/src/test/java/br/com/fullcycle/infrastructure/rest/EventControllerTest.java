package br.com.fullcycle.infrastructure.rest;

import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.event.Event;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.customer.CustomerRepository;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.partner.PartnerRepository;
import br.com.fullcycle.application.event.CreateEventUseCase;
import br.com.fullcycle.infrastructure.dtos.NewEventDTO;
import br.com.fullcycle.infrastructure.dtos.SubscribeDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class EventControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired
    private EventRepository eventRepository;

    private Customer johnDoe;
    private Partner disney;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        customerRepository.deleteAll();
        partnerRepository.deleteAll();

        johnDoe = customerRepository.create(Customer.newCustomer("John Doe", "123.456.789-00", "john@gmail.com"));
        disney = partnerRepository.create(Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com"));
    }

    @Test
    @DisplayName("Deve criar um evento")
    public void testCreate() throws Exception {

        var event = new NewEventDTO("Disney on Ice", "2021-01-01", 100, disney.partnerId().value());

        final var result = this.mvc.perform(
                        MockMvcRequestBuilders.post("/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(event))
                )
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsByteArray();

        var actualResponse = mapper.readValue(result, NewEventDTO.class);
        Assertions.assertEquals(event.date(), actualResponse.date());
        Assertions.assertEquals(event.totalSpots(), actualResponse.totalSpots());
        Assertions.assertEquals(event.name(), actualResponse.name());
    }

    @Test
    @Transactional
    @DisplayName("Deve comprar um ticket de um evento")
    public void testReserveTicket() throws Exception {

        var event = new NewEventDTO("Disney on Ice", "2021-01-01", 100, disney.partnerId().value());

        final var createResult = this.mvc.perform(
                        MockMvcRequestBuilders.post("/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(event))
                )
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsByteArray();

        var eventId = mapper.readValue(createResult, CreateEventUseCase.Output.class).id();

        var sub = new SubscribeDTO(johnDoe.customerId().value(), null);

        this.mvc.perform(
                        MockMvcRequestBuilders.post("/events/{id}/subscribe", eventId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(sub))
                )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        var actualEvent = eventRepository.eventOfId(EventId.with(eventId)).get();
        Assertions.assertEquals(1, actualEvent.allTickets().size());
    }

    @Test
    @DisplayName("Deve cancelar um evento por REST")
    void testCancelEvent() throws Exception {
        final var anEvent = createEvent();

        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/cancel", anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$", Matchers.aMapWithSize(2)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("CANCELLED"));

        Assertions.assertTrue(eventRepository.eventOfId(anEvent.eventId()).orElseThrow().cancelled());
    }

    @Test
    @DisplayName("Deve retornar 422 ao cancelar evento inexistente")
    void testCancelEventNotFound() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/cancel", EventId.unique().value()))
                .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(MockMvcResultMatchers.content().string("Event not found"));
    }

    @Test
    @DisplayName("Deve retornar 422 ao repetir o cancelamento")
    void testCancelEventTwice() throws Exception {
        final var anEvent = createEvent();
        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/cancel", anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.status().isOk());

        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/cancel", anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(MockMvcResultMatchers.content().string("Event already cancelled"));
    }

    @Test
    @DisplayName("Deve retornar 422 ao inscrever cliente em evento cancelado")
    void testSubscribeToCancelledEvent() throws Exception {
        final var anEvent = createEvent();
        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/cancel", anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.status().isOk());

        mvc.perform(MockMvcRequestBuilders.post("/events/{id}/subscribe", anEvent.eventId().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SubscribeDTO(johnDoe.customerId().value(), null))))
                .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(MockMvcResultMatchers.content().string("Event is cancelled"));

        Assertions.assertTrue(eventRepository.eventOfId(anEvent.eventId()).orElseThrow().allTickets().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "false, default", "false, true", "false, false",
            "true, default", "true, true", "true, false"
    })
    @DisplayName("Deve consultar estado e selecionar a representação pelo header")
    void testGetEvent(final boolean cancelled, final String header) throws Exception {
        final var anEvent = createEvent();
        if (cancelled) {
            anEvent.cancel();
            eventRepository.update(anEvent);
        }
        final var request = MockMvcRequestBuilders.get("/events/{id}", anEvent.eventId().value());
        if (!"default".equals(header)) {
            request.header("X-Public", header);
        }

        final var result = mvc.perform(request)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(anEvent.eventId().value()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(cancelled ? "CANCELLED" : "ACTIVE"));

        if ("true".equals(header)) {
            result.andExpect(MockMvcResultMatchers.jsonPath("$", Matchers.aMapWithSize(2)));
        } else {
            result.andExpect(MockMvcResultMatchers.jsonPath("$", Matchers.aMapWithSize(5)))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("Disney on Ice"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.date").value("2021-01-01"))
                    .andExpect(MockMvcResultMatchers.jsonPath("$.totalSpots").value(100));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deve retornar 404 sem corpo nas duas representações")
    void testGetEventNotFound(final boolean publicRepresentation) throws Exception {
        final var request = MockMvcRequestBuilders.get("/events/{id}", EventId.unique().value());
        if (publicRepresentation) {
            request.header("X-Public", "true");
        }

        mvc.perform(request)
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.content().string(""));
    }

    private Event createEvent() {
        return eventRepository.create(
                Event.newEvent("Disney on Ice", "2021-01-01", 100, disney)
        );
    }
}