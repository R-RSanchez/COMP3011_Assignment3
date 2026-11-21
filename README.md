# COMP3011 Assignment 3 — Cloud subtitles video player

Extends the concurrent JavaFX player from Assignment 2 with timed Whisper subtitles through the Spring Boot server from Assignment 1.

## Build and run

Requires JDK 25 and Maven. From this repository root:

```
mvn -f player/pom.xml verify
mvn -f server/pom.xml verify
mvn -f server/pom.xml spring-boot:run
mvn -f player/pom.xml javafx:run "-Djavafx.args=-Sn video.mp4"
```

Set OPENAI_API_KEY in the server process environment before starting it. Never put credentials in source control. The player uses localhost:8080/api/v1/subtitles; cumulative successful transcription seconds are returned by GET /api/v1/global/usage. Toggle captions with the CC button or S key.

## Design

The JavaFX thread owns playback state. Ordered effect workers process independent native frame clones; the model releases a clone after conversion to JavaFX. Subtitle extraction and two bounded HTTP workers run separately. Immutable cue snapshots and generation checks prevent cancelled media responses from replacing current captions.

Audio is decoded to counted mono PCM16 samples at 16 kHz. Stable chunk boundaries preserve exact cache keys across repeats and toggles. The persistent client cache stores timed JSON and audio; excerpt matching verifies an RMS audio envelope across cached chunk boundaries and rebases segment/word times. Unmatched audio falls back to transcription. This is conservative content matching, not universal audio recognition; disk use grows with cached audio.

## Validation

TITAN run fb52c4e8-8874-45bf-8e1d-f85349e297e8 on 21 November 2026: 16/16 features, final frame 3288, usage unchanged at 137.25 seconds for repeats, renamed media and excerpt.

Player verification: 42 passing tests, plus one optional real-media test. Server: 11 passing tests. To include the optional test, supply -Dcomp3011.test.media=path/to/original.mp4 and -Dcomp3011.test.clip=path/to/excerpt.m4a when running Maven verify for player. Real PCM and AAC excerpts were tested locally. TITAN does not establish complete viva readiness; network loss, removable media and live presentation should also be rehearsed.

The TITAN ZIP has player pom.xml and src/ plus the freshly built Spring Boot JAR named server.jar at its root. Build output, local media, credentials and ZIPs are excluded from Git.

## Attribution

Author: Raúl Ruiz Sánchez. Adapted from the supplied COMP3011 starter code and earlier assignments. Development, debugging and testing were assisted by ChatGPT/Codex; contributions are acknowledged in source headers. The author must be able to explain and defend the implementation.
