package com.usama.naghma;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All songs and playlists, saved as one JSON file inside the app. Use from the main thread. */
final class Library {

    static final class Song {
        String id, title, artist, album, uri, source; // source: "file" (copied into the app) or "device"
        long duration, added;

        String subtitle() {
            String a = (artist == null || artist.isEmpty() || "<unknown>".equals(artist)) ? "Unknown artist" : artist;
            return duration > 0 ? a + "  ·  " + Ui.time(duration) : a;
        }
    }

    static final class YtItem {
        String videoId, title, author;
    }

    static final class Playlist {
        String id, name;
        boolean youtube;
        String ytListId;
        final List<String> songIds = new ArrayList<>();
        final List<YtItem> ytItems = new ArrayList<>();

        String subtitle() {
            if (youtube) return "YouTube  ·  " + ytItems.size() + (ytItems.size() == 1 ? " video" : " videos");
            return songIds.size() + (songIds.size() == 1 ? " song" : " songs");
        }
    }

    private static Library instance;

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File file;
    final List<Song> songs = new ArrayList<>();
    final List<Playlist> playlists = new ArrayList<>();
    private final Map<String, Song> byId = new HashMap<>();

    private Library(Context c) {
        ctx = c;
        file = new File(c.getFilesDir(), "library.json");
        load();
    }

    File musicDir() {
        File d = new File(ctx.getFilesDir(), "music");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 46655), 36);
    }

    // ---------- songs ----------

    Song song(String id) {
        return id == null ? null : byId.get(id);
    }

    boolean has(String id) {
        return byId.containsKey(id);
    }

    void addSong(Song s) {
        if (byId.containsKey(s.id)) return;
        songs.add(s);
        byId.put(s.id, s);
    }

    void deleteSong(Song s) {
        songs.remove(s);
        byId.remove(s.id);
        for (Playlist p : playlists) p.songIds.remove(s.id);
        if ("file".equals(s.source)) {
            try {
                String path = Uri.parse(s.uri).getPath();
                if (path != null) new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        save();
    }

    List<Song> sortedSongs() {
        List<Song> list = new ArrayList<>(songs);
        Collections.sort(list, (a, b) -> safe(a.title).compareToIgnoreCase(safe(b.title)));
        return list;
    }

    List<Song> search(String q) {
        List<Song> out = new ArrayList<>();
        String s = q.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return out;
        for (Song song : sortedSongs()) {
            if (safe(song.title).toLowerCase(Locale.ROOT).contains(s)
                    || safe(song.artist).toLowerCase(Locale.ROOT).contains(s)
                    || safe(song.album).toLowerCase(Locale.ROOT).contains(s)) out.add(song);
        }
        return out;
    }

    // ---------- playlists ----------

    Playlist newPlaylist(String name) {
        Playlist p = new Playlist();
        p.id = newId();
        p.name = name;
        playlists.add(p);
        save();
        return p;
    }

    Playlist playlist(String id) {
        for (Playlist p : playlists) if (p.id.equals(id)) return p;
        return null;
    }

    List<Playlist> localPlaylists() {
        List<Playlist> out = new ArrayList<>();
        for (Playlist p : playlists) if (!p.youtube) out.add(p);
        return out;
    }

    List<Song> songsOf(Playlist p) {
        List<Song> out = new ArrayList<>();
        for (String id : p.songIds) {
            Song s = byId.get(id);
            if (s != null) out.add(s);
        }
        return out;
    }

    // ---------- player items ----------

    static MediaItem item(Song s) {
        Uri uri = Uri.parse(s.uri);
        return new MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(uri)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build())
                .build();
    }

    static List<MediaItem> items(List<Song> list) {
        List<MediaItem> out = new ArrayList<>();
        for (Song s : list) out.add(item(s));
        return out;
    }

    /** Puts the file address back on an item that came through the media controller. */
    MediaItem resolve(MediaItem m) {
        Song s = byId.get(m.mediaId);
        if (s != null) return item(s);
        Uri uri = m.requestMetadata.mediaUri;
        if (uri == null && m.localConfiguration != null) uri = m.localConfiguration.uri;
        if (uri == null) return m;
        return m.buildUpon().setUri(uri).build();
    }

    // ---------- saving ----------

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] data;
            try (FileInputStream in = new FileInputStream(file)) {
                data = new byte[(int) file.length()];
                int off = 0;
                while (off < data.length) {
                    int r = in.read(data, off, data.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray ss = root.optJSONArray("songs");
            if (ss != null) for (int i = 0; i < ss.length(); i++) {
                JSONObject o = ss.getJSONObject(i);
                Song s = new Song();
                s.id = o.getString("id");
                s.title = o.optString("title", "");
                s.artist = o.optString("artist", "");
                s.album = o.optString("album", "");
                s.uri = o.getString("uri");
                s.source = o.optString("source", "file");
                s.duration = o.optLong("duration", 0);
                s.added = o.optLong("added", 0);
                addSong(s);
            }
            JSONArray ps = root.optJSONArray("playlists");
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.getJSONObject(i);
                Playlist p = new Playlist();
                p.id = o.getString("id");
                p.name = o.optString("name", "Playlist");
                p.youtube = o.optBoolean("youtube", false);
                p.ytListId = o.optString("ytListId", null);
                JSONArray ids = o.optJSONArray("songs");
                if (ids != null) for (int j = 0; j < ids.length(); j++) p.songIds.add(ids.getString(j));
                JSONArray yt = o.optJSONArray("yt");
                if (yt != null) for (int j = 0; j < yt.length(); j++) {
                    JSONObject y = yt.getJSONObject(j);
                    YtItem it = new YtItem();
                    it.videoId = y.getString("v");
                    it.title = y.optString("t", "");
                    it.author = y.optString("a", "");
                    p.ytItems.add(it);
                }
                playlists.add(p);
            }
        } catch (Exception e) {
            // A damaged file should not stop the app from opening.
        }
    }

    void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray ss = new JSONArray();
            for (Song s : songs) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", safe(s.title));
                o.put("artist", safe(s.artist));
                o.put("album", safe(s.album));
                o.put("uri", s.uri);
                o.put("source", s.source);
                o.put("duration", s.duration);
                o.put("added", s.added);
                ss.put(o);
            }
            root.put("songs", ss);
            JSONArray ps = new JSONArray();
            for (Playlist p : playlists) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("youtube", p.youtube);
                if (p.ytListId != null) o.put("ytListId", p.ytListId);
                JSONArray ids = new JSONArray();
                for (String id : p.songIds) ids.put(id);
                o.put("songs", ids);
                JSONArray yt = new JSONArray();
                for (YtItem it : p.ytItems) {
                    JSONObject y = new JSONObject();
                    y.put("v", it.videoId);
                    y.put("t", safe(it.title));
                    y.put("a", safe(it.author));
                    yt.put(y);
                }
                o.put("yt", yt);
                ps.put(o);
            }
            root.put("playlists", ps);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
        }
    }
}
