package ua.bogven.playlistbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Пошук музики лише в легальних безкоштовних джерелах:
 *  - Jamendo (Creative Commons, офіційний API з дозволеним завантаженням)
 *  - Internet Archive (колекції netlabels / opensource_audio, лише CC та public domain)
 */
public class MusicSources {

    public interface Progress {
        void onProgress(String message);
    }

    static final String MODERN_FROM = "2018-01-01";

    // ---------------------------------------------------------------- HTTP

    /** Відкриває з'єднання, вручну проходячи переадресації (у т.ч. http <-> https). */
    public static HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "PlaylistBot/1.0 (Android)");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Переадресація без адреси");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("Сервер відповів кодом " + code);
            }
            return c;
        }
        throw new IOException("Забагато переадресацій");
    }

    public static String httpGet(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Кодує ім'я файлу для шляху URL (пробіли як %20, слеші зберігаються). */
    static String encPath(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(enc(parts[i]).replace("+", "%20"));
        }
        return sb.toString();
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ---------------------------------------------------------------- Jamendo

    /**
     * @param lang     ISO 639-1 код мови ("uk", "en"...), "instrumental" або "" — будь-яка
     * @param newest   true — найновіші, false — популярні цього місяця
     */
    public static List<Track> jamendo(String clientId, String genreTag, String lang,
                                      boolean newest, boolean modernOnly, int limit) throws Exception {
        StringBuilder u = new StringBuilder("https://api.jamendo.com/v3.0/tracks/?format=json");
        u.append("&client_id=").append(enc(clientId));
        u.append("&limit=").append(Math.max(1, Math.min(limit, 200)));
        u.append("&audioformat=mp32&audiodlformat=mp32");
        u.append("&order=").append(newest ? "releasedate_desc" : "popularity_month");
        if (!genreTag.isEmpty()) u.append("&fuzzytags=").append(enc(genreTag));
        if ("instrumental".equals(lang)) {
            u.append("&vocalinstrumental=instrumental");
        } else if (!lang.isEmpty()) {
            u.append("&vocalinstrumental=vocal&lang=").append(enc(lang));
        }
        if (modernOnly) u.append("&datebetween=").append(MODERN_FROM).append('_').append(today());

        JSONObject root = new JSONObject(httpGet(u.toString()));
        JSONObject headers = root.optJSONObject("headers");
        if (headers != null && !"success".equals(headers.optString("status"))) {
            String msg = headers.optString("error_message", "помилка API");
            throw new Exception(msg.isEmpty() ? "перевірте ключ Jamendo (Client ID)" : msg);
        }
        List<Track> out = new ArrayList<>();
        JSONArray arr = root.optJSONArray("results");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!o.optBoolean("audiodownload_allowed", true)) continue;
            String dl = o.optString("audiodownload", "");
            if (dl.isEmpty()) continue;
            Track t = new Track();
            t.title = o.optString("name", "Без назви");
            t.artist = o.optString("artist_name", "Невідомий виконавець");
            t.downloadUrl = dl;
            t.streamUrl = o.optString("audio", dl);
            t.license = o.optString("license_ccurl", "Creative Commons");
            t.pageUrl = o.optString("shareurl", "");
            t.source = "Jamendo";
            out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- Internet Archive

    static String iaSubject(String genreTag) {
        switch (genreTag) {
            case "hiphop":     return "(\"hip hop\" OR hiphop OR \"hip-hop\" OR rap)";
            case "electronic": return "(electronic OR electronica OR techno OR house)";
            case "chillout":   return "(chillout OR \"chill out\" OR downtempo)";
            default:           return "(" + genreTag + ")";
        }
    }

    /**
     * @param langQuery вираз для поля language, напр. "(ukr OR Ukrainian)", або ""
     * @param needed    скільки треків потрібно
     */
    public static List<Track> internetArchive(String genreTag, String langQuery, boolean newest,
                                              boolean modernOnly, int needed, boolean shuffle,
                                              Progress progress) throws Exception {
        StringBuilder q = new StringBuilder("mediatype:(audio) AND collection:(netlabels OR opensource_audio)");
        if (!genreTag.isEmpty()) q.append(" AND subject:").append(iaSubject(genreTag));
        if (!langQuery.isEmpty()) q.append(" AND language:").append(langQuery);
        if (modernOnly) q.append(" AND publicdate:[").append(MODERN_FROM).append(" TO ").append(today()).append("]");

        int rows = Math.min(Math.max(needed * 3, 30), 300);
        String url = "https://archive.org/advancedsearch.php?q=" + enc(q.toString())
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl"
                + "&rows=" + rows + "&page=1"
                + "&sort%5B%5D=" + enc(newest ? "publicdate desc" : "downloads desc")
                + "&output=json";

        JSONObject root = new JSONObject(httpGet(url));
        JSONObject resp = root.optJSONObject("response");
        JSONArray docs = resp == null ? null : resp.optJSONArray("docs");
        List<Track> out = new ArrayList<>();
        if (docs == null) return out;

        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.optJSONObject(i);
            if (d == null) continue;
            String lic = d.optString("licenseurl", "").toLowerCase();
            // Лише вільні ліцензії
            if (!lic.contains("creativecommons") && !lic.contains("publicdomain")) continue;
            items.add(d);
        }
        if (shuffle) Collections.shuffle(items);

        int checked = 0;
        for (JSONObject d : items) {
            if (out.size() >= needed) break;
            checked++;
            if (progress != null) {
                progress.onProgress("Internet Archive: перевіряю альбом " + checked + " (знайдено " + out.size() + "/" + needed + ")");
            }
            String id = d.optString("identifier", "");
            if (id.isEmpty()) continue;
            String creator = firstString(d.opt("creator"));
            try {
                JSONObject meta = new JSONObject(httpGet("https://archive.org/metadata/" + encPath(id)));
                JSONArray files = meta.optJSONArray("files");
                if (files == null) continue;
                List<JSONObject> mp3s = new ArrayList<>();
                for (int k = 0; k < files.length(); k++) {
                    JSONObject f = files.optJSONObject(k);
                    if (f == null) continue;
                    String name = f.optString("name", "");
                    String fmt = f.optString("format", "");
                    if (name.toLowerCase(Locale.ROOT).endsWith(".mp3") && fmt.toUpperCase(Locale.ROOT).contains("MP3")) {
                        mp3s.add(f);
                    }
                }
                if (mp3s.isEmpty()) continue;
                // По 1-2 треки з альбому — для різноманіття
                if (shuffle) Collections.shuffle(mp3s);
                int take = Math.min(2, mp3s.size());
                for (int k = 0; k < take && out.size() < needed; k++) {
                    JSONObject f = mp3s.get(k);
                    String name = f.optString("name");
                    Track t = new Track();
                    String ft = f.optString("title", "");
                    t.title = ft.isEmpty() ? stripExt(name.substring(name.lastIndexOf('/') + 1)) : ft;
                    String fa = f.optString("artist", "");
                    t.artist = !fa.isEmpty() ? fa : (!creator.isEmpty() ? creator : "Невідомий виконавець");
                    t.downloadUrl = "https://archive.org/download/" + encPath(id) + "/" + encPath(name);
                    t.streamUrl = t.downloadUrl;
                    t.license = d.optString("licenseurl", "");
                    t.pageUrl = "https://archive.org/details/" + id;
                    t.source = "Internet Archive";
                    out.add(t);
                }
            } catch (Exception ignored) {
                // пропускаємо проблемний альбом
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Audius

    static final String AUDIUS = "https://api.audius.co/v1";
    static final String APP = "app_name=PlaylistBot";

    static String audiusGenre(String tag) {
        switch (tag) {
            case "pop": return "Pop";
            case "rock": return "Rock";
            case "electronic": return "Electronic";
            case "hiphop": return "Hip-Hop/Rap";
            case "indie": return "Alternative";
            case "dance": return "House";
            case "chillout": return "Downtempo";
            case "jazz": return "Jazz";
            case "blues": return "Blues";
            case "folk": return "Folk";
            case "metal": return "Metal";
            case "punk": return "Punk";
            case "reggae": return "Reggae";
            case "lounge": return "Lo-Fi";
            case "ambient": return "Ambient";
            case "classical": return "Classical";
            case "soundtrack": return "Soundtrack";
            default: return "";
        }
    }

    /** Лише треки, для яких автор сам дозволив завантаження (без умов і оплати). */
    static boolean audiusFree(JSONObject o) {
        boolean dl = o.optBoolean("is_downloadable", false);
        JSONObject access = o.optJSONObject("access");
        if (access != null && !access.optBoolean("download", dl)) return false;
        if (o.has("download_conditions") && !o.isNull("download_conditions")) return false;
        if (o.has("stream_conditions") && !o.isNull("stream_conditions")) return false;
        return dl;
    }

    public static List<Track> audius(String genreTag, boolean newest, int needed) throws Exception {
        String genre = audiusGenre(genreTag);
        List<Track> out = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        String[] urls = {
                AUDIUS + "/tracks/search?" + APP + "&only_downloadable=true&limit=100"
                        + "&query=" + enc(genre.isEmpty() ? "music" : genre)
                        + (genre.isEmpty() ? "" : "&genre=" + enc(genre))
                        + "&sort_method=" + (newest ? "recent" : "popular"),
                AUDIUS + "/tracks/trending?" + APP + "&time=" + (newest ? "week" : "month")
                        + (genre.isEmpty() ? "" : "&genre=" + enc(genre))
        };
        Exception last = null;
        for (String url : urls) {
            if (out.size() >= needed) break;
            try {
                JSONObject root = new JSONObject(httpGet(url));
                JSONArray arr = root.optJSONArray("data");
                if (arr == null) continue;
                for (int i = 0; i < arr.length() && out.size() < needed; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null || !audiusFree(o)) continue;
                    String id = o.optString("id", "");
                    if (id.isEmpty() || !ids.add(id)) continue;
                    Track t = new Track();
                    t.title = o.optString("title", "Без назви");
                    JSONObject u = o.optJSONObject("user");
                    t.artist = u != null ? u.optString("name", "Невідомий виконавець") : "Невідомий виконавець";
                    t.downloadUrl = AUDIUS + "/tracks/" + enc(id) + "/stream?" + APP;
                    t.streamUrl = t.downloadUrl;
                    t.license = "Audius: завантаження дозволене автором (лише для особистого прослуховування)";
                    t.pageUrl = "https://audius.co" + o.optString("permalink", "");
                    t.source = "Audius";
                    out.add(t);
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return out;
    }

    // ---------------------------------------------------------------- ccMixter

    public static List<Track> ccMixter(String genreTag, boolean instrumental, boolean newest, int needed) throws Exception {
        String tag = "hiphop".equals(genreTag) ? "hip_hop" : genreTag;
        StringBuilder tags = new StringBuilder();
        if (!tag.isEmpty()) tags.append(tag);
        if (instrumental) tags.append(tags.length() > 0 ? "+" : "").append("instrumental");
        String url = "https://ccmixter.org/api/query?f=json&limit=" + Math.min(needed * 2, 200)
                + "&sort=" + (newest ? "date" : "rank") + "&ord=desc"
                + (tags.length() > 0 ? "&tags=" + tags : "");
        JSONArray arr = new JSONArray(httpGet(url));
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < needed; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            JSONArray files = o.optJSONArray("files");
            if (files == null) continue;
            String dl = "";
            for (int k = 0; k < files.length(); k++) {
                JSONObject f = files.optJSONObject(k);
                if (f == null) continue;
                String u = f.optString("download_url", "");
                if (u.toLowerCase(Locale.ROOT).endsWith(".mp3")) { dl = u; break; }
            }
            if (dl.isEmpty()) continue;
            Track t = new Track();
            t.title = o.optString("upload_name", "Без назви");
            String an = o.optString("user_real_name", "");
            t.artist = an.isEmpty() ? o.optString("user_name", "Невідомий виконавець") : an;
            t.downloadUrl = dl;
            t.streamUrl = dl;
            t.license = o.optString("license_url", "Creative Commons");
            t.pageUrl = o.optString("file_page_url", "");
            t.source = "ccMixter";
            out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- Openverse

    public static List<Track> openverse(String genreTag, int needed) throws Exception {
        String q = genreTag.isEmpty() ? "music" : ("hiphop".equals(genreTag) ? "hip hop" : genreTag);
        List<Track> out = new ArrayList<>();
        Exception last = null;
        for (int page = 1; page <= 4 && out.size() < needed; page++) {
            try {
                String url = "https://api.openverse.org/v1/audio/?q=" + enc(q)
                        + "&category=music&page_size=20&page=" + page;
                JSONObject root = new JSONObject(httpGet(url));
                JSONArray arr = root.optJSONArray("results");
                if (arr == null || arr.length() == 0) break;
                for (int i = 0; i < arr.length() && out.size() < needed; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("url", "");
                    String ft = o.optString("filetype", "").toLowerCase(Locale.ROOT);
                    boolean mp3 = ft.startsWith("mp3") || u.toLowerCase(Locale.ROOT).contains(".mp3") || u.contains("format=mp3");
                    if (u.isEmpty() || !mp3) continue;
                    Track t = new Track();
                    t.title = o.optString("title", "Без назви");
                    t.artist = o.optString("creator", "Невідомий виконавець");
                    t.downloadUrl = u;
                    t.streamUrl = u;
                    String lic = o.optString("license", "").toUpperCase(Locale.ROOT);
                    t.license = "CC " + lic + " " + o.optString("license_version", "") + " " + o.optString("license_url", "");
                    t.pageUrl = o.optString("foreign_landing_url", "");
                    t.source = "Openverse/" + o.optString("source", "");
                    out.add(t);
                }
            } catch (Exception e) {
                last = e;
                break;
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return out;
    }

    static String firstString(Object o) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            return a.length() > 0 ? a.optString(0, "") : "";
        }
        return o == null ? "" : String.valueOf(o);
    }

    static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
