plugins {
    id("playground.bc-domain")
}

// M8 (ADR-18 §1) — massing-gen-domain is Spring-free pure Java per ADR-02 v2.
// Hosts the MassingAlgorithm, Program/Room/SiteFootprint/RoomBox VOs,
// MassingErrorCode enum, MassingException hierarchy, and MassingSummary
// formatter. Zero runtime dependencies beyond shared-kernel + JUnit (test).
//
// MassingException extends shared-kernel's AbstractException directly (not
// one of the 5 HTTP-typed subclasses) because M8's error codes span 422 /
// 502 / 504 — statuses without a dedicated shared-kernel subclass today.
// The override returns the appropriate HttpStatus + Level (slf4j) per
// MassingErrorCode — so slf4j-api lands on the compile classpath as
// compileOnly (matches shared-kernel's own pattern for the abstract
// AbstractException.logLevel() method).
dependencies {
    "compileOnly"("org.slf4j:slf4j-api")

    // Tests instantiate MassingException through MassingErrorCode (which
    // statically references HttpStatus). Spring-web stays compileOnly for
    // production sources (-domain is Spring-runtime-free), but we need the
    // class on the test runtime classpath so MassingErrorCode's static
    // initializer succeeds. slf4j-api is already on the test runtime via
    // the bc-domain plugin's testImplementation chain (JUnit pulls slf4j).
    "testRuntimeOnly"("org.springframework:spring-web")
    "testRuntimeOnly"("org.slf4j:slf4j-api")
}
