package com.playground.chat.application.service;

import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.UserModelRef;
import java.util.List;

/** Snapshot of pre-stream side-effects bound to one turn. */
record TurnContext(
        ChatSession session,
        List<Message> truncatedHistory,
        Message savedUser,
        boolean firstTurn,
        List<UserDocumentRef> documents,
        List<UserModelRef> models) {
}
