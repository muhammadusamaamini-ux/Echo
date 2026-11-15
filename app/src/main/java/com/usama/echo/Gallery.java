package com.usama.echo;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves finished files where your gallery and other apps can see them (Movies/Echo, Music/Echo, Pictures/Echo). */
final class Gallery {
    private Gallery() {}

    /** dir is Environment.DIRECTORY_MOVIES, DIRECTORY_MUSIC or DIRECTORY_PICTURES. Call from a background thread. */
    static Uri save(Context c, File src, String displayName, String mime, String dir) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri collection;
            if (Environment.DIRECTORY_MUSIC.equals(dir) || Environment.DIRECTORY_RINGTONES.equals(dir)) {
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else if (Environment.DIRECTORY_PICTURES.equals(dir)) {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/Echo");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri out = c.getContentResolver().insert(collection, v);
            if (out == null) throw new IOException("Couldn't create the file");
            try (InputStream in = new FileInputStream(src);
                 OutputStream os = c.getContentResolver().openOutputStream(out)) {
                if (os == null) throw new IOException("Couldn't write the file");
                copy(in, os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(out, done, null, null);
            return out;
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(dir), "Echo");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Couldn't create " + folder);
        File dest = new File(folder, displayName);
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(dest)) {
            copy(in, os);
        }
        MediaScannerConnection.scanFile(c, new String[]{dest.getPath()}, new String[]{mime}, null);
        return Uri.fromFile(dest);
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
    }
}
