package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.dto.FolderListItemDto;
import com.playground.docs.application.service.FolderListService;
import com.playground.shared.error.SharedExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification for {@link FolderController} per M2 spec
 * §6.1 row {@code GET /api/docs/folders} + §6.4 {@code FolderListItem}.
 */
class FolderControllerTest {

    private final FolderListService folderService = mock(FolderListService.class);
    private final FolderController controller = new FolderController(folderService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void returns_folder_list_for_authenticated_caller() throws Exception {
        UUID caller = UUID.randomUUID();
        when(folderService.listFolders(caller)).thenReturn(List.of(
                new FolderListItemDto("/", 5L),
                new FolderListItemDto("/agents/", 8L),
                new FolderListItemDto("/agents/build-log/", 3L)));

        mockMvc.perform(get("/folders").header("X-User-Id", caller.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(3)))
                .andExpect(jsonPath("$.items[0].path", is("/")))
                .andExpect(jsonPath("$.items[0].count", is(5)))
                .andExpect(jsonPath("$.items[1].path", is("/agents/")))
                .andExpect(jsonPath("$.items[1].count", is(8)))
                .andExpect(jsonPath("$.items[2].path", is("/agents/build-log/")))
                .andExpect(jsonPath("$.items[2].count", is(3)));
    }

    @Test
    void returns_401_when_user_header_missing() throws Exception {
        mockMvc.perform(get("/folders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns_401_when_user_header_invalid_uuid() throws Exception {
        mockMvc.perform(get("/folders").header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void empty_list_when_user_has_no_documents() throws Exception {
        UUID caller = UUID.randomUUID();
        when(folderService.listFolders(caller)).thenReturn(List.of());

        mockMvc.perform(get("/folders").header("X-User-Id", caller.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)));
    }
}
