# Peep

- JDK 25, named JPMS modules, supported JDK APIs only; no preview features.
- Build with `javac @cmd/compile`; test with `java @cmd/test`; smoke example: `java @cmd/run`.
- No Maven/Gradle, class path, generated sources or runtime reflection in the provider.
- Link sibling service-catalog and Minau module sources under lib/src. See README for pinned revisions.
- Export/open no provider packages. Consumer configuration must be part of a catalog contract.
- Test through catalog contracts with Minau, assertions enabled. Keep HTTP integration in tests.
- Preserve published logging.v01 semantics; no nested/inherited goals or metrics/tracing in this release.
- Do not commit out/ or downloaded dependencies.
