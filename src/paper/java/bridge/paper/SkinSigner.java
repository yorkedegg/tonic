package bridge.paper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a 3DS built-in skin into something a Java client will draw. Vanilla clients only accept
 * textures SIGNED by Mojang, so each skin PNG is sent to MineSkin once, and the returned
 * {value, signature} pair is cached on disk per skin id — a few dozen built-in skins, signed once.
 *
 * <p>Layout under {@code plugins/TonicJava/skins/}: {@code index.tsv} (skinId, png path, variant —
 * written by tools/3dst2png.py from a RomFS dump of resourcepacks/skins/skinpacks), the PNGs, and
 * {@code cache/<id>.txt} (two lines: value, signature). MineSkin needs an API key
 * (account.mineskin.org/keys) and paces requests via {@code delayInfo}; that pacing is honoured.
 */
final class SkinSigner {
    private static final String ENDPOINT = "https://api.mineskin.org/generate/upload";
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MILLIS = Pattern.compile("\"millis\"\\s*:\\s*(\\d+)");

    private final Path dir;
    private final String apiKey, userAgent;
    private final Logger log;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Object mineskin = new Object();
    private long notBefore;

    SkinSigner(Path dir, String apiKey, String userAgent, Logger log) {
        this.dir = dir; this.apiKey = apiKey; this.userAgent = userAgent; this.log = log;
    }

    /** index.tsv row for a skin id: {png relative path, variant}; null if we have no texture. */
    String[] lookup(String skinId) {
        Path index = dir.resolve("index.tsv");
        if (skinId == null || !Files.isRegularFile(index)) return null;
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t");
                if (c.length >= 3 && c[0].trim().equals(skinId)) return new String[] {c[1].trim(), c[2].trim()};
            }
        } catch (Exception e) { log.warning("skins/index.tsv unreadable: " + e); }
        return null;
    }

    /** Signed {value, signature} for a skin id — cached, else signed now (blocks for MineSkin pacing). */
    String[] signed(String skinId) {
        if (skinId == null) return null;
        // Disk is the only cache, on purpose: deleting skins/cache/<id>.txt after fixing a PNG
        // makes the next join re-sign it, with no restart. One tiny file read per join is nothing.
        String safe = skinId.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path cached = dir.resolve("cache").resolve(safe + ".txt");
        try {
            if (Files.isRegularFile(cached)) {
                List<String> l = Files.readAllLines(cached, StandardCharsets.UTF_8);
                if (l.size() >= 2 && !l.get(0).isBlank()) {
                    return new String[] {l.get(0).trim(), l.get(1).trim()};
                }
            }
            String[] entry = lookup(skinId);
            if (entry == null) { log.info("no PNG indexed for 3DS skin '" + skinId + "'"); return null; }
            byte[] png = Files.readAllBytes(dir.resolve(entry[0]));
            String[] v = sign(png, entry[1], skinId);
            if (v != null) {
                Files.createDirectories(cached.getParent());
                Files.writeString(cached, v[0] + "\n" + v[1] + "\n", StandardCharsets.UTF_8);
            }
            return v;
        } catch (Exception e) {
            log.warning("signing 3DS skin '" + skinId + "' failed: " + e);
            return null;
        }
    }

    /** One MineSkin upload. Serialised and paced: MineSkin says how long to wait before the next. */
    private String[] sign(byte[] png, String variant, String skinId) throws Exception {
        synchronized (mineskin) {
            for (int attempt = 0; attempt < 2; attempt++) {
                long wait = notBefore - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(Math.min(wait, 60_000));
                String boundary = "----TonicJava" + Long.toHexString(System.nanoTime());
                byte[] body = multipart(boundary, png, variant, skinId);
                HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("User-Agent", userAgent)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                String text = res.body() == null ? "" : res.body();
                Matcher m = MILLIS.matcher(text);
                notBefore = System.currentTimeMillis() + (m.find() ? Long.parseLong(m.group(1)) : 10_000);
                if (res.statusCode() == 429) { log.info("MineSkin rate limit for '" + skinId + "', retrying"); continue; }
                Matcher v = VALUE.matcher(text), s = SIGNATURE.matcher(text);
                if (res.statusCode() / 100 == 2 && v.find() && s.find()) {
                    log.info("MineSkin signed 3DS skin '" + skinId + "' (" + variant + ")");
                    return new String[] {v.group(1), s.group(1)};
                }
                log.warning("MineSkin " + res.statusCode() + " for '" + skinId + "': "
                        + text.substring(0, Math.min(200, text.length())));
                return null;
            }
            return null;
        }
    }

    private static byte[] multipart(String boundary, byte[] png, String variant, String name) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        String nl = "\r\n";
        Map<String, String> fields = new HashMap<>();
        fields.put("variant", variant);
        fields.put("name", name.length() > 20 ? name.substring(0, 20) : name);
        fields.put("visibility", "1");
        for (Map.Entry<String, String> f : fields.entrySet()) {
            o.writeBytes(("--" + boundary + nl + "Content-Disposition: form-data; name=\"" + f.getKey() + "\""
                    + nl + nl + f.getValue() + nl).getBytes(StandardCharsets.UTF_8));
        }
        o.writeBytes(("--" + boundary + nl + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\""
                + nl + "Content-Type: image/png" + nl + nl).getBytes(StandardCharsets.UTF_8));
        o.writeBytes(png);
        o.writeBytes((nl + "--" + boundary + "--" + nl).getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }
}
