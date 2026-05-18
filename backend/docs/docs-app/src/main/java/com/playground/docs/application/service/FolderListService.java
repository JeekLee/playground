package com.playground.docs.application.service;

import com.playground.docs.application.dto.FolderListItemDto;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.model.id.AuthorId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service for the folder-list endpoint per M2 spec §6.1
 * {@code GET /api/docs/folders} + §6.4 {@code FolderListItem}.
 *
 * <p>Folders are <em>implicit</em> in M2 (spec §4.1): the list is computed
 * as {@code SELECT path, COUNT(*) FROM docs.documents WHERE user_id = ?
 * GROUP BY path ORDER BY path}. No empty folders — a folder exists only if
 * at least one document lives at the exact path.
 *
 * <p>Tenant isolation: the caller's UUID is the only predicate the repository
 * applies, so user A never sees user B's paths (non-functional requirement
 * spec §10).
 */
@Service
public class FolderListService {

    private final DocumentRepository repository;

    public FolderListService(DocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<FolderListItemDto> listFolders(UUID callerId) {
        return repository.listFolders(AuthorId.of(callerId));
    }
}
