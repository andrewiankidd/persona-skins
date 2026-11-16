package io.github.andrewiankidd.personaskins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Turns a skin image into a texture Java clients accept. A Java client shows
 * another player's skin only from a profile property signed by Mojang, so the
 * image has to be worn by a real account once: MineSkin (mineskin.org) does
 * that and returns the signed property. Generation is serialised and spaced
 * out, since without an API key MineSkin allows only a few a minute.
 */
public final class MineSkin {
    private static final URI GENERATE = URI.create("https://api.mineskin.org/v2/generate");

    public record Signed(String value, String signature) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String visibility;
    private final String userAgent;
    private long nextAllowed;

    public MineSkin(String apiKey, String visibility, String version) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.visibility = visibility;
        this.userAgent = "PersonaSkins/" + version;
    }

    /** Uploads a 64x64 PNG; blocks until MineSkin has signed it. */
    public synchronized Signed generate(byte[] png, boolean slim, String name) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            long wait = nextAllowed - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            String boundary = "----persona" + UUID.randomUUID().toString().replace("-", "");
            HttpRequest.Builder req = HttpRequest.newBuilder(GENERATE)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(form(boundary, png, slim, name)));
            if (!apiKey.isEmpty()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject body = parse(res.body());
            if (res.statusCode() == 429 && attempt < 5) {
                nextAllowed = System.currentTimeMillis() + retryAfter(res, body);
                continue;
            }
            nextAllowed = System.currentTimeMillis() + nextDelay(body);
            if (res.statusCode() / 100 != 2 || body == null || !body.has("skin")) {
                throw new IOException("MineSkin answered " + res.statusCode() + ": " + abbreviate(res.body()));
            }
            JsonObject data = body.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
            return new Signed(data.get("value").getAsString(), data.get("signature").getAsString());
        }
    }

    private byte[] form(String boundary, byte[] png, boolean slim, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "variant", slim ? "slim" : "classic");
        field(out, boundary, "visibility", visibility);
        field(out, boundary, "name", name);
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject parse(String s) {
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Milliseconds MineSkin asks for before the next request, from rateLimit.next. */
    private static long nextDelay(JsonObject body) {
        try {
            JsonObject next = body.getAsJsonObject("rateLimit").getAsJsonObject("next");
            return Math.max(0, next.get("relative").getAsLong());
        } catch (RuntimeException e) {
            return 6000;
        }
    }

    private static long retryAfter(HttpResponse<String> res, JsonObject body) {
        return res.headers().firstValue("Retry-After")
                .map(s -> {
                    try {
                        return Long.parseLong(s.trim()) * 1000;
                    } catch (NumberFormatException e) {
                        return 10_000L;
                    }
                })
                .orElseGet(() -> body == null ? 10_000L : Math.max(nextDelay(body), 2000));
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
