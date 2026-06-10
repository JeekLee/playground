package com.playground.metrics.app;

import com.playground.metrics.app.dto.LogsResponse;
import com.playground.metrics.app.port.LokiPort;
import com.playground.metrics.domain.LogQlTemplate;
import com.playground.metrics.domain.ServiceAllowlist;
import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * LogQL composer + Loki query per spec §5.4. The {@code service} parameter
 * is allowlisted via {@link ServiceAllowlist}; the {@code search} parameter
 * is escaped into a quoted regex via {@link LogQlTemplate}; the
 * {@code since} parameter is parsed into a {@link Duration}; the
 * {@code limit} parameter is clamped to {@link LogQlTemplate#MAX_LIMIT}.
 *
 * <p>Slice 1 always returns {@code hasMore=false} + {@code nextCursor=null}
 * — cursor pagination is M5.1 territory.
 */
@Service
@RequiredArgsConstructor
public class QueryLogsUseCase {

    private static final Pattern SINCE_PATTERN = Pattern.compile("^(\\d+)(s|m|h|d)$");
    private static final Duration DEFAULT_SINCE = Duration.ofMinutes(15);
    private static final Duration MAX_SINCE = Duration.ofDays(7);

    private final LokiPort loki;

    public Mono<LogsResponse> execute(String service, String sinceToken, String search, Integer limit) {
        if (!ServiceAllowlist.contains(service)) {
            throw ExceptionCreator.of(MetricsErrorCode.UNKNOWN_SERVICE, service).build();
        }
        Duration since;
        try {
            since = parseSince(sinceToken);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(MetricsErrorCode.INVALID_RANGE, sinceToken).build();
        }
        int clamped = LogQlTemplate.clampLimit(limit);
        String logql = LogQlTemplate.forService(service, search);

        return loki.queryRange(logql, since, clamped)
                .map(entries -> new LogsResponse(entries, false, null));
    }

    static Duration parseSince(String token) {
        if (token == null || token.isBlank()) {
            return DEFAULT_SINCE;
        }
        Matcher m = SINCE_PATTERN.matcher(token.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid since token: " + token);
        }
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("unknown since unit: " + m.group(2));
        };
        if (d.compareTo(Duration.ofSeconds(1)) < 0 || d.compareTo(MAX_SINCE) > 0) {
            throw new IllegalArgumentException("since out of range: " + token);
        }
        return d;
    }
}
