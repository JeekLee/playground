package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.application.dto.FolderListItemDto;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.model.id.AuthorId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FolderListService} per M2 spec §6.1 row +
 * §6.4 {@code FolderListItem} + §10 "Folder listing scoping".
 */
@ExtendWith(MockitoExtension.class)
class FolderListServiceTest {

    @Mock
    DocumentRepository repository;

    @Test
    void delegates_to_repository_and_returns_grouped_list() {
        UUID caller = UUID.randomUUID();
        List<FolderListItemDto> expected = List.of(
                new FolderListItemDto("/", 5L),
                new FolderListItemDto("/agents/", 8L),
                new FolderListItemDto("/agents/build-log/", 3L));
        when(repository.listFolders(AuthorId.of(caller))).thenReturn(expected);

        FolderListService service = new FolderListService(repository);
        List<FolderListItemDto> result = service.listFolders(caller);

        assertThat(result).containsExactlyElementsOf(expected);
        // Tenant isolation: the only predicate is the caller's user id.
        verify(repository).listFolders(AuthorId.of(caller));
    }
}
