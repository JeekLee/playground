package com.playground.chat.application.service;

import com.playground.chat.application.dto.SessionDetailView;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session CRUD per spec §5.3. Ownership enforced at the SQL layer
 * (every read/write filters on {@code user_id}). 404 ("session not found")
 * is returned both for "not in DB" and "not yours" per the spec §5.1
 * existence-leak-neutral wording.
 *
 * <p>Read-side detail assembly (history reload) is delegated to
 * {@link SessionDetailLoader}; this service stays a thin CRUD layer.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionDetailLoader sessionDetailLoader;
    private final Clock clock;

    @Transactional
    public ChatSession create(UserId caller) {
        ChatSession session = ChatSession.newSession(caller, clock.instant());
        return sessionRepository.save(session);
    }

    public List<SessionRepository.SessionSummary> list(UserId caller) {
        return sessionRepository.listForUser(caller);
    }

    @Transactional
    public ChatSession rename(SessionId id, UserId caller, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw ExceptionCreator.of(ChatErrorCode.SESSION_TITLE_BLANK).build();
        }
        boolean updated = sessionRepository.rename(id, caller, newTitle);
        if (!updated) {
            throw ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build();
        }
        return sessionRepository.findOwned(id, caller)
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build());
    }

    @Transactional
    public void delete(SessionId id, UserId caller) {
        boolean deleted = sessionRepository.deleteOwned(id, caller);
        if (!deleted) {
            // Idempotent per spec §5.3 — return 200/204 if the session was already gone.
            // We still validate ownership semantics; "not owned" is indistinguishable
            // from "already deleted" by design.
        }
    }

    /** Resolve session detail (404 if not owned). */
    public SessionDetailView loadDetail(SessionId id, UserId caller) {
        return sessionDetailLoader.load(id, caller);
    }
}
