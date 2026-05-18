package com.playground.docs.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.service.DocumentLikeService;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.shared.error.SharedExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification for {@link DocumentLikeController} per M2
 * spec §6.1 rows {@code POST /api/docs/{id}/like} +
 * {@code DELETE /api/docs/{id}/like}.
 *
 * <p>Auth header missing → 401. Document not found / non-UUID → 404. Both
 * succeed paths return 204 with no body.
 */
class DocumentLikeControllerTest {

    private final DocumentLikeService likeService = mock(DocumentLikeService.class);
    private final DocumentLikeController controller = new DocumentLikeController(likeService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void post_like_returns_204_when_caller_authenticated() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();

        mockMvc.perform(post("/{id}/like", docId.toString())
                        .header("X-User-Id", caller.toString()))
                .andExpect(status().isNoContent());

        verify(likeService).like(docId, caller);
    }

    @Test
    void delete_like_returns_204_when_caller_authenticated() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();

        mockMvc.perform(delete("/{id}/like", docId.toString())
                        .header("X-User-Id", caller.toString()))
                .andExpect(status().isNoContent());

        verify(likeService).unlike(docId, caller);
    }

    @Test
    void post_like_returns_401_when_user_header_missing() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(post("/{id}/like", docId.toString()))
                .andExpect(status().isUnauthorized());

        verify(likeService, never()).like(any(), any());
    }

    @Test
    void delete_like_returns_401_when_user_header_missing() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(delete("/{id}/like", docId.toString()))
                .andExpect(status().isUnauthorized());

        verify(likeService, never()).unlike(any(), any());
    }

    @Test
    void post_like_returns_404_when_doc_not_found() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        doThrow(new DocumentNotFoundException(DocumentId.of(docId)))
                .when(likeService).like(docId, caller);

        mockMvc.perform(post("/{id}/like", docId.toString())
                        .header("X-User-Id", caller.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_like_returns_404_when_id_is_not_uuid() throws Exception {
        mockMvc.perform(post("/{id}/like", "not-a-uuid")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }
}
