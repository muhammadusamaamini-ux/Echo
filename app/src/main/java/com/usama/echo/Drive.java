package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays music straight from a Google Drive folder, without downloading it.
 * The folder must be shared as "Anyone with the link".
 */
final class Drive {
    private Drive() {}

    private static final Pattern FOLDER = Pattern.compile("(?:/folders/|[?&]id=)([A-Za-z0-9_-]{10,})");
    private static final Pattern ENTRY_ID = Pattern.compile("id=\"entry-([A-Za-z0-9_-]+)\"");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile("class=\"flip-entry-title\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern PAGE_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Set<String> AUDIO = new HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "amr", "3gp", "mka", "weba"));

    static String folderId(String text) {
        if (text == null) return null;
        Matcher m = FOLDER.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** The link Echo streams a Drive file from. */
    static String streamUrl(String fileId) {
        return "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
    }

    static final class Folder {
        String name;
        final List<Library.Song> songs = new ArrayList<>();
        String error;
    }

    // ---------- reading a shared folder ----------

    /** Lists the songs in a shared folder and its sub-folders. Call from a background thread. */
    static Folder read(String folderId) {
        Folder f = new Folder();
        try {
            readInto(folderId, f, 0, new HashSet<>());
            if (f.songs.isEmpty() && f.error == null) {
                f.error = "No songs were found in this folder. Make sure it contains audio files (MP3, M4A…) "
                        + "and is shared as \"Anyone with the link\".";
            }
        } catch (Exception e) {
            f.error = "Couldn't open the folder. Check your internet, and that the folder is shared as "
                    + "\"Anyone with the link\".";
        }
        return f;
    }

    private static void readInto(String folderId, Folder f, int depth, Set<String> seen) throws Exception {
        if (depth > 3 || !seen.add(folderId)) return;
        String html = get("https://drive.google.com/embeddedfolderview?id=" + folderId);
        if (html == null) {
            if (depth == 0) f.error = "This folder isn't shared. In Google Drive, share it as \"Anyone with the link\" and try again.";
            return;
        }
        if (depth == 0) {
            Matcher t = PAGE_TITLE.matcher(html);
            if (t.find()) f.name = unescape(t.group(1)).replace(" - Google Drive", "").trim();
        }
        List<String> subFolders = new ArrayList<>();
        String[] parts = html.split("class=\"flip-entry\"");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            Matcher id = ENTRY_ID.matcher(p);
            Matcher title = TITLE.matcher(p);
            Matcher href = HREF.matcher(p);
            if (!id.find() || !title.find()) continue;
            String fileId = id.group(1);
            String name = unescape(title.group(1)).trim();
            String link = href.find() ? href.group(1) : "";
            if (link.contains("/folders/")) {
                subFolders.add(fileId);
                continue;
            }
            String ext = ext(name);
            if (!AUDIO.contains(ext)) continue;
            f.songs.add(song(fileId, name, f.name));
        }
        for (String sub : subFolders) readInto(sub, f, depth + 1, seen);
    }

    private static Library.Song song(String fileId, String fileName, String folderName) {
        Library.Song s = new Library.Song();
        s.id = "gd_" + fileId;
        s.source = "drive";
        s.uri = streamUrl(fileId);
        s.added = System.currentTimeMillis();
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        base = base.replaceAll("^\\d{1,3}[ ._-]+", "").replace('_', ' ').trim(); // "01 - Song" -> "Song"
        int dash = base.indexOf(" - ");
        if (dash > 0) {
            s.artist = base.substring(0, dash).trim();
            s.title = base.substring(dash + 3).trim();
        } else {
            s.artist = "";
            s.title = base;
        }
        if (s.title.isEmpty()) s.title = fileName;
        s.album = folderName == null ? "" : folderName;
        return s;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    private static String unescape(String s) {
        return Html.fromHtml(s).toString();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        con.setRequestProperty("Accept-Language", "en");
        try {
            int code = con.getResponseCode();
            if (code != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            }
            String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            // A sign-in page means the folder is private.
            if (html.contains("accounts.google.com/ServiceLogin") && !html.contains("flip-entry")) return null;
            return html;
        } finally {
            con.disconnect();
        }
    }

    // ---------- screens ----------

    static void askLink(Activity a, String prefill) {
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(Ui.text(a, "Paste the link of a Google Drive folder with your songs. They play online, "
                + "nothing is downloaded.\n\nIn the Drive app: on the folder tap ⋮ → Share → Manage access → "
                + "General access: \"Anyone with the link\" → Copy link.", 14, Ui.SUB, false));
        final EditText link = new EditText(a);
        link.setHint("https://drive.google.com/drive/folders/…");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (prefill != null) link.setText(prefill);
        box.addView(link);
        new AlertDialog.Builder(a)
                .setTitle("Google Drive folder")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> {
                    String id = folderId(link.getText().toString());
                    if (id == null) {
                        new AlertDialog.Builder(a).setMessage("That doesn't look like a Drive folder link. "
                                + "It should contain \"/folders/\".").setPositiveButton("OK", null).show();
                        return;
                    }
                    load(a, id, null);
                })
                .show();
    }

    /** Reads the folder and makes (or refreshes) its playlist. */
    static void load(Activity a, String folderId, Library.Playlist existing) {
        final Handler main = new Handler(Looper.getMainLooper());
        final TextView msg = Ui.text(a, "Reading the folder…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog wait = new AlertDialog.Builder(a).setTitle("Google Drive").setView(msg)
                .setCancelable(false).show();
        new Thread(() -> {
            final Folder f = read(folderId);
            main.post(() -> {
                wait.dismiss();
                if (a.isFinishing()) return;
                if (f.error != null) {
                    new AlertDialog.Builder(a).setTitle("Couldn't add the folder").setMessage(f.error)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                Library lib = Library.get(a);
                Library.Playlist pl = existing;
                if (pl == null) {
                    pl = lib.playlistForDrive(folderId);
                }
                int before = 0;
                if (pl == null) {
                    pl = new Library.Playlist();
                    pl.id = Library.newId();
                    pl.name = f.name == null || f.name.isEmpty() ? "Google Drive" : f.name;
                    pl.driveFolder = folderId;
                    lib.playlists.add(pl);
                } else {
                    before = pl.songIds.size();
                }
                for (Library.Song s : f.songs) {
                    Library.Song old = lib.song(s.id);
                    if (old == null) lib.addSong(s);
                    if (!pl.songIds.contains(s.id)) pl.songIds.add(s.id);
                }
                lib.save();
                if (a instanceof MainActivity) ((MainActivity) a).refresh();
                int added = pl.songIds.size() - before;
                if (existing != null) {
                    android.widget.Toast.makeText(a, added == 0 ? "No new songs in the folder"
                            : added + " new songs added", android.widget.Toast.LENGTH_SHORT).show();
                    if (a instanceof PlaylistActivity) ((PlaylistActivity) a).reload();
                } else {
                    a.startActivity(new Intent(a, PlaylistActivity.class).putExtra("id", pl.id));
                }
            });
        }).start();
    }
}
