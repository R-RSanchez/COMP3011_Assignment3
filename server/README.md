# COMP3011 Assignment 1 - Speech to Text Web Application

## Overview

This project is a Java Spring Boot web application that records audio from a user's microphone, uploads the recording to a Java backend, sends the audio to the OpenAI Speech-to-Text API, and displays the transcription in the browser.

The application also provides administration and global statistics REST endpoints for:

- server uptime
- global input/output token usage
- graceful server shutdown

The application has been designed to support concurrent requests safely and efficiently.

---

## Technologies

- Java 25
- Spring Boot 4
- Maven
- HTML
- CSS
- JavaScript
- OpenAI Speech-to-Text API
- JUnit 5
- Mockito

---

## Project Structure

```text
src/main/java/comp3011/assignment1/
├── controller/
├── dto/
├── exception/
└── service/

src/main/resources/
├── static/
│   ├── index.html
│   ├── script.js
│   └── style.css
└── application.properties

src/test/java/comp3011/assignment1/
├── controller/
└── service/
```

---

## Application Architecture

The backend follows a simple separation of responsibilities.

### Controllers

Controllers expose the HTTP endpoints and delegate application logic to services.

Main controllers:

- `TranscriptionController`
- `AdminController`
- `GlobalStatsController`

Constructor dependency injection is used throughout the application.

### Services

#### TranscriptionService

Responsible for:

- receiving the uploaded audio from the controller
- sending the audio to the OpenAI transcription API
- returning the resulting transcription
- recording API token usage

#### GlobalStatsService

Stores cumulative input and output token usage since the server started.

Shared mutable state is protected using `synchronized` methods to avoid race conditions between concurrent requests.

#### ServerLifecycleService

Stores:

- the UTC server start time
- whether graceful shutdown has already started

The shutdown state is also protected using synchronization.

---

## Speech-to-Text Flow

```text
Browser microphone
        |
        v
MediaRecorder
        |
        v
WebM audio Blob
        |
        v
POST /api/transcribe
        |
        v
TranscriptionController
        |
        v
TranscriptionService
        |
        v
OpenAI Speech-to-Text API
        |
        v
Transcription returned to browser
```

The OpenAI model used is:

```text
gpt-4o-mini-transcribe
```

---

## API Key Security

The OpenAI API key is never hardcoded in the source code.

The application reads the key dynamically from the environment variable:

```text
OPENAI_API_KEY
```

The key is not:

- included in Java source code
- included in JavaScript
- returned to browser clients
- written to logs
- committed to Git

TITAN provides the API key dynamically at runtime.

---

## REST API

### Audio Transcription

```text
POST /api/transcribe
```

Accepts a multipart audio file using the field:

```text
file
```

The backend sends the audio to the external STT service and returns the transcription as text.

### Server Uptime

```text
GET /api/v1/admin/uptime
```

Example response:

```json
{
  "utcServerStart": "2026-09-01T00:00:00Z",
  "utcNow": "2026-09-01T00:01:30Z",
  "serverUptimeSeconds": 90.0
}
```

### Global Statistics

```text
GET /api/v1/global/stats
```

Example response:

```json
{
  "inputTokens": 123,
  "outputTokens": 45
}
```

The counters are kept in process memory and reset when the Java process restarts.

### Graceful Shutdown

```text
POST /api/v1/admin/shutdown
```

Successful response:

```text
HTTP 202 Accepted
```

```json
{
  "message": "Graceful shutdown requested."
}
```

If shutdown has already started, the endpoint returns:

```text
HTTP 409 Conflict
```

The shutdown operation is performed separately from the request thread so that the HTTP response can be returned before the Spring application context closes.

---

## Concurrency

The application enables Java virtual threads:

```properties
spring.threads.virtual.enabled=true
spring.main.keep-alive=true
```

This is useful because calls to the external Speech-to-Text service perform blocking network I/O.

Virtual threads allow many requests to wait on external network calls without requiring a large number of traditional platform threads.

The external STT call itself is deliberately **not synchronized**, because doing so would force transcription requests to execute one at a time.

---

## Race Condition Protection

Global token counters are shared between requests.

Updates are protected with:

```java
public synchronized void addUsage(long input, long output)
```

