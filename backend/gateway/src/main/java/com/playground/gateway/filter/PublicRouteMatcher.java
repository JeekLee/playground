package com.playground.gateway.filter;

import java.util.List;
import java.util.regex.Pattern;
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
 *
 * <p>ADR-12 (M2) amended ADR-09 to flatten the docs namespace: the
 * {@code /api/docs/public/**} prefix is removed; only {@code GET /api/docs/{id}}
 * is anonymous-OK (visibility-or-ownership gate enforced inside docs-api).
 * Every other docs route — {@code POST /api/docs} (JSON + multipart),
 * {@code GET /api/docs?scope=mine}, {@code PATCH /api/docs/{id}},
 * {@code POST /api/docs/{id}/publish}, {@code POST /api/docs/{id}/unpublish},
 * {@code DELETE /api/docs/{id}} — requires authentication.
 *
 * <p>S1 (single-author CRUD) implements only the {@code GET /api/docs/{id}}
 * public match — the community feed ({@code GET /api/docs}) and the search /
 * view-counter / OG-preview public surfaces from ADR-12 §7 ship in M2 S2 / S3.
 */
@Component
public class PublicRouteMatcher {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * UUID-shaped tail under {@code /api/docs/}. Constrained to RFC-4122 hex
     * form so the bare base path {@code /api/docs} (the {@code ?scope=mine}
     * query lives there) and sub-routes like {@code /api/docs/{id}/publish}
     * never accidentally match the public rule.
     */
    private static final Pattern DOCS_DETAIL_UUID =
            Pattern.compile("^/api/docs/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * M2 S3 view-counter endpoint: {@code POST /api/docs/{uuid}/view} is
     * anonymous-OK (ADR-12 §10 "same-cookie path regardless of auth state"
     * + brief: "Auth optional").
     */
    private static final Pattern DOCS_VIEW_UUID =
            Pattern.compile("^/api/docs/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/view$");

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.GET, "/"),
            new Rule(HttpMethod.GET, "/docs/public/**"),
            new Rule(HttpMethod.GET, "/chat"),
            new Rule(HttpMethod.GET, "/metrics"),
            // ADR-12 amendment to ADR-09 + M2 S2 brief:
            //  - GET /api/docs/{uuid}  → single-doc anonymous read (visibility gate inside docs-api).
            //  - GET /api/docs         → community feed (every author's public docs).
            //  - GET /api/docs/owner   → owner UUID resolution endpoint (fail-closed null).
            //  - GET /api/docs/search  → public-scope full-text search; mine-scope handled inside docs-api.
            // S1 only listed the UUID detail; S2 adds the other three.
            new Rule(HttpMethod.GET, "/api/docs/{id}", DOCS_DETAIL_UUID),
            new Rule(HttpMethod.GET, "/api/docs"),
            new Rule(HttpMethod.GET, "/api/docs/"),
            new Rule(HttpMethod.GET, "/api/docs/owner"),
            new Rule(HttpMethod.GET, "/api/docs/search"),
            // M2 S3: POST /api/docs/{uuid}/view is anonymous-OK (ADR-12 §10).
            // Auth-required engagement routes (POST/DELETE /like, GET /folders)
            // are NOT in this allowlist — gateway 401's them when the session
            // is missing per the default authenticated() catch-all.
            new Rule(HttpMethod.POST, "/api/docs/{id}/view", DOCS_VIEW_UUID),
            // ADR-14 §G.4: /api/rag/chat/** is auth-only (the legacy /public row
            // is permanently removed). No allowlist entries here.
            new Rule(HttpMethod.GET, "/api/metrics/**"),
            new Rule(null, "/_next/**"),
            new Rule(null, "/favicon.ico"),
            new Rule(null, "/static/**"));

    public boolean isPublic(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        RequestPath path = exchange.getRequest().getPath();
        String pattern = path.value();
        for (Rule rule : RULES) {
            if (rule.method() != null && !rule.method().equals(method)) {
                continue;
            }
            if (rule.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rule entry: optional method filter + either an Ant path pattern or a
     * pre-compiled regex (mutually exclusive). The regex form is used when an
     * Ant pattern would over-match (e.g. {@code /api/docs/*} would also pick up
     * {@code /api/docs/mine}, which must remain authenticated).
     */
    private record Rule(HttpMethod method, String pattern, Pattern regex) {

        Rule(HttpMethod method, String pattern) {
            this(method, pattern, null);
        }

        Rule(HttpMethod method, String pattern, Pattern regex) {
            this.method = method;
            this.pattern = pattern;
            this.regex = regex;
        }

        boolean matches(String path) {
            if (regex != null) {
                return regex.matcher(path).matches();
            }
            return MATCHER.match(pattern, path);
        }
    }
}
