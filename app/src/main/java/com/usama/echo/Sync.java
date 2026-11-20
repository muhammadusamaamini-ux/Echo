package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone ⇄ PC sync, the same way as Book Shelf: one shared library file (echo/library.json) in your private
 * GitHub data repository. Music files are uploaded once to echo/files/ and downloaded by the other device.
 * Google Drive and YouTube items are links, so only their details are shared.
 *
 * Each device remembers how the library looked after its last sync (the "snapshot"). Anything different
 * now was changed here, anything missing now was removed here; the newest change wins. Same rules as the
 * PC version (pc/src/renderer/js/sync.js), so the two always agree.
 */
final class Sync {
    private Sync() {
    }

    static final long MAX = 45L * 1024 * 1024; // GitHub's limit per file (with room to spare)
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean applying; // true while sync itself changes the library (so it doesn't trigger another sync)
    static volatile String status = "Sync is off";
    static Runnable onChanged; // the open screen refreshes itself
    private static Runnable pending;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        SharedPreferences p = prefs(c);
        return !p.getString("repo", "").isEmpty() && !p.getString("token", "").isEmpty();
    }

    /** Sync a little while after something changed (calls close together become one sync). */
    static void later(Context c, long delayMs) {
        final Context app = c.getApplicationContext();
        if (!on(app)) return;
        main.post(() -> {
            if (pending != null) main.removeCallbacks(pending);
            pending = () -> {
                pending = null;
                start(app, false, null);
            };
            main.postDelayed(pending, delayMs);
        });
    }

    interface Done {
        void onDone(boolean ok, String message);
    }

    /** Syncs on a background thread. done (may be null) is called on the main thread. */
    static void start(Context c, boolean manual, Done done) {
        final Context app = c.getApplicationContext();
        if (!on(app) || !running.compareAndSet(false, true)) {
            if (done != null) main.post(() -> done.onDone(false, on(app) ? "Already syncing" : "Sync is off"));
            return;
        }
        new Thread(() -> {
            String msg;
            boolean ok;
            try {
                int got = run(app, manual);
                ok = true;
                msg = got > 0 ? "Synced · " + got + (got == 1 ? " song" : " songs") + " downloaded" : "Synced";
                status = "Synced " + new java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(new java.util.Date());
                prefs(app).edit().putLong("last", System.currentTimeMillis()).apply();
            } catch (HttpError e) {
                ok = false;
                msg = e.code == 401 ? "GitHub didn't accept the token. Open ⋮ → Sync and paste a new one." : e.getMessage();
                status = e.code == 401 ? "Sync: token expired" : "Sync paused";
            } catch (Exception e) {
                ok = false;
                msg = "Couldn't reach GitHub. Check your internet.";
                status = "Sync paused (offline?)";
            } finally {
                running.set(false);
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            main.post(() -> {
                if (onChanged != null) onChanged.run();
                if (done != null) done.onDone(fOk, fMsg);
            });
        }, "echo-sync").start();
    }

    /** Runs a sync on the calling (background) thread; for the background job. */
    static void runBlocking(Context c) {
        Context app = c.getApplicationContext();
        if (!on(app) || !running.compareAndSet(false, true)) return;
        try {
            run(app, false);
            prefs(app).edit().putLong("last", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        } finally {
            running.set(false);
        }
    }

    // ---------- one sync ----------

    private static int run(Context app, boolean manual) throws Exception {
        SharedPreferences sp = prefs(app);
        Gh gh = new Gh(sp.getString("repo", ""), sp.getString("token", ""));
        status = "Syncing…";
        String branch = sp.getString("branch", "");
        if (branch.isEmpty()) {
            branch = new JSONObject(gh.call("GET", "", null).body).optString("default_branch", "main");
            sp.edit().putString("branch", branch).apply();
        }
        keyFiles(app, manual ? 120_000 : 30_000);
        Library lib = Library.get(app);
        for (int attempt = 0; attempt < 3; attempt++) {
            // 1. the shared file as it is now
            JSONObject remote = emptyDoc();
            String sha = null;
            Resp r = gh.call("GET", "/contents/echo/library.json?ref=" + Uri.encode(branch), null);
            if (r.code == 200) {
                JSONObject f = new JSONObject(r.body);
                sha = f.getString("sha");
                JSONObject doc = new JSONObject(new String(Base64.decode(f.getString("content"), Base64.DEFAULT), StandardCharsets.UTF_8));
                for (String k : new String[]{"songs", "folders", "tomb"}) if (doc.optJSONObject(k) != null) remote.put(k, doc.getJSONObject(k));
            } else if (r.code != 404) throw r.error();

            // 2. what changed here since last time (computed on the main thread, where the library lives)
            final JSONObject fRemote = remote;
            final JSONObject snap = new JSONObject(sp.getString("snap", "{}"));
            final long now = System.currentTimeMillis();
            final Plan plan = onMain(() -> plan(lib, fRemote, snap, now));

            // 3. upload music files the other device doesn't have yet
            int up = 0;
            for (Upload u : plan.uploads) {
                JSONObject e = plan.merged.getJSONObject("songs").getJSONObject(u.key);
                if (u.size > MAX) {
                    e.put("tooBig", true);
                    continue;
                }
                status = "Uploading " + e.optString("title") + "… (" + (++up) + " of " + plan.uploads.size() + ")";
                if (gh.upload(app, "echo/files/" + u.file + "." + e.optString("ext", "mp3"), u.uri, u.size, branch)) e.put("uploaded", true);
            }

            // 4. save the shared file (if the other device saved in between, start again)
            if (!canon(plan.merged).equals(canon(remote)) || !plan.uploads.isEmpty()) {
                JSONObject body = new JSONObject();
                body.put("message", "Echo sync");
                body.put("branch", branch);
                body.put("content", Base64.encodeToString(plan.merged.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                if (sha != null) body.put("sha", sha);
                Resp w = gh.call("PUT", "/contents/echo/library.json", body.toString());
                if (w.code == 409 || w.code == 422) continue;
                if (w.code / 100 != 2) throw w.error();
            }

            // 5. bring the other device's changes here
            final List<Download> downloads = onMain(() -> apply(lib, plan, snap));
            int got = 0;
            File dir = lib.musicDir();
            for (Download d : downloads) {
                status = "Downloading " + d.e.optString("title") + "… (" + (got + 1) + " of " + downloads.size() + ")";
                String name = d.e.optString("title", "Song").replaceAll("[\\\\/:*?\"<>|]", "_");
                if (name.length() > 100) name = name.substring(0, 100);
                String ext = d.e.optString("ext", "mp3");
                File out = new File(dir, name + "." + ext);
                for (int i = 2; out.exists(); i++) out = new File(dir, name + " (" + i + ")." + ext);
                if (!gh.download("echo/files/" + d.e.getString("file") + "." + ext, out, branch)) continue;
                got++;
                final File fOut = out;
                onMain(() -> {
                    addDownloaded(lib, d, fOut);
                    return null;
                });
            }

            // 6. remember how things look now
            onMain(() -> {
                JSONObject after = local(lib);
                JSONObject ns = new JSONObject();
                for (String kind : new String[]{"songs", "folders"}) {
                    JSONObject m = after.getJSONObject(kind);
                    for (Iterator<String> it = m.keys(); it.hasNext(); ) {
                        String k = it.next();
                        JSONObject me = plan.merged.getJSONObject(kind).optJSONObject(k);
                        ns.put(k, new JSONObject().put("h", hash(m.getJSONObject(k))).put("t", me != null ? me.optLong("t", now) : now));
                    }
                }
                sp.edit().putString("snap", ns.toString()).apply();
                applying = true;
                try {
                    lib.save();
                } finally {
                    applying = false;
                }
                return null;
            });
            return got;
        }
        throw new Exception("The library kept changing on the other device. Try again in a minute.");
    }

    static final class Upload {
        String key, file, uri;
        long size;
    }

    static final class Download {
        String key;
        JSONObject e;
    }

    static final class Plan {
        JSONObject merged, local;
        Set<String> changedHere = new HashSet<>();
        List<Upload> uploads = new ArrayList<>();
    }

    private static JSONObject emptyDoc() throws Exception {
        return new JSONObject().put("v", 1).put("songs", new JSONObject()).put("folders", new JSONObject()).put("tomb", new JSONObject());
    }

    private static Plan plan(Library lib, JSONObject remote, JSONObject snap, long now) throws Exception {
        Plan p = new Plan();
        p.local = local(lib);
        p.merged = new JSONObject(remote.toString());
        JSONObject tomb = p.merged.getJSONObject("tomb");
        for (String kind : new String[]{"songs", "folders"}) {
            JSONObject loc = p.local.getJSONObject(kind);
            for (Iterator<String> it = loc.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = loc.getJSONObject(k);
                JSONObject had = snap.optJSONObject(k);
                boolean changed = had != null ? !had.optString("h").equals(hash(e)) : !remote.getJSONObject(kind).has(k);
                if (!changed) continue;
                p.changedHere.add(k);
                p.merged.getJSONObject(kind).put(k, new JSONObject(e.toString()).put("t", now));
                tomb.remove(k);
            }
        }
        for (Iterator<String> it = snap.keys(); it.hasNext(); ) { // removed here
            String k = it.next();
            if (!p.local.getJSONObject("songs").has(k) && !p.local.getJSONObject("folders").has(k)) {
                p.merged.getJSONObject("songs").remove(k);
                p.merged.getJSONObject("folders").remove(k);
                tomb.put(k, now);
            }
        }
        JSONObject ms = p.merged.getJSONObject("songs");
        for (Iterator<String> it = ms.keys(); it.hasNext(); ) { // keep the upload flags
            String k = it.next();
            JSONObject e = ms.getJSONObject(k), r = remote.getJSONObject("songs").optJSONObject(k);
            if (r != null && "file".equals(e.optString("kind"))) {
                if (r.optBoolean("uploaded")) e.put("uploaded", true);
                if (r.optBoolean("tooBig")) e.put("tooBig", true);
            }
        }
        for (Library.Song s : lib.songs) {
            if (s.k == null || !isFileSong(s)) continue;
            JSONObject e = ms.optJSONObject("h_" + s.k);
            if (e == null || e.optBoolean("uploaded") || e.optBoolean("tooBig")) continue;
            Upload u = new Upload();
            u.key = "h_" + s.k;
            u.file = s.k;
            u.uri = s.uri;
            u.size = s.size;
            p.uploads.add(u);
        }
        return p;
    }

    private static List<Download> apply(Library lib, Plan p, JSONObject snap) throws Exception {
        List<Download> downloads = new ArrayList<>();
        JSONObject ls = p.local.getJSONObject("songs"), lf = p.local.getJSONObject("folders");
        applying = true;
        try {
            JSONObject ms = p.merged.getJSONObject("songs");
            for (Iterator<String> it = ms.keys(); it.hasNext(); ) {
                String k = it.next();
                if (p.changedHere.contains(k) || (snap.has(k) && !ls.has(k))) continue;
                JSONObject e = ms.getJSONObject(k), mine = ls.optJSONObject(k);
                if (mine != null && hash(mine).equals(hash(e))) continue;
                Library.Song s = songByKey(lib, k);
                if (s != null) {
                    s.title = e.optString("title", s.title);
                    s.artist = e.optString("artist", s.artist);
                    s.album = e.optString("album", s.album);
                    if (e.optLong("duration") > 0) s.duration = e.optLong("duration");
                } else if ("drive".equals(e.optString("kind"))) {
                    Library.Song n = new Library.Song();
                    n.id = k;
                    n.source = "drive";
                    n.uri = e.optString("uri");
                    n.title = e.optString("title");
                    n.artist = e.optString("artist");
                    n.album = e.optString("album");
                    n.duration = e.optLong("duration");
                    n.added = System.currentTimeMillis();
                    lib.addSong(n);
                } else if ("file".equals(e.optString("kind")) && e.optBoolean("uploaded") && e.has("file")) {
                    Download d = new Download();
                    d.key = k;
                    d.e = e;
                    downloads.add(d);
                }
            }
            JSONObject mf = p.merged.getJSONObject("folders");
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = mf.keys(); it.hasNext(); ) {
                String k = it.next();
                if (p.changedHere.contains(k) || (snap.has(k) && !lf.has(k))) continue;
                JSONObject mine = lf.optJSONObject(k);
                if (mine != null && hash(mine).equals(hash(mf.getJSONObject(k)))) continue;
                keys.add(k);
            }
            applyFolders(lib, mf, keys);
            JSONObject tomb = p.merged.getJSONObject("tomb");
            for (Iterator<String> it = tomb.keys(); it.hasNext(); ) {
                String k = it.next();
                if (p.changedHere.contains(k)) continue;
                JSONObject had = snap.optJSONObject(k);
                if (had != null && had.optLong("t") > tomb.optLong(k)) continue;
                Library.Song s = songByKey(lib, k);
                if (s != null) lib.deleteSongQuiet(s);
                Library.Playlist f = folderByKey(lib, k);
                if (f != null) {
                    for (Library.Playlist ch : lib.children(f.id)) ch.parentId = f.parentId;
                    lib.playlists.remove(f);
                    lib.recent.remove(f.id);
                }
            }
            lib.save();
        } finally {
            applying = false;
        }
        return downloads;
    }

    private static void applyFolders(Library lib, JSONObject remote, List<String> keys) throws Exception {
        for (String k : keys) { // make every folder exist first (a parent may be new too)
            if (folderByKey(lib, k) != null) continue;
            JSONObject e = remote.getJSONObject(k);
            Library.Playlist p = new Library.Playlist();
            p.id = (k.startsWith("drv_") || k.startsWith("yt_")) ? Library.newId() : k;
            p.name = e.optString("name", "Folder");
            JSONObject src = e.optJSONObject("src");
            if (src != null && "drive".equals(src.optString("kind"))) {
                p.driveFolder = src.optString("id");
                p.driveRoot = src.optString("root", p.driveFolder);
                p.driveName = src.optString("name", p.name);
            }
            lib.playlists.add(p);
        }
        for (String k : keys) {
            JSONObject e = remote.getJSONObject(k);
            Library.Playlist p = folderByKey(lib, k);
            if (p == null) continue;
            p.name = e.optString("name", p.name);
            p.hidden = e.optBoolean("hidden");
            Library.Playlist par = e.isNull("parent") ? null : folderByKey(lib, e.optString("parent"));
            p.parentId = par != null && par != p ? par.id : null;
            if (e.optBoolean("youtube")) {
                p.youtube = true;
                p.ytListId = e.optString("ytListId", null);
                p.ytItems.clear();
                JSONArray yt = e.optJSONArray("yt");
                if (yt != null) for (int i = 0; i < yt.length(); i++) {
                    JSONObject y = yt.getJSONObject(i);
                    Library.YtItem it = new Library.YtItem();
                    it.videoId = y.optString("v");
                    it.title = y.optString("t");
                    it.author = y.optString("a");
                    p.ytItems.add(it);
                }
            }
            JSONObject src = e.optJSONObject("src");
            if (src != null && !"drive".equals(src.optString("kind"))) p.remoteSrc = src.toString();
            p.songIds.clear();
            p.pendingKeys.clear();
            JSONArray songs = e.optJSONArray("songs");
            if (songs != null) for (int i = 0; i < songs.length(); i++) {
                Library.Song s = songByKey(lib, songs.getString(i));
                if (s != null) {
                    if (!p.songIds.contains(s.id)) p.songIds.add(s.id);
                } else p.pendingKeys.add(songs.getString(i));
            }
        }
    }

    private static void addDownloaded(Library lib, Download d, File f) {
        if (songByKey(lib, d.key) != null) {
            f.delete();
            return;
        }
        Library.Song s = new Library.Song();
        s.k = d.e.optString("file");
        s.id = "sy_" + s.k.substring(0, Math.min(16, s.k.length()));
        s.source = "file";
        s.uri = Uri.fromFile(f).toString();
        s.size = f.length();
        s.title = d.e.optString("title");
        s.artist = d.e.optString("artist");
        s.album = d.e.optString("album");
        s.duration = d.e.optLong("duration");
        s.added = System.currentTimeMillis();
        lib.addSong(s);
        for (Library.Playlist p : lib.playlists) {
            if (p.pendingKeys.remove(d.key) && !p.songIds.contains(s.id)) p.songIds.add(s.id);
        }
    }

    // ---------- the library as shared entities ----------

    static boolean isFileSong(Library.Song s) {
        return "file".equals(s.source) || "device".equals(s.source);
    }

    static String songKey(Library.Song s) {
        if ("drive".equals(s.source)) return s.id;
        if (s.k != null && isFileSong(s)) return "h_" + s.k;
        return null;
    }

    static String folderKey(Library.Playlist p) {
        if (p.driveFolder != null) return "drv_" + p.driveFolder;
        if (p.youtube && p.ytListId != null) return "yt_" + p.ytListId;
        return p.id;
    }

    static Library.Song songByKey(Library lib, String k) {
        if (k.startsWith("gd_")) return lib.song(k);
        if (k.startsWith("h_")) {
            String h = k.substring(2);
            for (Library.Song s : lib.songs) if (h.equals(s.k)) return s;
        }
        return null;
    }

    static Library.Playlist folderByKey(Library lib, String k) {
        for (Library.Playlist p : lib.playlists) if (k.equals(folderKey(p))) return p;
        return null;
    }

    private static JSONObject local(Library lib) throws Exception {
        JSONObject songs = new JSONObject(), folders = new JSONObject();
        for (Library.Song s : lib.songs) {
            String k = songKey(s);
            if (k == null) continue;
            JSONObject e = new JSONObject();
            e.put("title", n(s.title)).put("artist", n(s.artist)).put("album", n(s.album)).put("duration", s.duration);
            if ("drive".equals(s.source)) e.put("kind", "drive").put("uri", s.uri);
            else e.put("kind", "file").put("file", s.k).put("ext", ext(lib, s)).put("size", s.size);
            songs.put(k, e);
        }
        for (Library.Playlist p : lib.playlists) {
            JSONObject e = new JSONObject();
            Library.Playlist par = lib.playlist(p.parentId);
            e.put("name", n(p.name)).put("parent", par == null ? JSONObject.NULL : folderKey(par)).put("hidden", p.hidden);
            JSONArray ids = new JSONArray();
            List<String> seen = new ArrayList<>();
            for (String id : p.songIds) {
                Library.Song s = lib.song(id);
                String k = s == null ? null : songKey(s);
                if (k != null && !seen.contains(k)) { seen.add(k); ids.put(k); }
            }
            for (String k : p.pendingKeys) if (!seen.contains(k)) { seen.add(k); ids.put(k); }
            e.put("songs", ids);
            if (p.youtube) {
                e.put("youtube", true).put("ytListId", p.ytListId == null ? JSONObject.NULL : p.ytListId);
                JSONArray yt = new JSONArray();
                for (Library.YtItem it : p.ytItems) yt.put(new JSONObject().put("v", it.videoId).put("t", n(it.title)).put("a", n(it.author)));
                e.put("yt", yt);
            }
            if (p.driveFolder != null) {
                e.put("src", new JSONObject().put("kind", "drive").put("id", p.driveFolder)
                        .put("root", p.driveRoot != null ? p.driveRoot : p.driveFolder).put("name", n(p.driveName)));
            } else if (p.remoteSrc != null) {
                try {
                    e.put("src", new JSONObject(p.remoteSrc));
                } catch (Exception ignored) {
                }
            }
            folders.put(folderKey(p), e);
        }
        return new JSONObject().put("songs", songs).put("folders", folders);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String ext(Library lib, Library.Song s) {
        String e = null;
        try {
            Uri u = Uri.parse(s.uri);
            if ("content".equals(u.getScheme())) {
                String mime = lib.context().getContentResolver().getType(u);
                if (mime != null) e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if ("audio/mpeg".equals(mime)) e = "mp3";
            } else {
                String path = u.getPath();
                int dot = path == null ? -1 : path.lastIndexOf('.');
                if (dot > 0) e = path.substring(dot + 1);
            }
        } catch (Exception ignored) {
        }
        if (e == null || e.isEmpty() || e.length() > 5) e = "mp3";
        return e.toLowerCase(Locale.ROOT);
    }

    /** Gives music files on the phone a content key, so the same song is recognised on every device. */
    private static void keyFiles(Context app, long limitMs) throws Exception {
        final Library lib = Library.get(app);
        List<String[]> todo = onMain(() -> {
            List<String[]> out = new ArrayList<>();
            for (Library.Song s : lib.songs) if (isFileSong(s) && s.k == null) out.add(new String[]{s.id, s.uri});
            return out;
        });
        long until = System.currentTimeMillis() + limitMs;
        for (String[] t : todo) {
            if (System.currentTimeMillis() > until) break;
            final long[] size = {0};
            final String h;
            try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(t[1]))) {
                if (in == null) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) {
                    md.update(buf, 0, r);
                    size[0] += r;
                }
                h = hex(md.digest());
            } catch (Exception e) {
                continue; // the file is gone or can't be read
            }
            onMain(() -> {
                Library.Song s = lib.song(t[0]);
                if (s != null) {
                    s.k = h;
                    s.size = size[0];
                }
                return null;
            });
        }
    }

    // ---------- comparing ----------

    static String canon(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            TreeSet<String> keys = new TreeSet<>();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
            StringBuilder b = new StringBuilder("{");
            for (String k : keys) {
                if ("t".equals(k)) continue;
                if (b.length() > 1) b.append(',');
                b.append(JSONObject.quote(k)).append(':').append(canon(o.opt(k)));
            }
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(',');
                b.append(canon(a.opt(i)));
            }
            return b.append(']').toString();
        }
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return JSONObject.quote((String) v);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }
        return String.valueOf(v);
    }

    static String hash(JSONObject e) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(canon(e).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception x) {
            return canon(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static <T> T onMain(Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        FutureTask<T> t = new FutureTask<>(c);
        main.post(t);
        try {
            return t.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    // ---------- GitHub ----------

    static final class HttpError extends Exception {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    static final class Resp {
        int code;
        String body;

        HttpError error() {
            String m = "GitHub answered " + code;
            try {
                m = new JSONObject(body).optString("message", m);
            } catch (Exception ignored) {
            }
            return new HttpError(code, m);
        }
    }

    static final class Gh {
        final String repo, token;

        Gh(String repo, String token) {
            this.repo = repo;
            this.token = token;
        }

        HttpURLConnection open(String method, String path, String accept) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + path).openConnection();
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", accept != null ? accept : "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            return c;
        }

        Resp call(String method, String path, String body) throws Exception {
            HttpURLConnection c = open(method, path, null);
            try {
                if (body != null) {
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(b.length);
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(b);
                    }
                }
                Resp r = new Resp();
                r.code = c.getResponseCode();
                InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
                r.body = in == null ? "" : read(in);
                if (r.code == 401 || r.code == 403 && r.body.contains("Bad credentials")) throw r.error();
                return r;
            } finally {
                c.disconnect();
            }
        }

        /** Uploads a music file unless it's already there. The file is streamed, not loaded into memory. */
        boolean upload(Context app, String remote, String uri, long size, String branch) throws Exception {
            Resp ex = call("GET", "/contents/" + remote + "?ref=" + Uri.encode(branch), null);
            if (ex.code == 200) return true;
            byte[] head = ("{\"message\":\"Echo music file\",\"branch\":" + JSONObject.quote(branch) + ",\"content\":\"").getBytes(StandardCharsets.UTF_8);
            byte[] tail = "\"}".getBytes(StandardCharsets.UTF_8);
            long b64 = 4 * ((size + 2) / 3);
            HttpURLConnection c = open("PUT", "/contents/" + remote, null);
            try {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(head.length + b64 + tail.length);
                c.setReadTimeout(300000);
                try (OutputStream o = c.getOutputStream(); InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri))) {
                    if (in == null) return false;
                    o.write(head);
                    Base64OutputStream enc = new Base64OutputStream(new NoClose(o), Base64.NO_WRAP);
                    byte[] buf = new byte[1 << 16];
                    long sent = 0;
                    for (int r; (r = in.read(buf)) > 0 && sent < size; ) {
                        int n2 = (int) Math.min(r, size - sent);
                        enc.write(buf, 0, n2);
                        sent += n2;
                    }
                    enc.close();
                    if (sent != size) return false;
                    o.write(tail);
                }
                int code = c.getResponseCode();
                return code / 100 == 2 || code == 422; // 422: it was uploaded meanwhile
            } finally {
                c.disconnect();
            }
        }

        boolean download(String remote, File out, String branch) {
            try {
                HttpURLConnection c = open("GET", "/contents/" + remote + "?ref=" + Uri.encode(branch), "application/vnd.github.raw");
                c.setInstanceFollowRedirects(true);
                try {
                    if (c.getResponseCode() != 200) return false;
                    File tmp = new File(out.getPath() + ".part");
                    try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[1 << 16];
                        for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
                    }
                    return tmp.renameTo(out);
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static final class NoClose extends java.io.FilterOutputStream {
        NoClose(OutputStream o) {
            super(o);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws java.io.IOException {
            flush();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) > 0; ) b.write(buf, 0, r);
        in.close();
        return b.toString("UTF-8");
    }

    // ---------- settings (⋮ → Sync) ----------

    static void show(Activity a) {
        SharedPreferences sp = prefs(a);
        if (on(a)) {
            new AlertDialog.Builder(a).setTitle("Sync between your phone and PC")
                    .setMessage("Connected to " + sp.getString("repo", "") + ". Your folders, playlists and songs are kept the same on your phone and PC.\n\nStatus: " + status)
                    .setPositiveButton("Sync now", (d, w) -> {
                        Toast.makeText(a, "Syncing…", Toast.LENGTH_SHORT).show();
                        start(a, true, (ok, msg) -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
                    })
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Turn off", (d, w) -> new AlertDialog.Builder(a).setTitle("Turn off sync")
                            .setMessage("Echo on this phone stops syncing. Nothing is deleted.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Turn off", (d2, w2) -> {
                                sp.edit().clear().apply();
                                status = "Sync is off";
                            }).show())
                    .show();
            return;
        }
        LinearLayout box = Ui.column(a);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, Ui.dp(a, 8), pad, 0);
        TextView info = new TextView(a);
        info.setText("Sync keeps your library and music in a private GitHub repository that only you can see, the same way Book Shelf does.\n\n"
                + "1. Create a private repository named Echo-Data:\nhttps://github.com/new\n\n"
                + "2. Make a token:\nhttps://github.com/settings/personal-access-tokens/new\nRepository access: Only select repositories → Echo-Data. Permissions → Contents: Read and write. Generate, then copy it.\n\n"
                + "3. Paste it below. On your PC: Echo → Sync, and paste the same token.");
        info.setTextIsSelectable(true);
        Linkify.addLinks(info, Linkify.WEB_URLS);
        info.setMovementMethod(LinkMovementMethod.getInstance());
        box.addView(info);
        final EditText repo = new EditText(a);
        repo.setSingleLine(true);
        repo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        repo.setText("muhammadusamaamini-ux/Echo-Data");
        final EditText tok = new EditText(a);
        tok.setSingleLine(true);
        tok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tok.setHint("github_pat_…");
        box.addView(repo);
        box.addView(tok);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Sync between your phone and PC").setView(sv)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String r = repo.getText().toString().trim().replaceFirst("^https?://github\\.com/", "").replaceFirst("\\.git$", "").replaceFirst("/$", "");
            String t = tok.getText().toString().trim();
            if (!r.matches("[\\w.-]+/[\\w.-]+") || t.isEmpty()) {
                Toast.makeText(a, "Enter the repository as owner/name and paste the token.", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(a, "Checking…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String err = null;
                String branch = "main";
                boolean priv = true;
                try {
                    Resp res = new Gh(r, t).call("GET", "", null);
                    if (res.code == 404) err = "That repository wasn't found, or the token isn't allowed to use it.";
                    else if (res.code != 200) err = res.error().getMessage();
                    else {
                        JSONObject j = new JSONObject(res.body);
                        JSONObject perm = j.optJSONObject("permissions");
                        if (perm != null && !perm.optBoolean("push")) err = "The token can read but not write. Set Contents to \"Read and write\".";
                        branch = j.optString("default_branch", "main");
                        priv = j.optBoolean("private", true);
                    }
                } catch (HttpError e) {
                    err = "GitHub didn't accept this token. Copy it again and paste it.";
                } catch (Exception e) {
                    err = "Couldn't reach GitHub. Check your internet.";
                }
                final String fErr = err, fBranch = branch;
                final boolean fPriv = priv;
                main.post(() -> {
                    if (fErr != null) {
                        Toast.makeText(a, fErr, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!fPriv) Toast.makeText(a, "Warning: this repository is public. Make it private on GitHub.", Toast.LENGTH_LONG).show();
                    sp.edit().putString("repo", r).putString("token", t).putString("branch", fBranch).putString("snap", "{}").apply();
                    d.dismiss();
                    Toast.makeText(a, "Connected. Syncing…", Toast.LENGTH_SHORT).show();
                    start(a, true, (ok, msg) -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
                });
            }).start();
        }));
        d.show();
    }
}
