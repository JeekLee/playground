package com.playground.chat.infrastructure.persistence;

import com.playground.chat.application.port.UserDocumentManifestPort;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * docs-api 내부 manifest 엔드포인트를 호출해 [YOUR DOCUMENTS] 목록을 가져온다
 * (SP3a spec D2). chat은 더 이상 docs 스키마를 직접 읽지 않는다. 실패·타임아웃·
 * non-2xx는 빈 리스트로 degrade → 섹션 생략, 턴 계속(구 JDBC 어댑터와 동일).
 */
@Component
public class WebClientUserDocumentManifestAdapter implements UserDocumentManifestPort {

    private static final Logger log = LoggerFactory.getLogger(WebClientUserDocumentManifestAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final String endpoint;

    public WebClientUserDocumentManifestAdapter(
            WebClient.Builder builder,
            @Value("${playground.chat.docs-manifest-url:http://docs-api:18082/internal/docs/manifest}")
            String endpoint) {
        this.webClient = builder.build();
        this.endpoint = endpoint;
    }

    private record ManifestResponse(List<Entry> documents) {
        private record Entry(String id, String title) {}
    }

    @Override
    public List<UserDocumentRef> recentForUser(UserId userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String uri = UriComponentsBuilder.fromHttpUrl(endpoint)
                .queryParam("userId", userId.value().toString())
                .queryParam("limit", limit)
                .build().toUriString();
        try {
            ManifestResponse resp = webClient.get().uri(uri)
                    .retrieve()
                    .bodyToMono(ManifestResponse.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("manifest_fetch_failed userId={} reason={}", userId.value(), e.toString());
                        return Mono.empty();
                    })
                    .block();
            if (resp == null || resp.documents() == null) {
                return List.of();
            }
            List<UserDocumentRef> refs = new ArrayList<>(resp.documents().size());
            int ordinal = 1;
            for (ManifestResponse.Entry e : resp.documents()) {
                refs.add(new UserDocumentRef(ordinal++, UUID.fromString(e.id()), e.title()));
            }
            return refs;
        } catch (RuntimeException e) {
            log.warn("manifest_parse_failed userId={} reason={}", userId.value(), e.toString());
            return List.of();
        }
    }
}
