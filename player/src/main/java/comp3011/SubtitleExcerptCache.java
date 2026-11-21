package comp3011;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.sound.sampled.AudioSystem;
import com.google.gson.*;

/**
 * Conservative audio-content matching across cached chunk boundaries.
 * Stores original mono PCM alongside successful transcripts. Matching uses
 * a 20 ms RMS envelope sampled at 1 kHz, then verifies the entire overlap, never filenames or text.
 * Author: Raúl Ruiz Sánchez. Developed with assistance from ChatGPT.
 */
final class SubtitleExcerptCache {
    private static final int RATE = 1000;
    private final Path directory;

    SubtitleExcerptCache(Path directory) { this.directory = directory; }

    void remember(Path wav, String digest) throws IOException {
        Path target = directory.resolve(digest + ".wav");
        if (Files.exists(target)) return;
        Path temporary = Files.createTempFile(directory, "audio-", ".tmp");
        try {
            Files.copy(wav, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    String find(Path wav) throws IOException, InterruptedException {
        double[] query = samples(wav);
        if (query == null || query.length < 500 || !Files.isDirectory(directory)) return null;
        List<Match> matches = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path audio : files.filter(p -> p.toString().endsWith(".wav")).toList()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String name = audio.getFileName().toString();
                Path json = directory.resolve(name.substring(0, name.length() - 4) + ".json");
                if (!Files.isRegularFile(json)) continue;
                try {
                    double[] reference = samples(audio);
                    if (reference == null) continue;
                    Match match = match(query, reference, Files.readString(json));
                    if (match != null) matches.add(match);
                } catch (IOException | RuntimeException corrupt) {
                    // A damaged cache entry is a miss, never a playback error.
                }
            }
        }
        matches.sort(Comparator.comparingInt(Match::from));
        List<Match> selected = new ArrayList<>();
        int covered = 0;
        while (covered < query.length - 30) {
            Match next = null;
            for (Match m : matches) {
                if (m.from <= covered + 30 && m.to > covered
                        && (next == null || m.to > next.to)) next = m;
            }
            if (next == null) return null;
            selected.add(next);
            covered = next.to;
        }
        if (covered < query.length - 30) return null;
        JsonArray segments = new JsonArray();
        JsonArray words = new JsonArray();
        for (Match m : selected) {
            JsonObject source = JsonParser.parseString(m.json).getAsJsonObject();
            shift(source, "segments", segments, m.offset / (double) RATE, query.length / (double) RATE);
            shift(source, "words", words, m.offset / (double) RATE, query.length / (double) RATE);
        }
        JsonObject result = new JsonObject();
        result.add("segments", segments);
        result.add("words", words);
        result.addProperty("duration", query.length / (double) RATE);
        return result.toString();
    }

    private static void shift(JsonObject source, String key, JsonArray target,
                              double offset, double duration) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        for (JsonElement item : source.getAsJsonArray(key)) {
            JsonObject value = item.getAsJsonObject().deepCopy();
            double start = value.get("start").getAsDouble() - offset;
            double end = value.get("end").getAsDouble() - offset;
            if (end <= 0 || start >= duration) continue;
            value.addProperty("start", Math.max(0, start));
            value.addProperty("end", Math.min(duration, end));
            if (!target.contains(value)) target.add(value);
        }
    }

    // offset maps query index q to reference index q + offset.
    private static Match match(double[] query, double[] reference, String json) {
        int window = Math.min(700, Math.min(query.length, reference.length));
        if (window < 500) return null;
        double best = 0.97;
        int bestOffset = Integer.MIN_VALUE;
        for (int anchor = 0; anchor + window <= query.length; anchor += 250) {
            for (int r = 0; r + window <= reference.length; r += 4) {
                double score = correlation(query, anchor, reference, r, window, 4);
                if (score < 0.88) continue;
                for (int fine = Math.max(0, r - 3); fine <= Math.min(reference.length - window, r + 3); fine++) {
                    int offset = fine - anchor;
                    int from = Math.max(0, -offset);
                    int to = Math.min(query.length, reference.length - offset);
                    int edge = 40;
                    if (to - from < 500 + 2 * edge) continue;
                    double verified = correlation(query, from + edge, reference, from + offset + edge, to - from - 2 * edge, 1);
                    if (verified > best) { best = verified; bestOffset = offset; }
                }
            }
        }
        if (bestOffset == Integer.MIN_VALUE) return null;
        return new Match(Math.max(0, -bestOffset),
                Math.min(query.length, reference.length - bestOffset), bestOffset, json);
    }

    private static double correlation(double[] a, int ai, double[] b, int bi, int n, int stride) {
        double ab = 0, aa = 0, bb = 0, ax = 0, bx = 0;
        int count = 0;
        for (int i = 0; i < n; i += stride) {
            double x = a[ai + i], y = b[bi + i];
            ab += x * y; aa += x * x; bb += y * y; ax += x; bx += y; count++;
        }
        // Silence carries no identifying information.
        if (aa < count * 400 || bb < count * 400) return 0;
        ab -= ax * bx / count;
        aa -= ax * ax / count;
        bb -= bx * bx / count;
        if (aa <= count || bb <= count) return 0;
        return ab / Math.sqrt(aa * bb);
    }

    private static double[] samples(Path wav) throws IOException {
        try (var input = AudioSystem.getAudioInputStream(wav.toFile())) {
            var format = input.getFormat();
            if (format.getSampleRate() != 16000 || format.getChannels() != 1
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian()) return null;
            byte[] bytes = input.readAllBytes();
            int actual = bytes.length;
            while (actual >= 2 && bytes[actual-1] == 0 && bytes[actual-2] == 0) actual -= 2;
            bytes = Arrays.copyOf(bytes, actual);
            double[] result = new double[bytes.length / 32];
            double[] energy = new double[bytes.length / 2 + 1];
            for (int i = 0; i < bytes.length / 2; i++) {
                double sample = (short) ((bytes[2*i] & 255) | (bytes[2*i+1] << 8));
                energy[i+1] = energy[i] + sample * sample;
            }
            for (int i = 0; i < result.length; i++) {
                int from = Math.max(0, i * 16 - 160);
                int to = Math.min(energy.length - 1, i * 16 + 160);
                result[i] = Math.sqrt((energy[to] - energy[from]) / (to - from));
            }
            return result;
        } catch (javax.sound.sampled.UnsupportedAudioFileException unsupported) { return null; }
    }

    private record Match(int from, int to, int offset, String json) {}
}
