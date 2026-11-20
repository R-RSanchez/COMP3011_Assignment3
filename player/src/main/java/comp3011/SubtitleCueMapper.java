package comp3011;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Maps Whisper verbose_json segment/word timestamps to media-time cues.
 * Removes low-confidence speech and bounds each phrase to 1..7 seconds.
 * All returned values are immutable and suitable for SubtitleState.setCues().
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class SubtitleCueMapper {
    private SubtitleCueMapper() {
    }

    public static List<SubtitleCue> fromJson(String json, long chunkStartUs) {
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
        List<SubtitleCue> output = new ArrayList<>();
        JsonArray segments = array(payload, "segments");
        JsonArray words = array(payload, "words");

        if (segments != null) {
            for (JsonElement element : segments) {
                JsonObject segment = element.getAsJsonObject();
                if (number(segment, "no_speech_prob", 0) > 0.7
                        || number(segment, "avg_logprob", 0) < -1.2) {
                    continue;
                }

                double from = number(segment, "start", 0);
                double to = number(segment, "end", from);
                List<Word> selected = new ArrayList<>();
                if (words != null) {
                    for (JsonElement wordElement : words) {
                        JsonObject word = wordElement.getAsJsonObject();
                        double begin = number(word, "start", 0);
                        if (begin >= from - 0.05 && begin < to - 0.01) {
                            selected.add(new Word(begin,
                                    number(word, "end", begin),
                                    string(word, "word")));
                        }
                    }
                }

                if (!selected.isEmpty()) {
                    addWords(output, selected, chunkStartUs);
                } else {
                    addSegmentFallback(output,
                            string(segment, "text"), from, to, chunkStartUs);
                }
            }
        } else if (words != null) {
            List<Word> selected = new ArrayList<>();
            for (JsonElement element : words) {
                JsonObject word = element.getAsJsonObject();
                double begin = number(word, "start", 0);
                selected.add(new Word(begin,
                        number(word, "end", begin), string(word, "word")));
            }
            addWords(output, selected, chunkStartUs);
        }
        return List.copyOf(output);
    }

    private static void addWords(List<SubtitleCue> cues,
                                 List<Word> words, long offsetUs) {
        StringBuilder text = new StringBuilder();
        double start = -1;
        double end = -1;

        for (Word word : words) {
            String trimmed = word.text().strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (text.length() > 0 && (word.start() - end > 0.65
                    || word.end() - start > 6.5
                    || text.length() + 1 + trimmed.length() > 78)) {
                addCue(cues, text.toString(), start, end, offsetUs);
                text.setLength(0);
            }
            if (text.isEmpty()) {
                start = word.start();
            } else {
                text.append(' ');
            }
            text.append(trimmed);
            end = word.end();
        }
        if (!text.isEmpty()) {
            addCue(cues, text.toString(), start, end, offsetUs);
        }
    }

    private static void addSegmentFallback(List<SubtitleCue> cues,
                                            String text, double from,
                                            double to, long offsetUs) {
        String[] tokens = text.strip().split("\\s+");
        if (tokens.length == 0 || text.isBlank()) {
            return;
        }
        StringBuilder group = new StringBuilder();
        int firstWord = 0;
        for (int i = 0; i < tokens.length; i++) {
            if (!group.isEmpty() && group.length() + 1 + tokens[i].length() > 78) {
                double begin = from + (to - from) * firstWord / tokens.length;
                double end = from + (to - from) * i / tokens.length;
                addCue(cues, group.toString(), begin, end, offsetUs);
                group.setLength(0);
                firstWord = i;
            }
            if (!group.isEmpty()) {
                group.append(' ');
            }
            group.append(tokens[i]);
        }
        if (!group.isEmpty()) {
            double begin = from + (to - from) * firstWord / tokens.length;
            addCue(cues, group.toString(), begin, to, offsetUs);
        }
    }

    private static void addCue(List<SubtitleCue> cues, String text,
                                double from, double to, long offsetUs) {
        if (text.isBlank() || !Double.isFinite(from) || !Double.isFinite(to)) {
            return;
        }
        long start = Math.max(0, offsetUs + Math.round(from * 1_000_000));
        long end = Math.max(start + 1_000_000,
                Math.min(start + 7_000_000,
                        offsetUs + Math.round(to * 1_000_000)));
        cues.add(new SubtitleCue(start, end, text));
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonArray() ? null : value.getAsJsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static double number(JsonObject object, String name, double otherwise) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? otherwise : value.getAsDouble();
    }

    private record Word(double start, double end, String text) {
    }
}
