package comp3011;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Sends WAV chunks to the local A3 server using multipart/form-data.
 * This call BLOCKS: it must run on an I/O worker, NEVER on the JavaFX thread
 * or a FrameProcessor worker. The API key stays on the web server.
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class SubtitleHttpClient {
    private final HttpClient client;
    private final URI endpoint;

    public SubtitleHttpClient() {
        this(URI.create("http://localhost:8080/api/v1/subtitles"));
    }

    public SubtitleHttpClient(URI endpoint) {
        this.endpoint = endpoint;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String transcribe(Path wav) throws IOException, InterruptedException {
        String boundary = "COMP3011_" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(150))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofFile(wav),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            // Never log credentials or dump potentially sensitive response bodies.
            throw new IOException("Subtitle server returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
