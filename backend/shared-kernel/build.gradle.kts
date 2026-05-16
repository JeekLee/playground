plugins {
    id("playground.java-conventions")
}

// shared-kernel is a pure Java library — no Spring on the classpath by default,
// per ADR-01 v2 + ADR-03 (the event envelope is a record, not a Spring bean).
//
// M1+ adds: shared error hierarchy (ADR-11), shared DTOs.
