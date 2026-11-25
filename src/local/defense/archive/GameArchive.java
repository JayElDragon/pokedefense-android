package local.defense.archive;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class GameArchive implements Closeable {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final long MAX_ARCHIVE = 256L * 1024 * 1024;
    private static final int MAX_TEXT = 8 * 1024 * 1024;
    private static final String[] REQUIRED = {"index.html", "src/game.js", "src/maps.js", "ui/ui2.js", "style.css"};
    private static final String[] LOCAL_ASSET_HOSTS = {
        "fonts.gstatic.com", "play.pokemonshowdown.com", "raw.githubusercontent.com"
    };
    private static final String FONT_DIR = "/_ext/fonts.googleapis.com/css2/";

    private final ZipFile zip;
    private final String root;

    GameArchive(File file) throws IOException {
        ZipFile candidate = new ZipFile(file);
        try {
            root = findGameRoot(candidate);
            zip = candidate;
        } catch (IOException e) {
            candidate.close();
            throw e;
        }
    }

    static void install(InputStream source, File destination) throws IOException {
        File directory = destination.getParentFile();
        File incoming = File.createTempFile("game-incoming-", ".zip", directory);
        File nested = null;
        try {
            copy(source, incoming);
            File candidate = incoming;
            try (ZipFile outer = new ZipFile(incoming)) {
                ZipEntry entry = outer.getEntry("pokedefense.zip");
                if (entry != null && !entry.isDirectory()) {
                    nested = File.createTempFile("game-inner-", ".zip", directory);
                    try (InputStream stream = outer.getInputStream(entry)) {
                        copy(stream, nested);
                    }
                    candidate = nested;
                }
            }
            try (GameArchive checked = new GameArchive(candidate)) {
                checked.verifyMainPage();
            }
            Files.move(candidate.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            incoming.delete();
            if (nested != null) nested.delete();
        }
    }

    private static void copy(InputStream input, File output) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_ARCHIVE) throw new IOException("Archive too large");
                stream.write(buffer, 0, count);
            }
            stream.getFD().sync();
        }
    }

    private static String findGameRoot(ZipFile zip) throws IOException {
        if (zip.size() > 20_000) throw new IOException("Too many ZIP entries");
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.equals("index.html") && !name.endsWith("/index.html")) continue;
            String root = name.substring(0, name.length() - "index.html".length());
            if (root.startsWith("__MACOSX/")) continue;
            boolean valid = true;
            for (String required : REQUIRED) {
                ZipEntry entry = zip.getEntry(root + required);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > MAX_TEXT) {
                    valid = false;
                    break;
                }
            }
            if (valid) return root;
        }
        throw new IOException("PokéDefense site not found");
    }

    private void verifyMainPage() throws IOException {
        try (InputStream input = zip.getInputStream(zip.getEntry(root + "index.html"))) {
            String html = readText(input);
            if (!html.contains("pokedefense.save.v1") || !html.contains("src/game.js")) {
                throw new IOException("Unrecognized PokéDefense page");
            }
        }
    }

    static boolean isLocal(Uri uri) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
            && uri.getPort() == -1 && uri.getUserInfo() == null;
    }

    WebResourceResponse respond(Uri uri, String method) {
        if (!isLocal(uri)) return status(403, "Forbidden", "text/plain", "");
        String path = uri.getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        if (path.equals("/api") || path.startsWith("/api/")) {
            return status(404, "Not Found", "application/json", "{}");
        }
        if (!"GET".equals(method) || !path.startsWith("/") || path.contains("\\") || path.indexOf(0) >= 0) {
            return status(404, "Not Found", "text/plain", "");
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return status(404, "Not Found", "text/plain", "");
        }
        try {
            ZipEntry entry = zip.getEntry(root + path.substring(1));
            if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ARCHIVE) {
                return status(404, "Not Found", "text/plain", "");
            }
            String mime = mime(path);
            if (mime.equals("text/javascript") || mime.equals("text/css") || mime.equals("text/html")) {
                if (entry.getSize() > MAX_TEXT) return status(413, "Content Too Large", "text/plain", "");
                try (InputStream input = zip.getInputStream(entry)) {
                    String source = readText(input);
                    if (path.equals("/ui/gba-art.js")) {
                        // This offscreen canvas reads pixels back repeatedly as it paints each map.
                        source = source.replace("art.getContext('2d')",
                            "art.getContext('2d', { willReadFrequently: true })");
                    }
                    return status(200, "OK", mime, localizeAssets(adaptPerformance(path, source)));
                }
            }
            return new WebResourceResponse(mime, null, 200, "OK", Collections.emptyMap(), zip.getInputStream(entry));
        } catch (IOException | IllegalStateException e) {
            return status(404, "Not Found", "text/plain", "");
        }
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_TEXT) throw new IOException("Web resource too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String adaptPerformance(String path, String source) {
        switch (path) {
            case "/index.html":
                return source.replace("if (set.reduceMotion)",
                    "document.body.classList.toggle('pd-low-spec', set.lowSpec !== false);\n"
                        + "      if (set.reduceMotion || set.lowSpec !== false)");
            case "/src/game.js":
                String loadedSave = "  SAVE.story = Object.assign";
                String cacheKey = "map.id + (map.portrait ? ':p' : '')";
                return source.replace(loadedSave,
                        "  if (SAVE.settings?.lowSpec == null) SAVE.settings = { ...SAVE.settings, lowSpec: true };\n"
                            + loadedSave)
                    .replace(cacheKey, "mapArtCacheKey(map)")
                    .replace("const artCache = {};",
                        "const artCache = {};\n  const mapArtCacheKey = map => " + cacheKey
                            + " + (document.body.classList.contains('pd-low-spec') ? ':low' : ':high');")
                    .replace("    if (!menuBg.map) menuBgMap();",
                        "    if (fxLow()) {\n"
                            + "      if (menuBg.profile !== 'low') {\n"
                            + "        menuBgMap();\n"
                            + "        menuCtx.drawImage(menuBg.art.canvas, 0, 0);\n"
                            + "        menuBg.profile = 'low';\n"
                            + "      }\n"
                            + "      return;\n"
                            + "    }\n"
                            + "    if (menuBg.profile !== 'high') { menuBgMap(); menuBg.profile = 'high'; }");
            case "/ui/gba-art.js":
                // The original renderer is cheaper and supplies the same layout and collision data.
                return source.replace("MapArt.paint = map => {",
                    "MapArt.paint = map => {\n"
                        + "    if (document.body.classList.contains('pd-low-spec')) return paint(map);");
            case "/ui/ui2.js":
                return source.replace("const key = `${id}:${w}`;",
                    "const key = `${id}:${w}:${document.body.classList.contains('pd-low-spec')}`;");
            case "/ui/settings.js":
                return addPerformanceSelector(source);
            default:
                return source;
        }
    }

    private static String addPerformanceSelector(String source) {
        String general = "$('set-general').innerHTML = " + (char) 96;
        String profile = "\n      <div class=\"set-group\"><h4>Rendimiento</h4>"
            + "<div class=\"set-row\"><span><b>Perfil gráfico</b>"
            + "<small>Bajo simplifica los mapas y reduce efectos. Alto recupera el detalle visual.</small></span>"
            + "${seg('performanceProfile', s.lowSpec ? 'low' : 'high', [['low', 'Bajo'], ['high', 'Alto']])}"
            + "</div></div>";
        return source.replace(general, general + profile)
            .replaceAll("(?m)^[ \\t]*[$]\\{toggle\\('lowSpec',[^\\r\\n]*\\}\\r?\\n", "")
            .replace("S()[g.dataset.set] = b.dataset.v; P.saveSettings();",
                "if (g.dataset.set === 'performanceProfile') S().lowSpec = b.dataset.v === 'low';\n"
                    + "      else S()[g.dataset.set] = b.dataset.v;\n      P.saveSettings();")
            .replace("!!s.reduceMotion);",
                "!!(s.reduceMotion || s.lowSpec));\n    document.body.classList.toggle('pd-low-spec', !!s.lowSpec);")
            .replace("${s.reduceMotion}|", "${s.reduceMotion}|${s.lowSpec}|");
    }

    private static String localizeAssets(String source) {
        source = source.replace("https://fonts.googleapis.com/css2?family=Press+Start+2P&display=swap",
            FONT_DIR + "index.html%4030c60e95730d.css");
        source = source.replace("https://fonts.googleapis.com/css2?family=Nunito:wght@600;700;800;900&display=swap",
            FONT_DIR + "index.html%40ff2130ce7912.css");
        for (String host : LOCAL_ASSET_HOSTS) {
            source = source.replace("https://" + host + "/", "/_ext/" + host + "/");
        }
        return source;
    }

    private static WebResourceResponse status(int code, String reason, String mime, String body) {
        return new WebResourceResponse(mime, "utf-8", code, reason, Collections.emptyMap(),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String mime(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "html": return "text/html";
            case "css": return "text/css";
            case "js": return "text/javascript";
            case "json": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "woff2": return "font/woff2";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
