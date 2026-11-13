package com.usama.echo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Download" for a YouTube video: asks audio or video (and quality), then hands it to DownloadService. */
final class Saver {
    private Saver() {}

    private static final Pattern VIDEO = Pattern.compile(
            "(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");

    /** Finds the video ID in a YouTube link (watch, youtu.be, Shorts, YouTube Music). */
    static String videoId(String text) {
        if (text == null) return null;
        Matcher m = VIDEO.matcher(text);
        if (m.find()) return m.group(1);
        String t = text.trim();
        if (t.matches("[A-Za-z0-9_-]{11}")) return t;
        return null;
    }

    /** "+ → Download from YouTube link": paste a link. */
    static void askLink(Activity a, String prefill) {
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(Ui.text(a, "Paste a YouTube video link. You can save it as audio or as video.\n"
                + "Tip: in the YouTube app, tap Share on a video and choose Echo.", 14, Ui.SUB, false));
        final EditText link = new EditText(a);
        link.setHint("https://youtu.be/…");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (prefill != null) link.setText(prefill);
        box.addView(link);
        new AlertDialog.Builder(a)
                .setTitle("Download from YouTube")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next", (d, w) -> {
                    String id = videoId(link.getText().toString());
                    if (id == null) {
                        new AlertDialog.Builder(a).setMessage("That doesn't look like a YouTube video link.")
                                .setPositiveButton("OK", null).show();
                    } else {
                        start(a, id);
                    }
                })
                .show();
    }

    /** One choice in the "Save as" list. */
    private static final class Choice {
        String label;
        boolean audioOnly;
        String videoUrl, audioUrl; // video-only + audio are joined; a "with sound" video has no audioUrl
    }

    /** Reads the video's details, then asks how to save it. */
    static void start(Activity a, String videoId) {
        if (Build.VERSION.SDK_INT < 29
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
            Toast.makeText(a, "Allow storage so Echo can save downloads, then tap Download again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 16), p, Ui.dp(a, 8));
        ProgressBar spin = new ProgressBar(a);
        box.addView(spin);
        box.addView(Ui.text(a, "Getting the video's details…", 14, Ui.SUB, false));
        final boolean[] cancelled = {false};
        final AlertDialog wait = new AlertDialog.Builder(a)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true)
                .show();

        new Thread(() -> {
            StreamInfo info = null;
            String error = null;
            try {
                Net.init();
                info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
            } catch (SignInConfirmNotBotException e) {
                error = "YouTube is asking Echo to prove it's not a robot right now. Wait a while and try again.";
            } catch (AgeRestrictedContentException e) {
                error = "This video is age-restricted, so it can't be downloaded without signing in.";
            } catch (ContentNotAvailableException e) {
                error = "This video isn't available (it may be private, deleted or blocked in your country).";
            } catch (Throwable e) {
                error = "Couldn't read this video. Check your internet and try again.\n\n"
                        + "If it keeps failing, YouTube may have changed something; a newer Echo build will fix it.\n\n("
                        + e.getClass().getSimpleName() + ")";
            }
            final StreamInfo got = info;
            final String err = error;
            main.post(() -> {
                if (cancelled[0] || a.isFinishing()) return;
                wait.dismiss();
                if (got == null) {
                    new AlertDialog.Builder(a).setTitle("Can't download").setMessage(err)
                            .setPositiveButton("OK", null).show();
                } else {
                    choose(a, videoId, got);
                }
            });
        }).start();
    }

    private static void choose(Activity a, String videoId, StreamInfo info) {
        final List<Choice> choices = choices(info);
        if (choices.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Can't download")
                    .setMessage("YouTube didn't offer a downloadable copy of this video. Try again later.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
        final String title = info.getName();
        final String author = YouTube.cleanAuthor(info.getUploaderName());
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    Choice c = choices.get(which);
                    Intent job = new Intent(a, DownloadService.class)
                            .putExtra("videoId", videoId)
                            .putExtra("title", title)
                            .putExtra("author", author)
                            .putExtra("audioOnly", c.audioOnly)
                            .putExtra("videoUrl", c.videoUrl)
                            .putExtra("audioUrl", c.audioUrl)
                            .putExtra("duration", info.getDuration() * 1000L);
                    if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(job);
                    else a.startService(job);
                    Toast.makeText(a, c.audioOnly
                            ? "Downloading audio… It will appear in your Echo songs."
                            : "Downloading video… It will be in your Movies › Echo folder.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean direct(org.schabi.newpipe.extractor.stream.Stream s) {
        return s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && s.getContent() != null;
    }

    /** Best M4A audio, preferring the video's original language track. */
    private static AudioStream bestAudio(List<AudioStream> list) {
        AudioStream best = null;
        int bestScore = -1;
        for (AudioStream s : list) {
            if (!direct(s) || s.getFormat() != MediaFormat.M4A) continue;
            AudioTrackType t = s.getAudioTrackType();
            int score = (t == null || t == AudioTrackType.ORIGINAL ? 1_000_000 : 0) + Math.max(0, s.getAverageBitrate());
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static int height(VideoStream v) {
        if (v.getHeight() > 0) return v.getHeight();
        try {
            return Integer.parseInt(v.getResolution().replaceAll("p.*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean avc(VideoStream v) {
        String c = v.getCodec();
        return c == null || c.toLowerCase(Locale.ROOT).startsWith("avc");
    }

    private static List<Choice> choices(StreamInfo info) {
        List<Choice> out = new ArrayList<>();
        AudioStream audio = bestAudio(info.getAudioStreams());
        if (audio != null) {
            Choice c = new Choice();
            c.audioOnly = true;
            c.audioUrl = audio.getContent();
            int kbps = audio.getAverageBitrate() > 0 ? audio.getAverageBitrate() : 0;
            c.label = "🎵  Audio only (M4A" + (kbps > 0 ? ", " + kbps + " kbps" : "") + ")";
            out.add(c);
        }
        // Highest quality first. Video-only streams are joined with the audio; others already have sound.
        TreeMap<Integer, Choice> video = new TreeMap<>((x, y) -> y - x);
        if (audio != null) {
            for (VideoStream v : info.getVideoOnlyStreams()) {
                int h = height(v);
                if (!direct(v) || v.getFormat() != MediaFormat.MPEG_4 || !avc(v) || h <= 0 || h > 1080) continue;
                if (video.containsKey(h)) continue;
                Choice c = new Choice();
                c.videoUrl = v.getContent();
                c.audioUrl = audio.getContent();
                c.label = "🎬  Video " + h + "p (MP4)";
                video.put(h, c);
            }
        }
        for (VideoStream v : info.getVideoStreams()) {
            int h = height(v);
            if (!direct(v) || v.getFormat() != MediaFormat.MPEG_4 || h <= 0 || video.containsKey(h)) continue;
            Choice c = new Choice();
            c.videoUrl = v.getContent();
            c.label = "🎬  Video " + h + "p (MP4)";
            video.put(h, c);
        }
        out.addAll(video.values());
        return out;
    }
}
