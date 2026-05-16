package com.playground.gateway.filter;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

/**
 * Shared classifier for the public allowlist (per ADR-09 + ADR-10 §3). Used by
 * {@link AnonCookieFilter} (and any future cost-protection filter) to decide
 * whether a request targets a public route — kept in lockstep with the
 * SecurityWebFilterChain config in {@link com.playground.gateway.security
 * .GatewaySecurityConfig}.
 */
@Component
public class PublicRouteMatcher {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/"),
            new Rule(HttpMethod.GET, "/docs/public/**"),
            new Rule(HttpMethod.GET, "/chat"),
            new Rule(HttpMethod.GET, "/metrics"),
            new Rule(HttpMethod.GET, "/api/docs/public/**"),
            new Rule(HttpMethod.POST, "/api/rag/chat/public"),
            new Rule(HttpMethod.GET, "/api/metrics/**"),
            new Rule(null, "/_next/**"),
            new Rule(null, "/favicon.ico"),
            new Rule(null, "/static/**"));

    public boolean isPublic(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        RequestPath path = exchange.getRequest().getPath();
        String pattern = path.value();
        for (Rule rule : RULES) {
            if (rule.method != null && !rule.method.equals(method)) {
                continue;
            }
            if (MATCHER.match(rule.pattern, pattern)) {
                return true;
            }
        }
        return false;
    }

    private record Rule(HttpMethod method, String pattern) {}
}
