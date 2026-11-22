package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageButton;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sorting. Every list remembers its own choice (one folder A–Z, another newest first, another by artist).
 * Same choices as on the PC.
 */
final class Sorter {
    private Sorter() {
    }

    static final String[] SONG_CODES = {"custom", "title", "titleDesc", "artist", "album", "newest", "oldest", "modified", "longest", "shortest"};
    static final String[] SONG_LABELS = {"Custom order (as added)", "Name (A–Z)", "Name (Z–A)", "Artist (A–Z)", "Album (A–Z)",
            "Newest added first", "Oldest added first", "Date modified (newest first)", "Longest first", "Shortest first"};
    static final String[] VIDEO_CODES = {"name", "nameDesc", "newest", "oldest", "biggest", "smallest", "longest"};
    static final String[] VIDEO_LABELS = {"Name (A–Z)", "Name (Z–A)", "Newest first", "Oldest first", "Size (largest first)",
            "Size (smallest first)", "Longest first"};
    static final String[] VFOLDER_CODES = {"name", "nameDesc", "most", "newest", "biggest"};
    static final String[] VFOLDER_LABELS = {"Name (A–Z)", "Name (Z–A)", "Most videos first", "Newest videos first", "Size (largest first)"};
    static final String[] YT_CODES = {"custom", "title", "titleDesc", "artist"};
    static final String[] YT_LABELS = {"Playlist order", "Name (A–Z)", "Name (Z–A)", "Channel (A–Z)"};

    private static final Collator COL = Collator.getInstance(Locale.getDefault());

    static {
        COL.setStrength(Collator.PRIMARY);
    }

    private static int cmp(String a, String b) {
        return COL.compare(a == null ? "" : a, b == null ? "" : b);
    }

    /** Text sorts put empty values (e.g. no artist) last. */
    private static int cmpLast(String a, String b) {
        boolean ea = a == null || a.isEmpty(), eb = b == null || b.isEmpty();
        if (ea != eb) return ea ? 1 : -1;
        return cmp(a, b);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sorts", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) {
        return prefs(c).getString(key, def);
    }

    /** A sort button for a screen's top bar. */
    static ImageButton button(Activity a, String key, String[] codes, String[] labels, String def, Runnable after) {
        ImageButton b = Ui.icon(a, R.drawable.ic_sort, 44, Ui.TEXT);
        b.setContentDescription("Sort");
        b.setOnClickListener(v -> choose(a, key, codes, labels, def, after));
        return b;
    }

    static void choose(Activity a, String key, String[] codes, String[] labels, String def, Runnable after) {
        String cur = get(a, key, def);
        int sel = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(cur)) sel = i;
        new AlertDialog.Builder(a).setTitle("Sort this list")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    prefs(a).edit().putString(key, codes[which]).apply();
                    d.dismiss();
                    Toast.makeText(a, "Sorted: " + labels[which], Toast.LENGTH_SHORT).show();
                    after.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static List<Library.Song> songs(Context c, String key, String def, List<Library.Song> in) {
        List<Library.Song> out = new ArrayList<>(in);
        String mode = get(c, key, def);
        Comparator<Library.Song> by = null;
        switch (mode) {
            case "title": by = (x, y) -> cmp(x.title, y.title); break;
            case "titleDesc": by = (x, y) -> cmp(y.title, x.title); break;
            case "artist": by = (x, y) -> { int r = cmpLast(x.artist, y.artist); return r != 0 ? r : cmp(x.title, y.title); }; break;
            case "album": by = (x, y) -> { int r = cmpLast(x.album, y.album); return r != 0 ? r : cmp(x.title, y.title); }; break;
            case "newest": by = (x, y) -> Long.compare(y.added, x.added); break;
            case "oldest": by = (x, y) -> Long.compare(x.added, y.added); break;
            case "modified": by = (x, y) -> Long.compare(modified(y), modified(x)); break;
            case "longest": by = (x, y) -> Long.compare(y.duration, x.duration); break;
            case "shortest": by = (x, y) -> Long.compare(x.duration, y.duration); break;
            default: break;
        }
        if (by != null) Collections.sort(out, by);
        return out;
    }

    /** A file's "date modified" (music saved on this phone); other songs use the date they were added. */
    private static long modified(Library.Song s) {
        try {
            if (s.uri != null && s.uri.startsWith("file:")) {
                long m = new java.io.File(android.net.Uri.parse(s.uri).getPath()).lastModified();
                if (m > 0) return m;
            }
        } catch (Exception ignored) {
        }
        return s.added;
    }

    static List<Videos.Video> videos(Context c, String key, List<Videos.Video> in) {
        List<Videos.Video> out = new ArrayList<>(in);
        Comparator<Videos.Video> by = null;
        switch (get(c, key, "newest")) {
            case "name": by = (x, y) -> cmp(x.title, y.title); break;
            case "nameDesc": by = (x, y) -> cmp(y.title, x.title); break;
            case "newest": by = (x, y) -> Long.compare(y.date, x.date); break;
            case "oldest": by = (x, y) -> Long.compare(x.date, y.date); break;
            case "biggest": by = (x, y) -> Long.compare(y.size, x.size); break;
            case "smallest": by = (x, y) -> Long.compare(x.size, y.size); break;
            case "longest": by = (x, y) -> Long.compare(y.duration, x.duration); break;
            default: break;
        }
        if (by != null) Collections.sort(out, by);
        return out;
    }

    /** Video folders, in the Videos page's own sort. */
    static List<Videos.Folder> folders(Context c, List<Videos.Folder> in) {
        List<Videos.Folder> out = new ArrayList<>(in);
        Comparator<Videos.Folder> by;
        switch (get(c, "vfolders", "name")) {
            case "nameDesc": by = (x, y) -> cmp(y.name, x.name); break;
            case "most": by = (x, y) -> Integer.compare(y.videos.size(), x.videos.size()); break;
            case "newest": by = (x, y) -> Long.compare(newest(y), newest(x)); break;
            case "biggest": by = (x, y) -> Long.compare(size(y), size(x)); break;
            default: by = (x, y) -> cmp(x.name, y.name); break;
        }
        Collections.sort(out, by);
        return out;
    }

    private static long newest(Videos.Folder f) {
        long m = 0;
        for (Videos.Video v : f.videos) m = Math.max(m, v.date);
        return m;
    }

    private static long size(Videos.Folder f) {
        long t = 0;
        for (Videos.Video v : f.videos) t += v.size;
        return t;
    }

    static List<Library.YtItem> yt(Context c, Library.Playlist p) {
        List<Library.YtItem> out = new ArrayList<>(p.ytItems);
        Comparator<Library.YtItem> by = null;
        switch (get(c, "yt:" + p.id, "custom")) {
            case "title": by = (x, y) -> cmp(x.title, y.title); break;
            case "titleDesc": by = (x, y) -> cmp(y.title, x.title); break;
            case "artist": by = (x, y) -> { int r = cmpLast(x.author, y.author); return r != 0 ? r : cmp(x.title, y.title); }; break;
            default: break;
        }
        if (by != null) Collections.sort(out, by);
        return out;
    }
}
