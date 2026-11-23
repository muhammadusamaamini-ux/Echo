package com.usama.echo;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photos on the phone: finding them, thumbnails, loading them the right way up, and collage arrangements. */
final class Photos {
    private Photos() {
    }

    static final class Photo {
        long id, size, date;
        String uri, name, folder;
        int w, h;
    }

    static final class Folder {
        String name;
        final List<Photo> photos = new ArrayList<>();
    }

    /** The list the viewer is showing (set before opening it). */
    static List<Photo> shown = new ArrayList<>();

    /** Reads all photos. Call from a background thread. */
    static List<Folder> scan(Context c) {
        Map<String, Folder> map = new LinkedHashMap<>();
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT};
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (cur != null) while (cur.moveToNext()) {
                Photo p = new Photo();
                p.id = cur.getLong(0);
                p.name = cur.getString(1);
                if (p.name == null) p.name = "Photo";
                int dot = p.name.lastIndexOf('.');
                if (dot > 0) p.name = p.name.substring(0, dot);
                p.folder = cur.getString(2);
                if (p.folder == null) p.folder = "Other";
                p.size = cur.getLong(3);
                long taken = cur.isNull(5) ? 0 : cur.getLong(5);
                p.date = taken > 0 ? taken : cur.getLong(4) * 1000;
                p.w = cur.getInt(6);
                p.h = cur.getInt(7);
                p.uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(p.id)).toString();
                Folder f = map.get(p.folder);
                if (f == null) {
                    f = new Folder();
                    f.name = p.folder;
                    map.put(p.folder, f);
                }
                f.photos.add(p);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(map.values());
    }

    // ---------- thumbnails ----------
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 10)) {
        @Override
        protected int sizeOf(String k, Bitmap v) {
            return v.getByteCount();
        }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    static final Handler main = new Handler(Looper.getMainLooper());

    @SuppressWarnings("deprecation")
    static void thumb(Context c, Photo p, ImageView iv) {
        final String key = p.uri;
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
                if (Build.VERSION.SDK_INT >= 29) bmp = app.getContentResolver().loadThumbnail(Uri.parse(key), new Size(320, 320), null);
                else bmp = MediaStore.Images.Thumbnails.getThumbnail(app.getContentResolver(), p.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
            } catch (Exception ignored) {
            }
            if (bmp == null) bmp = load(app, Uri.parse(key), 320);
            final Bitmap result = bmp;
            if (result != null) cache.put(key, result);
            main.post(() -> {
                if (key.equals(iv.getTag()) && result != null) iv.setImageBitmap(result);
            });
        });
    }

    /** Loads a photo no bigger than maxSide, turned the right way up. Call from a background thread. */
    static Bitmap load(Context c, Uri uri, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o2);
            }
            if (b == null) return null;
            int turn = 0;
            boolean mirror = false;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (or == ExifInterface.ORIENTATION_ROTATE_90 || or == ExifInterface.ORIENTATION_TRANSPOSE) turn = 90;
                else if (or == ExifInterface.ORIENTATION_ROTATE_180 || or == ExifInterface.ORIENTATION_FLIP_VERTICAL) turn = 180;
                else if (or == ExifInterface.ORIENTATION_ROTATE_270 || or == ExifInterface.ORIENTATION_TRANSVERSE) turn = 270;
                mirror = or == ExifInterface.ORIENTATION_FLIP_HORIZONTAL || or == ExifInterface.ORIENTATION_FLIP_VERTICAL
                        || or == ExifInterface.ORIENTATION_TRANSPOSE || or == ExifInterface.ORIENTATION_TRANSVERSE;
            } catch (Exception ignored) {
            }
            float k = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
            if (turn != 0 || mirror || k < 1f) {
                Matrix m = new Matrix();
                if (k < 1f) m.postScale(k, k);
                if (mirror) m.postScale(-1, 1);
                if (turn != 0) m.postRotate(turn);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------- collage arrangements (the same as on the PC) ----------
    /** All arrangements for n photos; each is a list of boxes {x, y, w, h} in 0–1. */
    static List<float[][]> layouts(int n) {
        List<float[][]> out = new ArrayList<>();
        if (n < 1) return out;
        if (n == 1) {
            out.add(new float[][]{{0, 0, 1, 1}});
            return out;
        }
        int rest = n - 1;
        if (rest <= 4) {
            out.add(join(new float[][]{{0, 0, 0.62f, 1}}, stack(rest, 0.62f, 0, 0.38f, 1, true)));
            out.add(join(new float[][]{{0, 0, 1, 0.62f}}, stack(rest, 0, 0.62f, 1, 0.38f, false)));
            out.add(join(stack(rest, 0, 0, 0.38f, 1, true), new float[][]{{0.38f, 0, 0.62f, 1}}));
            out.add(join(stack(rest, 0, 0, 1, 0.38f, false), new float[][]{{0, 0.38f, 1, 0.62f}}));
        } else {
            int a = (rest + 1) / 2, b = rest - a;
            out.add(join(join(new float[][]{{0, 0, 0.55f, 1}}, stack(a, 0.55f, 0, 0.225f, 1, true)), stack(b, 0.775f, 0, 0.225f, 1, true)));
            out.add(join(join(new float[][]{{0, 0, 1, 0.55f}}, stack(a, 0, 0.55f, 1, 0.225f, false)), stack(b, 0, 0.775f, 1, 0.225f, false)));
        }
        if (n == 5) {
            out.add(new float[][]{{0, 0, 0.5f, 0.3f}, {0.5f, 0, 0.5f, 0.3f}, {0, 0.3f, 1, 0.4f}, {0, 0.7f, 0.5f, 0.3f}, {0.5f, 0.7f, 0.5f, 0.3f}});
            out.add(new float[][]{{0, 0, 0.3f, 0.5f}, {0, 0.5f, 0.3f, 0.5f}, {0.3f, 0, 0.4f, 1}, {0.7f, 0, 0.3f, 0.5f}, {0.7f, 0.5f, 0.3f, 0.5f}});
        }
        if (n == 9) out.add(new float[][]{{0.25f, 0.25f, 0.5f, 0.5f}, {0, 0, 0.25f, 0.25f}, {0.25f, 0, 0.5f, 0.25f}, {0.75f, 0, 0.25f, 0.25f},
                {0, 0.25f, 0.25f, 0.5f}, {0.75f, 0.25f, 0.25f, 0.5f}, {0, 0.75f, 0.25f, 0.25f}, {0.25f, 0.75f, 0.5f, 0.25f}, {0.75f, 0.75f, 0.25f, 0.25f}});
        if (n == 7) out.add(new float[][]{{0.25f, 0.25f, 0.5f, 0.5f}, {0, 0, 0.5f, 0.25f}, {0.5f, 0, 0.5f, 0.25f}, {0, 0.25f, 0.25f, 0.5f},
                {0.75f, 0.25f, 0.25f, 0.5f}, {0, 0.75f, 0.5f, 0.25f}, {0.5f, 0.75f, 0.5f, 0.25f}});
        if (n == 3) out.add(new float[][]{{0.5f, 0, 0.5f, 1}, {0, 0.5f, 0.5f, 0.5f}, {0, 0, 0.5f, 0.5f}});
        List<int[]> comps = new ArrayList<>();
        compositions(n, new ArrayList<>(), comps);
        for (int[] comp : comps) {
            if (comp.length == 1 && n > 4) continue;
            out.add(rows(comp, null));
            out.add(flip(rows(comp, null)));
            if (comp.length == 2 && comp[0] < comp[1]) out.add(rows(comp, new float[]{3, 2}));
            if (comp.length == 2 && comp[0] > comp[1]) out.add(rows(comp, new float[]{2, 3}));
        }
        // remove duplicates, keep the first 24
        List<float[][]> uniq = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (float[][] l : out) {
            if (l.length != n) continue;
            List<String> parts = new ArrayList<>();
            for (float[] r : l) parts.add(Math.round(r[0] * 1000) + "," + Math.round(r[1] * 1000) + "," + Math.round(r[2] * 1000) + "," + Math.round(r[3] * 1000));
            Collections.sort(parts);
            if (seen.add(parts.toString())) uniq.add(l);
            if (uniq.size() == 24) break;
        }
        return uniq;
    }

    private static void compositions(int left, List<Integer> acc, List<int[]> out) {
        if (left == 0) {
            int[] a = new int[acc.size()];
            for (int i = 0; i < a.length; i++) a[i] = acc.get(i);
            out.add(a);
            return;
        }
        if (acc.size() >= 4) return;
        for (int k = 1; k <= Math.min(4, left); k++) {
            acc.add(k);
            compositions(left - k, acc, out);
            acc.remove(acc.size() - 1);
        }
    }

    private static float[][] rows(int[] counts, float[] sizes) {
        float total = 0;
        for (int i = 0; i < counts.length; i++) total += sizes == null ? 1 : sizes[i];
        List<float[]> out = new ArrayList<>();
        float y = 0;
        for (int r = 0; r < counts.length; r++) {
            float h = (sizes == null ? 1 : sizes[r]) / total;
            for (int i = 0; i < counts[r]; i++) out.add(new float[]{(float) i / counts[r], y, 1f / counts[r], h});
            y += h;
        }
        return out.toArray(new float[0][]);
    }

    private static float[][] flip(float[][] l) {
        float[][] o = new float[l.length][];
        for (int i = 0; i < l.length; i++) o[i] = new float[]{l[i][1], l[i][0], l[i][3], l[i][2]};
        return o;
    }

    private static float[][] stack(int k, float x0, float y0, float w, float h, boolean vertical) {
        float[][] o = new float[k][];
        for (int i = 0; i < k; i++) o[i] = vertical ? new float[]{x0, y0 + i * h / k, w, h / k} : new float[]{x0 + i * w / k, y0, w / k, h};
        return o;
    }

    private static float[][] join(float[][] a, float[][] b) {
        float[][] o = new float[a.length + b.length][];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
