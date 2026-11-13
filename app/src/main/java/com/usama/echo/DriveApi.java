package com.usama.echo;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads your private Google Drive (after you sign in). Call everything from a background thread. */
final class DriveApi {
    private DriveApi() {}

    static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String SHORTCUT = "application/vnd.google-apps.shortcut";
    private static final String FILES = "https://www.googleapis.com/drive/v3/files";

    static final class Item {
        String id, name, mime;
        boolean folder, audio;
    }

    /** Thrown when Drive says no (e.g. 403) so the caller can show a message. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("Google Drive error " + code);
            this.code = code;
        }
    }

    static List<Item> children(Context c, String account, String folderId) throws Exception {
        return query(c, account, "'" + folderId + "' in parents and trashed=false");
    }

    static List<Item> sharedWithMe(Context c, String account) throws Exception {
        List<Item> all = query(c, account, "sharedWithMe=true and trashed=false");
        List<Item> out = new ArrayList<>();
        for (Item i : all) if (i.folder || i.audio) out.add(i);
        return out;
    }

    private static List<Item> query(Context c, String account, String q) throws Exception {
        List<Item> out = new ArrayList<>();
        String page = null;
        int guard = 0;
        do {
            String url = FILES + "?q=" + Uri.encode(q)
                    + "&fields=" + Uri.encode("nextPageToken,files(id,name,mimeType,shortcutDetails)")
                    + "&pageSize=1000&orderBy=folder,name_natural&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (page != null ? "&pageToken=" + Uri.encode(page) : "");
            JSONObject j = new JSONObject(get(c, account, url));
            JSONArray files = j.optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) {
                JSONObject o = files.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id");
                it.name = o.optString("name", "");
                it.mime = o.optString("mimeType", "");
                if (SHORTCUT.equals(it.mime)) {
                    JSONObject sd = o.optJSONObject("shortcutDetails");
                    if (sd == null) continue;
                    it.id = sd.optString("targetId", it.id);
                    it.mime = sd.optString("targetMimeType", "");
                }
                it.folder = FOLDER.equals(it.mime);
                it.audio = !it.folder && (it.mime.startsWith("audio/") || isAudioName(it.name));
                out.add(it);
            }
            page = j.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null && ++guard < 50);
        return out;
    }

    static boolean isAudioName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return Drive.AUDIO.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String name(Context c, String account, String id) throws Exception {
        JSONObject j = new JSONObject(get(c, account, FILES + "/" + id + "?fields=name&supportsAllDrives=true"));
        return j.optString("name", "Google Drive");
    }

    /** Reads a folder with all its sub-folders, keeping empty folders. */
    static Drive.Folder readTree(Context c, String account, String folderId, String name) {
        Drive.Folder f = new Drive.Folder();
        f.account = account;
        try {
            if (name == null) name = name(c, account, folderId);
            f.root = node(c, account, folderId, name, 0, new HashSet<>(), f);
        } catch (DriveAuth.NeedsSignIn e) {
            f.error = "Google needs you to sign in again.";
            f.needsSignIn = e;
        } catch (HttpError e) {
            f.error = e.code == 404 ? "That folder can't be found any more in this Google account."
                    : "Google Drive answered with error " + e.code + ".";
        } catch (Exception e) {
            f.error = DriveAuth.explain(e);
            if (e instanceof IOException) f.error = "Couldn't reach Google Drive. Check your internet and try again.";
        }
        return f;
    }

    private static Drive.Node node(Context c, String account, String id, String name, int depth, Set<String> seen,
                                   Drive.Folder f) throws Exception {
        Drive.Node n = new Drive.Node();
        n.id = id;
        n.name = name;
        if (depth > 12 || !seen.add(id)) return n;
        List<Item> items = children(c, account, id);
        for (Item it : items) {
            if (it.folder) {
                try {
                    n.children.add(node(c, account, it.id, it.name, depth + 1, seen, f));
                } catch (DriveAuth.NeedsSignIn e) {
                    throw e;
                } catch (Exception e) {
                    f.complete = false; // a sub-folder failed: add what we can, remove nothing
                }
            } else if (it.audio) {
                n.songs.add(Drive.song(it.id, it.name, name, account));
                f.songCount++;
            }
        }
        return n;
    }

    private static String get(Context c, String account, String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = DriveAuth.token(c, account);
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("Authorization", "Bearer " + token);
            try {
                int code = con.getResponseCode();
                if (code == 401 && attempt == 0) {
                    DriveAuth.forget(account); // token expired: get a new one and retry
                    continue;
                }
                if (code != 200) throw new HttpError(code);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = con.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                con.disconnect();
            }
        }
        throw new HttpError(401);
    }

    /** The address Echo plays a private Drive file from (turned into a signed request by the player). */
    static String streamUri(String fileId, String account) {
        return "gdrive:///" + fileId + "?a=" + Uri.encode(account);
    }

    static String mediaUrl(String fileId) {
        return FILES + "/" + fileId + "?alt=media&supportsAllDrives=true";
    }
}
