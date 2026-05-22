package com.playground.identity.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.identity.application.dto.InternalUserDto;
import com.playground.identity.application.service.InternalUserService;
import com.playground.identity.domain.exception.UserNotFoundException;
import com.playground.shared.error.SharedExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer test for the three {@code /internal/users/**} routes per
 * ADR-12 §8. Verifies the wire shape (id, googleSub, displayName, avatarUrl —
 * NO email leak) and the 404 path for an unknown id/sub.
 */
class InternalUserControllerTest {

    private final InternalUserService service = mock(InternalUserService.class);
    private final InternalUserController controller = new InternalUserController(service);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void by_google_sub_returns_resolved_user() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.findByGoogleSub("g-123"))
                .thenReturn(new InternalUserDto(
                        userId.toString(), "g-123", "Alice", "https://example.com/a.png"));

        mockMvc.perform(get("/internal/users/by-google-sub/g-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.googleSub", is("g-123")))
                .andExpect(jsonPath("$.displayName", is("Alice")))
                .andExpect(jsonPath("$.avatarUrl", is("https://example.com/a.png")))
                // M2 S2 brief explicitly requires email to be absent from the internal payload.
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void by_google_sub_returns_404_when_not_found() throws Exception {
        when(service.findByGoogleSub(any()))
                .thenThrow(new UserNotFoundException("googleSub=missing"));

        mockMvc.perform(get("/internal/users/by-google-sub/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void by_id_returns_resolved_user() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.findById(userId))
                .thenReturn(new InternalUserDto(userId.toString(), "g-1", "Bob", null));

        mockMvc.perform(get("/internal/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(userId.toString())))
                .andExpect(jsonPath("$.displayName", is("Bob")))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist()); // null serialized → absent
    }

    @Test
    void by_id_with_garbage_uuid_returns_404() throws Exception {
        mockMvc.perform(get("/internal/users/{id}", "not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batch_returns_users_in_envelope() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.findAllByIds(any())).thenReturn(List.of(
                new InternalUserDto(a.toString(), "g-a", "Alice", null),
                new InternalUserDto(b.toString(), "g-b", "Bob", null)));

        mockMvc.perform(get("/internal/users").param("ids", a + "," + b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()", is(2)))
                .andExpect(jsonPath("$.users[0].displayName", is("Alice")));
    }

    @Test
    void batch_with_blank_ids_returns_empty_users() throws Exception {
        mockMvc.perform(get("/internal/users").param("ids", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()", is(0)));
    }
}
