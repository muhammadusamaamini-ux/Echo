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
    static final Set<String> AUDIO = new HashSet<>(java.util.Arrays.asList(
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

    /** One Drive folder: its songs and its sub-folders. */
    static final class Node {
        String id, name;
        final List<Library.Song> songs = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
    }

    static final class Folder {
        Node root;
        int songCount;
        boolean complete = true; // false if a sub-folder couldn't be read (then nothing is removed)
        String error;
        String account;          // set for private folders read through Google sign-in
        DriveAuth.NeedsSignIn needsSignIn;
    }

    // ---------- reading a shared folder ----------

    /** Reads a shared folder with all its sub-folders. Call from a background thread. */
    static Folder read(String folderId) {
        Folder f = new Folder();
        try {
            f.root = readNode(folderId, null, 0, new HashSet<>(), f);
            if (f.root == null) {
                f.error = "This folder isn't shared. In Google Drive, share it as \"Anyone with the link\" and try again.";
            } else if (f.songCount == 0) {
                f.error = "No songs were found in this folder or its sub-folders. Make sure it contains audio files "
                        + "(MP3, M4A…) and is shared as \"Anyone with the link\".";
            }
        } catch (Exception e) {
            f.error = "Couldn't open the folder. Check your internet, and that the folder is shared as "
                    + "\"Anyone with the link\".";
        }
        return f;
    }

    private static Node readNode(String folderId, String name, int depth, Set<String> seen, Folder f) throws Exception {
        if (depth > 8 || !seen.add(folderId)) return null;
        String html = get("https://drive.google.com/embeddedfolderview?id=" + folderId);
        if (html == null) {
            if (depth > 0) f.complete = false;
            return null;
        }
        Node n = new Node();
        n.id = folderId;
        n.name = name;
        if (n.name == null) {
            Matcher t = PAGE_TITLE.matcher(html);
            if (t.find()) n.name = unescape(t.group(1)).replace(" - Google Drive", "").trim();
            if (n.name == null || n.name.isEmpty()) n.name = "Google Drive";
        }
        List<String[]> subFolders = new ArrayList<>();
        String[] parts = html.split("class=\"flip-entry\"");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            Matcher id = ENTRY_ID.matcher(p);
            Matcher title = TITLE.matcher(p);
            Matcher href = HREF.matcher(p);
            if (!id.find() || !title.find()) continue;
            String fileId = id.group(1);
            String entryName = unescape(title.group(1)).trim();
            String link = href.find() ? href.group(1) : "";
            if (link.contains("/folders/")) {
                subFolders.add(new String[]{fileId, entryName});
                continue;
            }
            if (!AUDIO.contains(ext(entryName))) continue;
            n.songs.add(song(fileId, entryName, n.name));
            f.songCount++;
        }
        for (String[] sub : subFolders) {
            Node c = readNode(sub[0], sub[1], depth + 1, seen, f);
            if (c != null && (!c.songs.isEmpty() || !c.children.isEmpty())) n.children.add(c);
        }
        return n;
    }

    private static Library.Song song(String fileId, String fileName, String folderName) {
        return song(fileId, fileName, folderName, null);
    }

    static Library.Song song(String fileId, String fileName, String folderName, String account) {
        Library.Song s = new Library.Song();
        s.id = "gd_" + fileId;
        s.source = "drive";
        s.uri = account == null ? streamUrl(fileId) : DriveApi.streamUri(fileId, account);
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
        load(a, folderId, existing, existing == null ? null : existing.driveAccount);
    }

    static void load(Activity a, String folderId, Library.Playlist existing, String account) {
        final Handler main = new Handler(Looper.getMainLooper());
        final TextView msg = Ui.text(a, "Reading the folder…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog wait = new AlertDialog.Builder(a).setTitle("Google Drive").setView(msg)
                .setCancelable(false).show();
        new Thread(() -> {
            final Folder f = account == null ? read(folderId) : DriveApi.readTree(a, account, folderId, null);
            main.post(() -> {
                wait.dismiss();
                if (a.isFinishing()) return;
                if (f.needsSignIn != null) {
                    SignInActivity.run(a, account, () -> load(a, folderId, existing, account));
                    return;
                }
                if (f.error != null) {
                    new AlertDialog.Builder(a).setTitle(existing == null ? "Couldn't add the folder" : "Couldn't sync")
                            .setMessage(f.error).setPositiveButton("OK", null).show();
                    return;
                }
                Library lib = Library.get(a);
                Result r = apply(lib, f, folderId, existing == null ? null : existing.parentId);
                if (a instanceof MainActivity) ((MainActivity) a).refresh();
                if (existing != null) {
                    android.widget.Toast.makeText(a, r.summary("Everything is up to date"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    if (a instanceof PlaylistActivity) ((PlaylistActivity) a).reload();
                } else {
                    a.startActivity(new Intent(a, PlaylistActivity.class).putExtra("id", r.root.id));
                }
            });
        }).start();
    }

    private static void collectIds(Node n, Set<String> out) {
        for (Library.Song s : n.songs) out.add(s.id);
        for (Node c : n.children) collectIds(c, out);
    }

    static final class Result {
        Library.Playlist root;
        int newSongs, removedSongs, newFolders, removedFolders;

        boolean changed() {
            return newSongs + removedSongs + newFolders + removedFolders > 0;
        }

        String summary(String none) {
            if (!changed()) return none;
            List<String> parts = new ArrayList<>();
            if (newSongs > 0) parts.add(newSongs + (newSongs == 1 ? " new song" : " new songs"));
            if (removedSongs > 0) parts.add(removedSongs + " removed");
            if (newFolders > 0) parts.add(newFolders + (newFolders == 1 ? " new folder" : " new folders"));
            if (removedFolders > 0) parts.add(removedFolders + (removedFolders == 1 ? " folder removed" : " folders removed"));
            return android.text.TextUtils.join(", ", parts);
        }

        void add(Result o) {
            newSongs += o.newSongs;
            removedSongs += o.removedSongs;
            newFolders += o.newFolders;
            removedFolders += o.removedFolders;
        }
    }

    /** Makes Echo match what is in Drive now. Must run on the main thread. */
    static Result apply(Library lib, Folder f, String rootId, String parentForNew) {
        Result r = new Result();
        // What Echo had for this Drive folder before
        List<Library.Playlist> oldNodes = new ArrayList<>();
        Set<String> oldSongs = new HashSet<>();
        for (Library.Playlist p : lib.playlists) {
            if (p.driveFolder == null) continue;
            if (rootId.equals(p.driveRoot) || rootId.equals(p.driveFolder)) {
                oldNodes.add(p);
                for (String id : p.songIds) if (id.startsWith("gd_")) oldSongs.add(id);
            }
        }
        Set<String> allSongs = new HashSet<>();
        Set<String> allFolders = new HashSet<>();
        collectIds(f.root, allSongs);
        collectFolders(f.root, allFolders);

        Library.Playlist existingRoot = lib.playlistForDrive(rootId);
        int foldersBefore = lib.playlists.size();
        int songsBefore = lib.songs.size();
        r.root = sync(lib, f.root, existingRoot != null ? existingRoot.parentId : parentForNew, existingRoot, allSongs, rootId);
        r.newFolders = lib.playlists.size() - foldersBefore;
        r.newSongs = lib.songs.size() - songsBefore;

        for (Library.Playlist p : lib.playlists) {
            if (rootId.equals(p.driveRoot)) p.driveAccount = f.account;
        }
        if (f.complete) {
            // Songs deleted from Drive
            List<Library.Song> gone = new ArrayList<>();
            for (String id : oldSongs) {
                Library.Song s = lib.song(id);
                if (s != null && !allSongs.contains(id) && "drive".equals(s.source)) gone.add(s);
            }
            for (Library.Song s : gone) lib.deleteSongQuiet(s);
            r.removedSongs = gone.size();
            // Folders deleted from Drive (your own folders inside them are kept, one level up)
            for (Library.Playlist p : oldNodes) {
                if (p == r.root || allFolders.contains(p.driveFolder)) continue;
                for (Library.Playlist c : lib.children(p.id)) c.parentId = p.parentId;
                lib.playlists.remove(p);
                r.removedFolders++;
            }
        }
        lib.save();
        return r;
    }

    private static void collectFolders(Node n, Set<String> out) {
        out.add(n.id);
        for (Node c : n.children) collectFolders(c, out);
    }

    /**
     * Makes one folder (and its sub-folders) match Drive. Folders you moved somewhere else in Echo stay where
     * you put them; folders that are new in Drive are created inside their Drive parent.
     */
    private static Library.Playlist sync(Library lib, Node n, String parentId, Library.Playlist forced,
                                         Set<String> all, String rootId) {
        Library.Playlist pl = forced != null ? forced : lib.playlistForDrive(n.id);
        if (pl == null) {
            pl = new Library.Playlist();
            pl.id = Library.newId();
            pl.name = n.name;
            pl.driveName = n.name;
            pl.driveFolder = n.id;
            pl.parentId = parentId;
            lib.playlists.add(pl);
        }
        if (pl.driveFolder == null) pl.driveFolder = n.id;
        pl.driveRoot = rootId;
        // Renamed in Drive: follow it, unless you gave the folder your own name in Echo.
        if (n.name != null && !n.name.equals(pl.driveName)) {
            if (pl.driveName == null || pl.driveName.equals(pl.name)) pl.name = n.name;
            pl.driveName = n.name;
        }
        Set<String> here = new HashSet<>();
        for (Library.Song s : n.songs) {
            here.add(s.id);
            Library.Song old = lib.song(s.id);
            if (old == null) {
                lib.addSong(s);
            } else if ("drive".equals(old.source)) { // file renamed in Drive
                old.title = s.title;
                old.artist = s.artist;
                old.album = s.album;
                old.uri = s.uri;
            }
            if (!pl.songIds.contains(s.id)) pl.songIds.add(s.id);
        }
        // Drive songs that now live in another Drive folder are shown there, not here.
        pl.songIds.removeIf(id -> all.contains(id) && !here.contains(id));
        for (Node c : n.children) sync(lib, c, pl.id, null, all, rootId);
        return pl;
    }

    // ---------- automatic sync ----------

    interface SyncDone {
        void onDone(Result total);
    }

    private static boolean syncing;

    /** The Drive folders you imported (each is synced with all its sub-folders). */
    private static List<String[]> roots(Library lib) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>(); // root -> account (or null)
        for (Library.Playlist p : lib.playlists) {
            if (p.driveFolder == null) continue;
            if (p.driveRoot != null) {
                if (!out.containsKey(p.driveRoot) || p.driveAccount != null) out.put(p.driveRoot, p.driveAccount);
            } else {
                Library.Playlist parent = lib.playlist(p.parentId);
                if (parent == null || parent.driveFolder == null) out.put(p.driveFolder, p.driveAccount);
            }
        }
        List<String[]> list = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : out.entrySet()) list.add(new String[]{e.getKey(), e.getValue()});
        return list;
    }

    /** Checks every imported Drive folder for changes. Safe to call from any thread. */
    static void syncAll(android.content.Context ctx, SyncDone done) {
        final android.content.Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (syncing) {
                if (done != null) done.onDone(new Result());
                return;
            }
            final List<String[]> roots = roots(Library.get(app));
            if (roots.isEmpty()) {
                if (done != null) done.onDone(new Result());
                return;
            }
            syncing = true;
            new Thread(() -> {
                final List<Folder> results = new ArrayList<>();
                for (String[] r : roots) {
                    results.add(r[1] == null ? read(r[0]) : DriveApi.readTree(app, r[1], r[0], null));
                }
                main.post(() -> {
                    Result total = new Result();
                    Library lib = Library.get(app);
                    for (int i = 0; i < roots.size(); i++) {
                        Folder f = results.get(i);
                        if (f.error != null || f.root == null) continue; // offline or unshared: change nothing
                        if (lib.playlistForDrive(roots.get(i)[0]) == null) continue; // you deleted it meanwhile
                        total.add(apply(lib, f, roots.get(i)[0], null));
                    }
                    syncing = false;
                    app.getSharedPreferences("drive", android.content.Context.MODE_PRIVATE).edit()
                            .putLong("lastSync", System.currentTimeMillis()).apply();
                    if (done != null) done.onDone(total);
                });
            }).start();
        });
    }

    /** Sync one imported folder now (from its ⋮ menu). */
    static void syncNow(Activity a, Library.Playlist p) {
        Library lib = Library.get(a);
        String root = p.driveRoot != null ? p.driveRoot : p.driveFolder;
        Library.Playlist rootPl = lib.playlistForDrive(root);
        Library.Playlist target = rootPl != null ? rootPl : p;
        load(a, root, target, target.driveAccount != null ? target.driveAccount : p.driveAccount);
    }

    static long lastSync(android.content.Context c) {
        return c.getSharedPreferences("drive", android.content.Context.MODE_PRIVATE).getLong("lastSync", 0);
    }

    /** Runs a Drive check about every hour when there is internet, even when Echo is closed. */
    static void schedule(android.content.Context c) {
        android.app.job.JobScheduler js =
                (android.app.job.JobScheduler) c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(78) != null) return;
        android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(78,
                new android.content.ComponentName(c, DriveSyncJob.class))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60 * 60 * 1000L)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (Exception ignored) {
        }
    }
}
