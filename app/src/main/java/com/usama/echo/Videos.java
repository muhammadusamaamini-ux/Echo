package com.usama.echo;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The videos on this phone, grouped by folder (like VLC). */
final class Videos {
    private Videos() {}

    static final class Video {
        long id;
        String title, folder, uri;
        long duration, size, date;
        int width, height;

        String info() {
            String res = height > 0 ? (height >= 2160 ? "4K" : height >= 1080 ? "1080p" : height >= 720 ? "720p"
                    : height + "p") : "";
            String mb = size > 0 ? String.format(java.util.Locale.US, "%.0f MB", size / 1048576.0) : "";
            StringBuilder sb = new StringBuilder(Ui.time(duration));
            if (!res.isEmpty()) sb.append("  ·  ").append(res);
            if (!mb.isEmpty()) sb.append("  ·  ").append(mb);
            return sb.toString();
        }
    }

    static final class Folder {
        String name;
        final List<Video> videos = new ArrayList<>();
    }

    /** Reads all videos. Call from a background thread. */
    static List<Folder> scan(Context c) {
        Map<String, Folder> map = new LinkedHashMap<>();
        String[] cols = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT
        };
        try (Cursor cur = c.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cur != null) while (cur.moveToNext()) {
                Video v = new Video();
                v.id = cur.getLong(0);
                v.title = cur.getString(1);
                if (v.title == null) v.title = "Video";
                int dot = v.title.lastIndexOf('.');
                if (dot > 0) v.title = v.title.substring(0, dot);
                v.folder = cur.getString(2);
                if (v.folder == null) v.folder = "Other";
                v.duration = cur.getLong(3);
                v.size = cur.getLong(4);
                v.date = cur.getLong(5);
                v.width = cur.getInt(6);
                v.height = cur.getInt(7);
                v.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v.id).toString();
                Folder f = map.get(v.folder);
                if (f == null) {
                    f = new Folder();
                    f.name = v.folder;
                    map.put(v.folder, f);
                }
                f.videos.add(v);
            }
        } catch (Exception ignored) {
        }
        List<Folder> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    // ---------- thumbnails ----------

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Shows a video's thumbnail in an ImageView (loaded in the background). */
    @SuppressWarnings("deprecation")
    static void thumb(Context c, Video v, ImageView iv) {
        final String key = v.uri;
        iv.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        final Context app = c.getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    bmp = app.getContentResolver().loadThumbnail(Uri.parse(key), new Size(320, 180), null);
                } else {
                    bmp = MediaStore.Video.Thumbnails.getThumbnail(app.getContentResolver(), v.id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            if (result != null) cache.put(key, result);
            main.post(() -> {
                if (key.equals(iv.getTag()) && result != null) iv.setImageBitmap(result);
            });
        });
    }
}
