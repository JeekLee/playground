The playground project is a multi-service system built around domain-driven design.
Each bounded context owns its schema, its Kafka topics, and its lifecycle.

This fixture has no heading lines at all — the chunker should emit one root-section
chunk with an empty heading path. The text is short enough to fit in a single window,
so only one ChunkDraft should be produced.

A third paragraph here just to make sure multi-paragraph layout is handled without
accidentally treating paragraph boundaries as section boundaries.
