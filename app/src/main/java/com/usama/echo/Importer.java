package com.usama.echo;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Brings songs into the library: files picked from phone storage, or the phone's music. */
final class Importer {
    interface Progress {
        void onProgress(int done, int total);
    }

    interface Done {
        void onDone(List<Library.Song> added, int failed);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());

    private Importer() {}

    /** Copies each picked file into the app, so it plays offline and stays even if the original moves. */
    static void importFiles(Context ctx, List<Uri> uris, Progress progress, Done done) {
        final Context app = ctx.getApplicationContext();
        final File dir = Library.get(app).musicDir();
        new Thread(() -> {
            List<Library.Song> added = new ArrayList<>();
            int failed = 0;
            for (int i = 0; i < uris.size(); i++) {
                final int n = i;
                main.post(() -> progress.onProgress(n, uris.size()));
                Uri uri = uris.get(i);
                File out = null;
                try {
                    String name = displayName(app, uri);
                    String ext = extension(app, uri, name);
                    String id = Library.newId();
                    out = new File(dir, id + "." + ext);
                    try (InputStream in = app.getContentResolver().openInputStream(uri);
                         OutputStream os = new FileOutputStream(out)) {
                        if (in == null) throw new Exception("Cannot open");
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                    }
                    Library.Song s = new Library.Song();
                    s.id = id;
                    s.source = "file";
                    s.uri = Uri.fromFile(out).toString();
                    s.added = System.currentTimeMillis();
                    readTags(out.getPath(), s);
                    if (s.title == null || s.title.trim().isEmpty()) s.title = stripExt(name);
                    added.add(s);
                } catch (Exception e) {
                    failed++;
                    if (out != null) out.delete();
                }
            }
            final int f = failed;
            main.post(() -> {
                Library lib = Library.get(app);
                for (Library.Song s : added) lib.addSong(s);
                lib.save();
                done.onDone(added, f);
            });
        }).start();
    }

    /** Adds the music already on the phone (it stays where it is). */
    static void scanDevice(Context ctx, Done done) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            List<Library.Song> found = new ArrayList<>();
            String[] cols = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION
            };
            try (Cursor c = app.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long mid = c.getLong(0);
                        Library.Song s = new Library.Song();
                        s.id = "dev_" + mid;
                        s.source = "device";
                        s.uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mid).toString();
                        s.title = c.getString(1);
                        s.artist = c.getString(2);
                        s.album = c.getString(3);
                        s.duration = c.getLong(4);
                        s.added = System.currentTimeMillis();
                        if (s.title == null) s.title = "Unknown";
                        found.add(s);
                    }
                }
            } catch (Exception ignored) {
            }
            main.post(() -> {
                Library lib = Library.get(app);
                List<Library.Song> added = new ArrayList<>();
                for (Library.Song s : found) {
                    if (!lib.has(s.id)) {
                        lib.addSong(s);
                        added.add(s);
                    }
                }
                lib.save();
                done.onDone(added, 0);
            });
        }).start();
    }

    private static void readTags(String path, Library.Song s) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            s.title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            s.artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            s.album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) s.duration = Long.parseLong(d.trim());
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (s.artist == null) s.artist = "";
        if (s.album == null) s.album = "";
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Song" : last;
    }

    private static String extension(Context c, Uri uri, String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String e = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (e.matches("[a-z0-9]{1,5}")) return e;
        }
        String mime = c.getContentResolver().getType(uri);
        String e = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return e == null ? "mp3" : e;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------- matching YouTube titles with your own songs ----------

    private static final String NOISE =
            "\\b(official|music|video|audio|lyrics?|lyrical|hd|hq|4k|full|song|ft|feat|featuring|mv|visualizer|remastered|version|topic|vevo|new|latest)\\b";

    static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        s = s.replaceAll("[^\\p{L}\\p{N}]+", " ");
        s = s.replaceAll(NOISE, " ");
        return s.trim().replaceAll(" +", " ");
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Finds the best song in the library for a YouTube video, or null. */
    static Library.Song match(Library.YtItem it, List<Library.Song> songs) {
        Set<String> yt = words(norm(it.title + " " + it.author));
        Library.Song best = null;
        double bestScore = 0;
        for (Library.Song s : songs) {
            Set<String> t = words(norm(s.title));
            if (t.isEmpty()) continue;
            int hit = 0;
            for (String w : t) if (yt.contains(w)) hit++;
            double score = hit / (double) t.size();
            if (t.size() == 1 && (score < 1 || t.iterator().next().length() < 4)) continue;
            Set<String> ar = words(norm(s.artist));
            if (!ar.isEmpty()) {
                int h2 = 0;
                for (String w : ar) if (yt.contains(w)) h2++;
                score += 0.25 * h2 / ar.size();
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return bestScore >= 0.8 ? best : null;
    }
}
