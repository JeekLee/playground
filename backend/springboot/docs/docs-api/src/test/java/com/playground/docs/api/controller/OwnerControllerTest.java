package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.service.OwnerService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies {@code GET /api/docs/owner} per M2 spec §6.3 + ADR-12 §8.
 * Fail-closed: empty {@code Optional} surfaces as {@code { "ownerUserId": null }}.
 */
class OwnerControllerTest {

    private final OwnerService ownerService = mock(OwnerService.class);
    private final OwnerController controller = new OwnerController(ownerService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returns_owner_uuid_when_resolved() throws Exception {
        UUID owner = UUID.randomUUID();
        when(ownerService.ownerUserId()).thenReturn(Optional.of(owner));

        mockMvc.perform(get("/owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId", is(owner.toString())));
    }

    @Test
    void returns_null_when_owner_unresolved() throws Exception {
        when(ownerService.ownerUserId()).thenReturn(Optional.empty());

        mockMvc.perform(get("/owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist()); // null serialized → absent
    }
}
