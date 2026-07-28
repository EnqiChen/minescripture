package dev.minescripture.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared Gson helpers. Gson is provided by Paper at runtime (zero shaded deps). */
public final class JsonUtil {

    public static final Gson GSON = new Gson();

    private JsonUtil() {
    }

    public static JsonObject parseObject(Reader reader) {
        return JsonParser.parseReader(reader).getAsJsonObject();
    }

    public static String str(JsonObject obj, String key, String def) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public static int integer(JsonObject obj, String key, int def) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    public static boolean bool(JsonObject obj, String key, boolean def) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    public static List<String> strList(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = obj.get(key);
        if (e != null && e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }

    public static Set<String> strSet(JsonObject obj, String key) {
        return new LinkedHashSet<>(strList(obj, key));
    }

    public static JsonArray array(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /**
     * Lenient extraction of the first balanced {...} block from LLM output —
     * survives markdown fences and chatter around the JSON. Returns null if no
     * complete object is found.
     */
    public static JsonObject firstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = inString;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return JsonParser.parseString(text.substring(start, i + 1)).getAsJsonObject();
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
