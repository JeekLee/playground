plugins {
    id("playground.bc-domain")
}

// ADR-15 §2 — pure value objects + PromQL/LogQL template constants. No Spring
// imports beyond the compileOnly spring-context (allowed for the rare
// @Service on a domain service — none used in M5 P0 yet) and compileOnly
// spring-web (HttpStatus reference if a domain exception is added later).
// No additional dependencies.
