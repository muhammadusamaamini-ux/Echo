package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Makes videos that every phone and WhatsApp can handle:
 *  - "Phone-friendly copy": a big video (4K, 2160×3840, HEVC…) becomes a normal 1080p H.264 MP4.
 *  - "WhatsApp Status": 720p H.264 MP4, small, cut into status-length parts (30, 60 or 90 seconds).
 * The original is never changed; the new files go to Movies › Echo.
 */
final class PhoneReady {
    private PhoneReady() {}

    /** Asks which of the two the user wants. */
    static void offer(Activity a, Uri uri, String title) {
        String[] items = {"Phone-friendly copy (1080p)", "For WhatsApp Status (cut into parts)"};
        new AlertDialog.Builder(a).setTitle("Make it play everywhere")
                .setItems(items, (d, w) -> {
                    if (w == 0) phoneCopy(a, uri, title);
                    else status(a, uri, title);
                }).show();
    }

    /** Shown when the player can't play a video on this phone. */
    static void offerAfterError(Activity a, Uri uri, String title) {
        new AlertDialog.Builder(a).setTitle("Can't play this video on this phone")
                .setMessage("It may be too big for this phone (for example 4K or 2160×3840) or in a format it "
                        + "doesn't support.\n\nEcho can make a phone-friendly 1080p copy that plays on any phone "
                        + "and on WhatsApp. Your original stays as it is.")
                .setPositiveButton("Make a copy", (d, w) -> phoneCopy(a, uri, title))
                .setNeutralButton("WhatsApp Status", (d, w) -> status(a, uri, title))
                .setNegativeButton("Not now", null).show();
    }

    static void phoneCopy(Activity a, Uri uri, String title) {
        start(a, uri, title, false, 0);
    }

    static void status(Activity a, Uri uri, String title) {
        final int[] secs = {60, 90, 30};
        String[] names = {"60 seconds per part (works for everyone)",
                "90 seconds per part (newer WhatsApp)", "30 seconds per part"};
        new AlertDialog.Builder(a).setTitle("WhatsApp Status: length of each part")
                .setItems(names, (d, w) -> start(a, uri, title, true, secs[w])).show();
    }

    // ---------- the work ----------

    private static final class Info {
        int width, height; // as shown (rotation applied)
        long durationMs;
    }

