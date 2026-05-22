package com.playground.docs.domain.exception;

import com.playground.docs.domain.model.id.DocumentId;
import com.playground.shared.error.NotFoundException;

/**
 * Convenience subclass for the 404 path. Per M2 spec §6.5 + §10 "Tenant
 * isolation" this is also thrown when the caller is not the author of a
 * private doc — the API surface never reveals the difference between
 * "missing" and "forbidden" for private docs.
 */
public class DocumentNotFoundException extends NotFoundException {

    public DocumentNotFoundException(DocumentId id) {
        super(DocsErrorCode.DOCUMENT_NOT_FOUND, id.toString());
    }

    public DocumentNotFoundException(String idString) {
        super(DocsErrorCode.DOCUMENT_NOT_FOUND, idString);
    }
}
