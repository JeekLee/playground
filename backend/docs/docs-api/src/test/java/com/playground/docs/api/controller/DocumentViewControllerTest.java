package com.playground.docs.api.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.service.ViewIncrementService;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.shared.error.SharedExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller verification for {@link DocumentViewController} per M2 spec
 * §6.1 row {@code POST /api/docs/{id}/view} + ADR-12 §10.
 *
 * <p>The controller's job is to read the cookie / forwarded-IP and call
 * the service; deeper dedup + visibility-gate behavior is exercised in
 * {@code ViewIncrementServiceTest}.
 */
class DocumentViewControllerTest {

    private final ViewIncrementService viewService = mock(ViewIncrementService.class);
    private final DocumentViewController controller = new DocumentViewController(viewService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void view_with_anon_cookie_forwards_value_to_service() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/{id}/view", docId.toString())
                        .cookie(new Cookie("PLAYGROUND_ANON", "anon-uuid-value")))
                .andExpect(status().isNoContent());

        verify(viewService).increment(eq(docId), eq("anon-uuid-value"), eq("127.0.0.1"));
    }

    @Test
    void view_with_no_cookie_passes_null_anon_and_xff() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/{id}/view", docId.toString())
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8"))
                .andExpect(status().isNoContent());

        // First hop of XFF is used.
        verify(viewService).increment(eq(docId), eq(null), eq("1.2.3.4"));
    }

    @Test
    void view_auth_optional_works_without_user_header() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/{id}/view", docId.toString())
                        .cookie(new Cookie("PLAYGROUND_ANON", "cookie")))
                .andExpect(status().isNoContent());
    }

    @Test
    void view_returns_404_when_doc_not_found() throws Exception {
        UUID docId = UUID.randomUUID();
        doThrow(new DocumentNotFoundException(DocumentId.of(docId)))
                .when(viewService).increment(eq(docId), eq("cookie"), eq("127.0.0.1"));

        mockMvc.perform(post("/{id}/view", docId.toString())
                        .cookie(new Cookie("PLAYGROUND_ANON", "cookie")))
                .andExpect(status().isNotFound());
    }

    @Test
    void view_with_non_uuid_returns_404() throws Exception {
        mockMvc.perform(post("/{id}/view", "not-a-uuid")
                        .cookie(new Cookie("PLAYGROUND_ANON", "cookie")))
                .andExpect(status().isNotFound());
    }
}
