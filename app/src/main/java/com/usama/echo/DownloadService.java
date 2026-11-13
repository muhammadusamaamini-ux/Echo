package com.usama.echo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads YouTube audio/video in the background, with progress in a notification. One at a time. */
public class DownloadService extends Service {
    private static final String CHANNEL = "downloads";
    private static final int PROGRESS_ID = 4100;
    private static final int CHUNK = 1024 * 1024; // YouTube serves big files fastest in small pieces
    private static final byte[] POST_BODY = {0x78, 0};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private int doneId = 4200;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = builder().setContentTitle("Preparing download…").setProgress(0, 0, true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(PROGRESS_ID, n);
        if (intent == null || intent.getStringExtra("title") == null) {
            if (pending.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        final Job job = new Job(intent);
        pending.incrementAndGet();
        worker.execute(() -> {
            try {
                run(job);
            } finally {
                if (pending.decrementAndGet() == 0) {
                    main.post(() -> {
                        if (pending.get() == 0) {
                            stopForeground(true);
                            stopSelf();
                        }
                    });
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Job {
        final String videoId, title, author, videoUrl, audioUrl;
        final boolean audioOnly;
        final long duration;

        Job(Intent i) {
            videoId = i.getStringExtra("videoId");
            title = i.getStringExtra("title");
            author = i.getStringExtra("author");
            videoUrl = i.getStringExtra("videoUrl");
            audioUrl = i.getStringExtra("audioUrl");
            audioOnly = i.getBooleanExtra("audioOnly", true);
            duration = i.getLongExtra("duration", 0);
        }
    }

    private Notification.Builder builder() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 41,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(open);
    }

    private long lastShown;

    private void progress(Job job, String step, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastShown < 700 && done < total) return;
        lastShown = now;
        Notification.Builder b = builder().setContentTitle(job.title).setSubText(pending.get() > 1
                ? (pending.get() - 1) + " more waiting" : null);
        if (total > 0) {
            int pct = (int) (done * 100 / total);
            b.setContentText(step + "  " + pct + "%  ·  " + mb(done) + " of " + mb(total)).setProgress(100, pct, false);
        } else {
            b.setContentText(step).setProgress(0, 0, true);
        }
        nm.notify(PROGRESS_ID, b.build());
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    // ---------- the work ----------

    private void run(Job job) {
        File dir = new File(getCacheDir(), "dl");
        dir.mkdirs();
        File a = new File(dir, "a_" + System.nanoTime());
        File v = new File(dir, "v_" + System.nanoTime());
        File out = new File(dir, "o_" + System.nanoTime() + (job.audioOnly ? ".m4a" : ".mp4"));
        try {
            if (job.audioOnly) {
                fetch(job, "Downloading audio", job.audioUrl, a);
                progress(job, "Finishing…", 0, 0);
                if (!remux(null, a, out)) copy(a, out);
                Uri saved = save(out, job, true);
                addToLibrary(saved, job);
                finished(job, saved, true, null);
            } else {
                boolean join = job.audioUrl != null;
                fetch(job, join ? "Downloading video" : "Downloading", job.videoUrl, v);
                if (join) {
                    fetch(job, "Downloading sound", job.audioUrl, a);
                    progress(job, "Joining video and sound…", 0, 0);
                    if (!remux(v, a, out)) throw new IOException("Couldn't join video and sound");
                } else if (!remux(v, null, out)) {
                    copy(v, out);
                }
                Uri saved = save(out, job, false);
                finished(job, saved, false, null);
            }
        } catch (Throwable e) {
            finished(job, null, job.audioOnly, e);
        } finally {
            a.delete();
            v.delete();
            out.delete();
        }
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("/(\\d+)\\s*$");

    /** Downloads a YouTube stream in 1 MB pieces (retrying each piece a few times). */
    private void fetch(Job job, String step, String url, File to) throws IOException {
        long done = 0, total = -1;
        try (OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            while (total < 0 || done < total) {
                int tries = 0;
                while (true) {
                    HttpURLConnection con = null;
                    long start = done;
                    try {
                        con = open(url, done, done + CHUNK - 1);
                        int code = con.getResponseCode();
                        if (code == 200) {
                            total = con.getContentLength() > 0 ? con.getContentLengthLong() : -1;
                        } else if (code == 206) {
                            String cr = con.getHeaderField("Content-Range");
                            Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr);
                            if (m != null && m.find()) total = Long.parseLong(m.group(1));
                        } else {
                            throw new IOException("YouTube answered " + code);
                        }
                        try (InputStream in = con.getInputStream()) {
                            int r;
                            while ((r = in.read(buf)) > 0) {
                                os.write(buf, 0, r);
                                done += r;
                                progress(job, step, done, total);
                            }
                        }
                        if (code == 200 || total < 0) total = done; // whole file came in one go
                        break;
                    } catch (IOException e) {
                        if (done != start) throw e; // part of a piece was written; can't safely retry
                        if (++tries >= 4) throw e;
                        try {
                            Thread.sleep(1500L * tries);
                        } catch (InterruptedException ie) {
                            throw new IOException("Cancelled");
                        }
                    } finally {
                        if (con != null) con.disconnect();
                    }
                }
            }
        }
        if (done == 0) throw new IOException("Nothing was downloaded");
    }

    private static HttpURLConnection open(String url, long from, long to) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            con.setRequestProperty("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null));
        } else {
            con.setRequestProperty("User-Agent", Net.USER_AGENT);
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            con.setRequestProperty("Origin", "https://www.youtube.com");
            con.setRequestProperty("Referer", "https://www.youtube.com/");
            con.setRequestProperty("Sec-Fetch-Dest", "empty");
            con.setRequestProperty("Sec-Fetch-Mode", "cors");
            con.setRequestProperty("Sec-Fetch-Site", "cross-site");
        }
        con.setRequestProperty("Range", "bytes=" + from + "-" + to);
        con.setRequestProperty("Accept-Encoding", "identity");
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setFixedLengthStreamingMode(POST_BODY.length);
        try (OutputStream os = con.getOutputStream()) {
            os.write(POST_BODY);
        }
        return con;
    }

    /** Rewrites YouTube's streaming files as normal MP4/M4A files (and joins video + sound). */
    private static boolean remux(File video, File audio, File out) {
        MediaMuxer mux = null;
        MediaExtractor ve = null, ae = null;
        try {
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = -1, at = -1;
            int bufSize = 1 << 20;
            if (video != null) {
                ve = new MediaExtractor();
                ve.setDataSource(video.getPath());
                MediaFormat f = selectTrack(ve, "video/");
                if (f == null) return false;
                vt = mux.addTrack(f);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = Math.max(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (audio != null) {
                ae = new MediaExtractor();
                ae.setDataSource(audio.getPath());
                MediaFormat f = selectTrack(ae, "audio/");
                if (f == null) return false;
                at = mux.addTrack(f);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = Math.max(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(Math.max(bufSize, 4 << 20));
            if (ve != null) copySamples(ve, mux, vt, buf);
            if (ae != null) copySamples(ae, mux, at, buf);
            mux.stop();
            return out.length() > 0;
        } catch (Throwable e) {
            out.delete();
            return false;
        } finally {
            try {
                if (mux != null) mux.release();
            } catch (Throwable ignored) {
            }
            if (ve != null) ve.release();
            if (ae != null) ae.release();
        }
    }

    private static MediaFormat selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                ex.selectTrack(i);
                return f;
            }
        }
        return null;
    }

    private static void copySamples(MediaExtractor ex, MediaMuxer mux, int track, ByteBuffer buf) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buf.clear();
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0, ex.getSampleTime());
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            mux.writeSampleData(track, buf, info);
            ex.advance();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
        }
    }

    private static String fileName(String title) {
        String n = title == null ? "YouTube" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "YouTube";
        if (n.length() > 100) n = n.substring(0, 100).trim();
        return n;
    }

    /** Puts the file in Music › Echo (audio) or Movies › Echo (video), where any app can see it. */
    private Uri save(File file, Job job, boolean audio) throws IOException {
        String name = fileName(job.title) + (audio ? ".m4a" : ".mp4");
        String mime = audio ? "audio/mp4" : "video/mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            Uri coll = audio ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/Echo");
            cv.put(MediaStore.MediaColumns.TITLE, job.title);
            if (audio) {
                cv.put(MediaStore.Audio.Media.ARTIST, job.author);
                cv.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            }
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(coll, cv);
            if (uri == null) throw new IOException("Couldn't create the file");
            try (InputStream in = new FileInputStream(file); OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("Couldn't write the file");
                byte[] b = new byte[65536];
                int r;
                while ((r = in.read(b)) > 0) os.write(b, 0, r);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), "Echo");
        dir.mkdirs();
        File dest = new File(dir, name);
        for (int i = 2; dest.exists(); i++) dest = new File(dir, fileName(job.title) + " (" + i + ")" + (audio ? ".m4a" : ".mp4"));
        copy(file, dest);
        MediaScannerConnection.scanFile(this, new String[]{dest.getPath()}, new String[]{mime}, null);
        return Uri.fromFile(dest);
    }

    /** Downloaded audio shows up in Echo's Songs straight away. */
    private void addToLibrary(Uri uri, Job job) {
        final Library.Song s = new Library.Song();
        s.id = "content".equals(uri.getScheme()) ? "dev_" + ContentUris.parseId(uri) : "yt_" + job.videoId;
        s.source = "device";
        s.uri = uri.toString();
        s.title = job.title;
        s.artist = job.author;
        s.album = "YouTube";
        s.duration = job.duration;
        s.added = System.currentTimeMillis();
        final Context app = getApplicationContext();
        main.post(() -> {
            Library lib = Library.get(app);
            lib.addSong(s);
            lib.save();
        });
    }

    private void finished(Job job, Uri saved, boolean audio, Throwable error) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setAutoCancel(true).setContentTitle(job.title);
        if (error == null && saved != null) {
            Intent open;
            if (audio) {
                open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            } else {
                open = new Intent(Intent.ACTION_VIEW).setDataAndType(saved, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText(audio ? "Saved. It's in your Echo songs and in Music › Echo."
                            : "Saved to Movies › Echo. Tap to watch.")
                    .setContentIntent(PendingIntent.getActivity(this, doneId, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            String why = error == null || error.getMessage() == null ? "" : " (" + error.getMessage() + ")";
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Download failed. Try again.")
                    .setStyle(new Notification.BigTextStyle().bigText("Download failed" + why
                            + ". Try again in a little while. If it keeps failing, YouTube may have changed "
                            + "something, and a newer Echo build will fix it."));
        }
        nm.notify(doneId++, b.build());
    }
}