Reads are also synchronized.

The synchronized sections are intentionally small and only protect shared state.

This avoids lost updates while still allowing multiple transcription requests to run concurrently.

The graceful shutdown state is protected using the same approach so that only one simultaneous shutdown request can be accepted.

---

## Error Handling

The application includes a global exception handler that returns consistent JSON responses for unexpected server errors.

Example:

```json
{
  "timestamp": "2026-09-01T00:00:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected server error occurred.",
  "path": "/api/example"
}
```

The frontend also handles errors including:

- microphone permission failure
- empty audio recordings
- failed HTTP requests
- unsuccessful server responses

The user interface displays application states such as:

```text
Ready
Requesting microphone...
Recording...
Transcribing...
Error
```

---

## Testing

The application contains functional, regression and concurrency tests.

### Controller Regression Tests

The following controllers are tested independently:

- `TranscriptionControllerTest`
- `AdminControllerTest`
- `GlobalStatsControllerTest`

The transcription controller test replaces the real STT service with a mocked service, so the test does not require an API key or make external network requests.

### Race Condition Test

`GlobalStatsServiceTest` executes updates to the token counters from multiple threads.

Each thread repeatedly updates the same shared counters.

The test verifies that the final totals are correct and no updates have been lost.

### Concurrent HTTP Request Test

`ConcurrentRequestTest` sends:

```text
250 simultaneous blocking HTTP requests
```

through the Spring application.

The STT service is mocked to behave like a blocking Cloud request.

The test verifies that:

- more than 200 simultaneous requests are handled
- all responses succeed
- the server does not crash
- requests are not significantly serialized

During local testing, 250 concurrent blocking requests completed in approximately 1-2 seconds.

Timing depends on the machine and runtime environment.

---

## Running Tests

Run the complete test suite with:

```bash
./mvnw test
```

A successful test run should finish with:

```text
Failures: 0
Errors: 0
BUILD SUCCESS
```

---

## Building the Application

Build the executable Spring Boot JAR with:

```bash
./mvnw clean package
```

The executable JAR is generated under:

```text
target/
```

For example:

```text
target/assignment1-0.0.1-SNAPSHOT.jar
```

Run the JAR with:

```bash
java -jar target/assignment1-0.0.1-SNAPSHOT.jar
```

The web application is then available at:

```text
http://localhost:8080
```

---

## TITAN Testing

The executable JAR was tested using the COMP3011 TITAN environment.

TITAN successfully verified all 11 functional features, including:

- server uptime
- initial global statistics
- web page availability
- start recording control
- recording state feedback
- stop recording control
- stopped state feedback
- correct speech transcription
- transcription within the required time
- updated global statistics
- graceful server shutdown

TITAN reported:

```text
11/11 features have been successfully implemented.
```

---

## Design Decisions

### Constructor Dependency Injection

Dependencies are supplied through constructors rather than instantiated directly.

This keeps classes loosely coupled and makes services easy to replace with mocks during testing.

### Stateless Controllers

Controllers do not store mutable request-specific state.

This reduces the risk of race conditions between simultaneous HTTP requests.

### Virtual Threads

Virtual threads were chosen because the application performs blocking network calls and needs to support a high number of overlapping requests.

### Minimal Synchronization

Only genuinely shared mutable state is synchronized.

The Cloud API call is intentionally not synchronized, as this would serialize requests and reduce performance.

---

## Known Limitations

- Local transcription requires a valid `OPENAI_API_KEY`.
- Token statistics exist only for the lifetime of the current Java process.
- The administrative shutdown endpoint does not use authentication because authentication is outside the Assignment 1 specification.

---

## External Resources and Generative AI

External documentation and generative AI tools were used during development for research, debugging, design discussion, testing ideas, and understanding Spring Boot APIs.

Resources consulted included:

- COMP3011 course material
- Assignment 1 specification
- Assignment 1 OpenAPI YAML
- Spring Boot documentation
- Java documentation
- OpenAI API documentation
- ChatGPT

All externally suggested code and design ideas were reviewed, tested and adapted as part of the implementation.

The submitted implementation has been reviewed so that its architecture, concurrency behaviour, REST API design, security approach and tests can be explained and defended.