    private static Info read(Activity a, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(a, uri);
            Info i = new Info();
            i.width = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            i.height = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (Math.abs(rot) % 180 == 90) {
                int t = i.width;
                i.width = i.height;
                i.height = t;
            }
            i.durationMs = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            return i;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static int num(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Scales so the short side is at most `shortSide`, keeping the shape; even numbers for the encoder. */
    private static int[] fit(int w, int h, int shortSide) {
        if (w <= 0 || h <= 0) return null;
        int s = Math.min(w, h);
        if (s <= shortSide) return new int[]{even(w), even(h)};
        double k = shortSide / (double) s;
        return new int[]{even((int) Math.round(w * k)), even((int) Math.round(h * k))};
    }

    private static int even(int n) {
        return Math.max(2, n - (n % 2));
    }

    private static void start(Activity a, Uri uri, String title, boolean forStatus, int partSec) {
        if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
            android.widget.Toast.makeText(a, "Allow storage access, then try again", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            Info info = read(a, uri);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (info == null || info.width <= 0 || info.height <= 0) {
                    error(a, "Echo couldn't read this video's details. The file may be damaged, or it isn't a video.");
                    return;
                }
                new Job(a, uri, title, info, forStatus, partSec).begin();
            });
        }).start();
    }

    /** One conversion, possibly in several parts (for Status). */
    private static final class Job {
        final Activity a;
        final Uri uri;
        final String base;
        final Info info;
        final boolean forStatus;
        final List<long[]> parts = new ArrayList<>();
        final int[] size;
        final Handler main = new Handler(Looper.getMainLooper());
        final List<Uri> saved = new ArrayList<>();
        Transformer transformer;
        AlertDialog dlg;
        TextView msg;
        ProgressBar bar;
        boolean cancelled;
        int index;

        Job(Activity a, Uri uri, String title, Info info, boolean forStatus, int partSec) {
            this.a = a;
            this.uri = uri;
            this.info = info;
            this.forStatus = forStatus;
            String t = title == null ? "video" : title.replaceAll("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (t.length() > 40) t = t.substring(0, 40).trim();
            this.base = (t.isEmpty() ? "video" : t) + (forStatus ? "_status_" : "_phone_")
                    + new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
            this.size = fit(info.width, info.height, forStatus ? 720 : 1080);
            long total = info.durationMs;
            if (forStatus && total > partSec * 1000L + 1500) {
                long step = partSec * 1000L;
                int n = (int) Math.ceil(total / (double) step);
                // spread evenly so the last part isn't a tiny 2-second scrap
                long each = (long) Math.ceil(total / (double) n);
                for (int i = 0; i < n; i++) {
                    long s = i * each;
                    long e = Math.min(total, s + each);
                    if (e - s > 300) parts.add(new long[]{s, i == n - 1 ? -1 : e});
                }
            } else {
                parts.add(new long[]{0, -1});
            }
        }

        void begin() {
            LinearLayout box = Ui.column(a);
            box.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
            String what = forStatus ? "720p for WhatsApp Status" : "1080p phone-friendly";
            TextView from = Ui.text(a, info.width + " × " + info.height + "  →  " + size[0] + " × " + size[1]
                    + "\n" + what + (parts.size() > 1 ? ",  " + parts.size() + " parts" : ""), 13, Ui.SUB, false);
            box.addView(from);
            msg = Ui.text(a, "Preparing…", 15, Ui.TEXT, false);
            msg.setPadding(0, Ui.dp(a, 10), 0, 0);
            box.addView(msg);
            bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            box.addView(bar);
            dlg = new AlertDialog.Builder(a).setTitle(forStatus ? "Making Status videos" : "Making a phone-friendly copy")
                    .setView(box).setCancelable(false)
                    .setNegativeButton("Cancel", (d, w) -> {
                        cancelled = true;
                        if (transformer != null) transformer.cancel();
                        transformer = null;
                        main.removeCallbacksAndMessages(null);
                    }).show();
            next();
        }

        private File temp() {
            return new File(a.getCacheDir(), "phoneready.mp4");
        }

        void next() {
            if (cancelled) return;
            if (index >= parts.size()) {
                finish();
                return;
            }
            long[] p = parts.get(index);
            MediaItem.Builder mb = new MediaItem.Builder().setUri(uri);
            if (parts.size() > 1) {
                MediaItem.ClippingConfiguration.Builder cb = new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(p[0]);
                if (p[1] > 0) cb.setEndPositionMs(p[1]);
                mb.setClippingConfiguration(cb.build());
            }
            List<Effect> video = new ArrayList<>();
            if (forStatus) video.add(FrameDropEffect.createDefaultFrameDropEffect(30f));
            video.add(Presentation.createForWidthAndHeight(size[0], size[1], Presentation.LAYOUT_SCALE_TO_FIT));
            EditedMediaItem item = new EditedMediaItem.Builder(mb.build())
                    .setEffects(new Effects(new ArrayList<AudioProcessor>(), video))
                    .build();

            int bitrate = forStatus ? 1_800_000 : (size[0] * size[1] > 1280 * 720 ? 8_000_000 : 4_000_000);
            DefaultEncoderFactory enc = new DefaultEncoderFactory.Builder(a)
                    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .build();
            final File out = temp();
            if (out.exists()) out.delete();
            transformer = new Transformer.Builder(a)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            transformer = null;
                            save(out);
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException e) {
                            transformer = null;
                            fail(e);
                        }
                    })
                    .build();
            try {
                transformer.start(item, out.getAbsolutePath());
            } catch (Exception e) {
                transformer = null;
                dlg.dismiss();
                error(a, "Couldn't start: " + e.getMessage());
                return;
            }
            final ProgressHolder holder = new ProgressHolder();
            final String part = parts.size() > 1 ? "Part " + (index + 1) + " of " + parts.size() + "  ·  " : "";
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (transformer == null) return;
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        int overall = (int) ((index * 100L + holder.progress) / parts.size());
                        bar.setProgress(overall);
                        msg.setText(part + holder.progress + "%  ·  keep Echo open");
                    } else {
                        msg.setText(part + "Working…  keep Echo open");
                    }
                    main.postDelayed(this, 400);
                }
            });
        }

        void save(File out) {
            msg.setText("Saving…");
            final String name = base + (parts.size() > 1 ? "_part" + (index + 1) : "") + ".mp4";
            new Thread(() -> {
                Uri u = null;
                String err = null;
                try {
                    u = Gallery.save(a, out, name, "video/mp4", Environment.DIRECTORY_MOVIES);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                out.delete();
                final Uri result = u;
                final String error = err;
                main.post(() -> {
                    if (cancelled) return;
                    if (result == null) {
                        dlg.dismiss();
                        error(a, "Couldn't save the file" + (error == null ? "." : ": " + error));
                        return;
                    }
                    saved.add(result);
                    index++;
                    next();
                });
            }).start();
        }

        void fail(ExportException e) {
            main.removeCallbacksAndMessages(null);
            dlg.dismiss();
            temp().delete();
            String code = e.getErrorCodeName();
            boolean decode = e.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
                    || e.errorCode == ExportException.ERROR_CODE_DECODING_FAILED
                    || e.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
            String m = decode
                    ? "This phone's video chip can't open this file at all (" + code + "), so it can't convert it "
                    + "either.\n\nOpen the video in Echo on your PC: it plays every format and can make a normal MP4 copy."
                    : "The conversion didn't work on this phone (" + code + ").";
            if (!saved.isEmpty()) m += "\n\n" + saved.size() + " part(s) were already saved to Movies › Echo.";
            error(a, m);
        }

        void finish() {
            main.removeCallbacksAndMessages(null);
            dlg.dismiss();
            if (saved.isEmpty()) return;
            String m = forStatus
                    ? (saved.size() == 1 ? "Your Status video is ready" : "Your " + saved.size() + " Status parts are ready")
                    + " in Movies › Echo.\n\nIn WhatsApp: Updates › Status ＋ › pick "
                    + (saved.size() == 1 ? "it." : "them in order (part 1, 2, 3…). WhatsApp posts them one after another.")
                    : "Saved to Movies › Echo as a normal 1080p MP4. It plays on any phone and can be shared on WhatsApp.";
            AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Done").setMessage(m)
                    .setNegativeButton("Close", null);
            if (forStatus) {
                b.setPositiveButton("Open WhatsApp", (d, w) -> share(true));
                b.setNeutralButton("Share", (d, w) -> share(false));
            } else {
                b.setPositiveButton("Play", (d, w) -> a.startActivity(new Intent(a, VideoPlayerActivity.class)
                        .setData(saved.get(0)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
                b.setNeutralButton("Share", (d, w) -> share(false));
            }
            b.show();
        }

        /** Sends the finished file(s); whatsApp = straight to WhatsApp if it's installed. */
        void share(boolean whatsApp) {
            Intent s;
            if (saved.size() == 1) {
                s = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, saved.get(0));
            } else {
                s = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/mp4")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(saved));
            }
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (whatsApp) {
                for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
                    try {
                        a.startActivity(new Intent(s).setPackage(pkg));
                        return;
                    } catch (Exception ignored) { } // not installed: try the next, then the normal share list
                }
            }
            a.startActivity(Intent.createChooser(s, "Share"));
        }
    }

    private static void error(Activity a, String m) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a).setTitle("Make it play everywhere").setMessage(m)
                .setPositiveButton("OK", null).show();
    }
}
