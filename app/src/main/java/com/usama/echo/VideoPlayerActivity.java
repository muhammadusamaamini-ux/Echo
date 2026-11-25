package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen video player, like VLC: swipe left side for brightness, right side for volume,
 * sideways to seek, double-tap to skip 10 s; aspect/zoom, speed, audio and subtitle tracks,
 * subtitle files, lock, picture-in-picture, and "play as audio". Also plays audio files.
 */
public class VideoPlayerActivity extends Activity implements Player.Listener {
    private static final int REQ_SUBS = 11;

    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout top;
    private TextView titleView, hud;
    private ImageButton unlock;
    private boolean locked, inPip, userRotated;
    private int resize;
    private final ArrayList<String> uris = new ArrayList<>();
    private final ArrayList<String> titles = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private SharedPreferences resume;

    // swipe gestures
    private float downX, downY, startBright;
    private int startVol;
    private long startPos, seekTarget = -1;
    private int mode; // 0 none, 1 seek, 2 brightness, 3 volume, 4 two-finger zoom
    // two-finger zoom
    private float zoom = 1f, panX, panY, lastFx, lastFy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        resume = getSharedPreferences("video_resume", MODE_PRIVATE);
        Hub.pauseIfPlaying(); // stop Echo's music while a video plays

        readIntent(getIntent());
        if (uris.isEmpty()) {
            finish();
            return;
        }
        if (isAudio(getIntent())) { // songs play in Echo's music player, which keeps going in the background
            playInMusicPlayer();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = (PlayerView) getLayoutInflater().inflate(R.layout.video_player_view, root, false);
        view.setShowNextButton(uris.size() > 1);
        view.setShowPreviousButton(uris.size() > 1);
        view.setShowSubtitleButton(true);
        view.setControllerShowTimeoutMs(3500);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        top = Ui.row(this);
        top.setBackgroundColor(0x99000000);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 26), Ui.dp(this, 4), Ui.dp(this, 6));
        ImageButton back = Ui.icon(this, R.drawable.ic_back, 44, Color.WHITE);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleView = Ui.line(this, "", 16, Color.WHITE, true);
        titleView.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        top.addView(titleView, Ui.weight());
        addTop(R.drawable.ic_subtitles, v -> pickSubtitles());
        addTop(R.drawable.ic_aspect, v -> nextResize());
        addTop(R.drawable.ic_rotate, v -> rotate());
        if (Build.VERSION.SDK_INT >= 26) addTop(R.drawable.ic_pip, v -> enterPip());
        addTop(R.drawable.ic_more, this::moreMenu);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        hud = Ui.text(this, "", 20, Color.WHITE, true);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        hud.setBackground(Ui.round(0xAA000000, Ui.dp(this, 14)));
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        unlock = Ui.icon(this, R.drawable.ic_lock, 52, Color.WHITE);
        unlock.setBackground(Ui.round(0x88000000, Ui.dp(this, 26)));
        unlock.setVisibility(View.GONE);
        unlock.setOnClickListener(v -> setLocked(false));
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.START | Gravity.CENTER_VERTICAL);
        ulp.leftMargin = Ui.dp(this, 20);
        root.addView(unlock, ulp);
        setContentView(root);

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                top.setVisibility(locked || inPip ? View.GONE : visibility));
        setupGestures();

        // if the phone's main video decoder refuses a file (e.g. 4K), try its other decoders before giving up
        androidx.media3.exoplayer.DefaultRenderersFactory renderers =
                new androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers)
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.addListener(this);
        view.setPlayer(player);
        int start = Math.max(0, Math.min(getIntent().getIntExtra("index", 0), uris.size() - 1));
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) items.add(item(uris.get(i), titles.get(i), null));
        player.setMediaItems(items, start, savedPosition(uris.get(start)));
        player.prepare();
        player.play();
        updateTitle();
    }

    private void addTop(int icon, View.OnClickListener l) {
        ImageButton b = Ui.icon(this, icon, 44, Color.WHITE);
        b.setOnClickListener(l);
        top.addView(b);
    }

    /** Works out what to play: from Echo (a list) or from another app ("Open with"). */
    private void readIntent(Intent i) {
        ArrayList<String> u = i.getStringArrayListExtra("uris");
        ArrayList<String> t = i.getStringArrayListExtra("titles");
        if (u != null && !u.isEmpty()) {
            uris.addAll(u);
            if (t != null && t.size() == u.size()) titles.addAll(t);
            else for (String x : u) titles.add(nameOf(Uri.parse(x)));
        } else if (i.getData() != null) {
            uris.add(i.getData().toString());
            String t1 = i.getStringExtra("title");
            titles.add(t1 != null ? t1 : nameOf(i.getData()));
        }
    }

    private String nameOf(Uri u) {
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) {
                    String n = c.getString(0);
                    int dot = n.lastIndexOf('.');
                    return dot > 0 ? n.substring(0, dot) : n;
                }
            } catch (Exception ignored) {
            }
        }
        String last = u.getLastPathSegment();
        return last == null ? u.toString() : last;
    }

    private static MediaItem item(String uri, String title, MediaItem.SubtitleConfiguration subs) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(uri).setMediaId(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        if (subs != null) b.setSubtitleConfigurations(Collections.singletonList(subs));
        return b.build();
    }

    /** True when what was opened is sound only (an MP3, M4A…), not a video. */
    private boolean isAudio(Intent i) {
        String t = i.getType();
        if (t != null) return t.startsWith("audio/");
        for (String u : uris) {
            Uri uri = Uri.parse(u);
            String mime = null;
            try {
                mime = getContentResolver().getType(uri);
            } catch (Exception ignored) {
            }
            if (mime != null) {
                if (!mime.startsWith("audio/")) return false;
                continue;
            }
            String name = nameWithExt(uri).toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            if (dot < 0 || !Drive.AUDIO.contains(name.substring(dot + 1))) return false;
        }
        return true;
    }

    /** Hands audio files to Echo's music player (background play, notification, lock screen). */
    private void playInMusicPlayer() {
        List<Library.Song> songs = new ArrayList<>();
        for (int k = 0; k < uris.size(); k++) {
            Library.Song s = new Library.Song();
            s.id = "open_" + Integer.toHexString(uris.get(k).hashCode());
            s.uri = uris.get(k);
            s.title = titles.get(k);
            s.artist = "";
            s.album = "";
            s.source = "device";
            songs.add(s);
        }
        try {
            getContentResolver().takePersistableUriPermission(Uri.parse(uris.get(0)), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        Hub.play(this, songs, Math.max(0, getIntent().getIntExtra("index", 0)), false);
        startActivity(new Intent(this, NowPlayingActivity.class));
        finish();
    }

    private boolean backgroundPlay() {
        return getSharedPreferences("video", MODE_PRIVATE).getBoolean("background", false);
    }

    private void updateTitle() {
        int i = player.getCurrentMediaItemIndex();
        if (i >= 0 && i < titles.size()) titleView.setText(titles.get(i));
    }

    // ---------- resume where you left off ----------

    private long savedPosition(String uri) {
        long p = resume.getLong(uri, 0);
        if (p > 5000) {
            main.postDelayed(() -> Toast.makeText(this, "Resumed at " + Ui.time(p), Toast.LENGTH_SHORT).show(), 600);
            return p;
        }
        return 0;
    }

    private void savePosition() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        String uri = player.getCurrentMediaItem().mediaId;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        SharedPreferences.Editor e = resume.edit();
        if (dur > 0 && pos > 5000 && pos < dur - 8000) e.putLong(uri, pos);
        else e.remove(uri);
        e.apply();
    }

    @Override
    public void onMediaItemTransition(MediaItem mediaItem, int reason) {
        updateTitle();
    }

    @Override
    public void onVideoSizeChanged(VideoSize size) {
        if (userRotated || size.width == 0 || size.height == 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        setRequestedOrientation(ratio > 1.05f ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED && player.getCurrentMediaItem() != null) {
            resume.edit().remove(player.getCurrentMediaItem().mediaId).apply();
        }
    }

    @Override
    public void onPlayerError(androidx.media3.common.PlaybackException error) {
        int i = player.getCurrentMediaItemIndex();
        String u = i >= 0 && i < uris.size() ? uris.get(i) : null;
        if (u != null && (u.startsWith("content:") || u.startsWith("file:")) && !isFinishing()) {
            // a file on the phone: offer a phone-friendly copy (too big, e.g. 4K, or a format this phone can't play)
            PhoneReady.offerAfterError(this, Uri.parse(u), titles.get(i));
        } else {
            Toast.makeText(this, "Can't play this file (" + error.getErrorCodeName() + ")", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- buttons ----------

    private void nextResize() {
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                AspectRatioFrameLayout.RESIZE_MODE_FILL};
        String[] names = {"Fit screen", "Zoom (crop)", "Stretch"};
        resize = (resize + 1) % modes.length;
        view.setResizeMode(modes[resize]);
        zoom = 1f;
        panX = panY = 0;
        applyZoom();
        flash(names[resize]);
    }

    private void rotate() {
        userRotated = true;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void moreMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Lock screen controls");
        m.getMenu().add(0, 2, 1, "Playback speed");
        m.getMenu().add(0, 3, 2, "Play as audio (keeps playing with screen off)");
        m.getMenu().add(0, 4, 3, "Edit this video");
        m.getMenu().add(0, 8, 3, "Phone-friendly copy / WhatsApp Status");
        m.getMenu().add(0, 5, 4, "Sleep timer");
        m.getMenu().add(0, 7, 6, "Keep playing when I leave: " + (backgroundPlay() ? "ON" : "off"));
        m.getMenu().add(0, 6, 5, "Repeat: " + (player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "this video"
                : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? "all" : "off"));
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: setLocked(true); break;
                case 2: chooseSpeed(); break;
                case 3: playAsAudio(); break;
                case 4: edit(); break;
                case 8: {
                    int i = player.getCurrentMediaItemIndex();
                    player.pause();
                    PhoneReady.offer(this, Uri.parse(uris.get(i)), titles.get(i));
                    break;
                }
                case 5: sleepTimer(); break;
                case 7:
                    boolean on = !backgroundPlay();
                    getSharedPreferences("video", MODE_PRIVATE).edit().putBoolean("background", on).apply();
                    flash(on ? "Sound keeps playing when you leave or lock the phone" : "Video pauses when you leave");
                    break;
                case 6:
                    int r = player.getRepeatMode();
                    player.setRepeatMode(r == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ONE
                            : r == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                    break;
            }
            return true;
        });
        m.show();
    }

    private void chooseSpeed() {
        final float[] speeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
        String[] names = new String[speeds.length];
        for (int i = 0; i < speeds.length; i++) names[i] = (speeds[i] == 1f ? "Normal" : speeds[i] + "x");
        new AlertDialog.Builder(this).setTitle("Playback speed")
                .setItems(names, (d, w) -> {
                    player.setPlaybackSpeed(speeds[w]);
                    flash(names[w]);
                }).show();
    }

    private void sleepTimer() {
        final int[] mins = {15, 30, 45, 60, 90};
        String[] names = {"15 minutes", "30 minutes", "45 minutes", "1 hour", "1½ hours", "Turn off"};
        new AlertDialog.Builder(this).setTitle("Stop playing after")
                .setItems(names, (d, w) -> {
                    main.removeCallbacks(sleep);
                    if (w < mins.length) {
                        main.postDelayed(sleep, mins[w] * 60_000L);
                        flash("Sleep timer: " + names[w]);
                    } else {
                        flash("Sleep timer off");
                    }
                }).show();
    }

    private final Runnable sleep = () -> {
        if (player != null) player.pause();
    };

    private void setLocked(boolean on) {
        locked = on;
        view.setUseController(!on);
        top.setVisibility(on ? View.GONE : View.VISIBLE);
        unlock.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            flash("Locked. Tap 🔒 to unlock");
            main.postDelayed(() -> { if (locked) unlock.setAlpha(0.35f); }, 2500);
        } else {
            unlock.setAlpha(1f);
            view.showController();
        }
    }

    private void pickSubtitles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_SUBS);
            flash("Choose a subtitle file (.srt, .vtt, .ass)");
        } catch (Exception e) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SUBS || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri sub = data.getData();
        String name = nameWithExt(sub).toLowerCase(Locale.ROOT);
        String mime = name.endsWith(".vtt") ? MimeTypes.TEXT_VTT
                : (name.endsWith(".ass") || name.endsWith(".ssa")) ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
        MediaItem.SubtitleConfiguration cfg = new MediaItem.SubtitleConfiguration.Builder(sub)
                .setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
        int index = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        player.replaceMediaItem(index, item(uris.get(index), titles.get(index), cfg));
        player.seekTo(index, pos);
        player.prepare();
        player.play();
        flash("Subtitles added");
    }

    private String nameWithExt(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? "" : last;
    }

    /** Hands the current video to Echo's music player, so its sound keeps playing in the background. */
    private void playAsAudio() {
        int i = player.getCurrentMediaItemIndex();
        long pos = player.getCurrentPosition();
        Library.Song s = new Library.Song();
        s.id = "vid_" + Integer.toHexString(uris.get(i).hashCode());
        s.uri = uris.get(i);
        s.title = titles.get(i);
        s.artist = "Video";
        s.album = "";
        s.source = "device";
        final MediaItem m = Library.item(s);
        player.pause();
        Hub.with(this, c -> {
            c.setMediaItem(m, pos);
            c.prepare();
            c.play();
        });
        Toast.makeText(this, "Playing as audio in Echo", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void edit() {
        int i = player.getCurrentMediaItemIndex();
        player.pause();
        startActivity(new Intent(this, EditorActivity.class).setData(Uri.parse(uris.get(i)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    // ---------- picture-in-picture ----------

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        VideoSize vs = player.getVideoSize();
        Rational r = vs.width > 0 && vs.height > 0
                ? new Rational(Math.max(1, Math.min(vs.width, vs.height * 2)), Math.max(1, Math.min(vs.height, vs.width * 2)))
                : new Rational(16, 9);
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(r).build());
        } catch (Exception e) {
            Toast.makeText(this, "Picture-in-picture isn't available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player == null || !player.isPlaying()) return;
        if (backgroundPlay()) playAsAudio(); // keep the sound going in Echo's music player
        else if (player.getVideoSize().width > 0) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
        view.setUseController(!pip && !locked);
        top.setVisibility(pip ? View.GONE : View.VISIBLE);
    }

    // ---------- gestures ----------

    private void flash(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHud);
        main.postDelayed(hideHud, 900);
    }

    private final Runnable hideHud = () -> hud.setVisibility(View.GONE);

    /** Scales and moves the video picture (two-finger pinch and drag). */
    private void applyZoom() {
        View s = view.getVideoSurfaceView();
        if (s == null) return;
        float maxX = s.getWidth() * (zoom - 1f) / 2f, maxY = s.getHeight() * (zoom - 1f) / 2f;
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        s.setScaleX(zoom);
        s.setScaleY(zoom);
        s.setTranslationX(panX);
        s.setTranslationY(panY);
    }

    private void setupGestures() {
        final android.view.ScaleGestureDetector scaler = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        if (locked) return true;
                        zoom = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));
                        applyZoom();
                        flash("Zoom " + Math.round(zoom * 100) + "%");
                        return true;
                    }
                });
        final GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) {
                    unlock.setAlpha(1f);
                    main.postDelayed(() -> { if (locked) unlock.setAlpha(0.35f); }, 2500);
                    return true;
                }
                if (view.isControllerFullyVisible()) view.hideController();
                else view.showController();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || player == null) return true;
                boolean forward = e.getX() > view.getWidth() / 2f;
                if (forward) player.seekForward();
                else player.seekBack();
                flash(forward ? "+10 s" : "−10 s");
                return true;
            }
        });
        view.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            if (e.getPointerCount() == 1 && mode != 4) taps.onTouchEvent(e);
            if (locked || player == null) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    // a second finger: zoom / move instead of seek, brightness or volume
                    mode = 4;
                    seekTarget = -1;
                    view.hideController();
                    lastFx = (e.getX(0) + e.getX(1)) / 2f;
                    lastFy = (e.getY(0) + e.getY(1)) / 2f;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    break;
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    mode = 0;
                    seekTarget = -1;
                    startPos = player.getCurrentPosition();
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    float wb = getWindow().getAttributes().screenBrightness;
                    startBright = wb >= 0 ? wb : 0.5f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == 4) {
                        if (e.getPointerCount() > 1) {
                            float fx = (e.getX(0) + e.getX(1)) / 2f, fy = (e.getY(0) + e.getY(1)) / 2f;
                            if (zoom > 1f) {
                                panX += fx - lastFx;
                                panY += fy - lastFy;
                                applyZoom();
                            }
                            lastFx = fx;
                            lastFy = fy;
                        }
                        break;
                    }
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (mode == 0) {
                        int slop = Ui.dp(this, 24);
                        // ignore swipes from the very top/bottom edges (system gestures, controls)
                        if (downY < Ui.dp(this, 60) || downY > view.getHeight() - Ui.dp(this, 90)) break;
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) mode = 1;
                        else if (Math.abs(dy) > slop) mode = downX < view.getWidth() / 2f ? 2 : 3;
                        if (mode != 0) view.hideController();
                    }
                    if (mode == 1) {
                        long dur = Math.max(0, player.getDuration());
                        long delta = (long) (dx / view.getWidth() * Math.min(dur, 180_000) * 1.2);
                        seekTarget = Math.max(0, Math.min(dur, startPos + delta));
                        flash((delta >= 0 ? "+" : "−") + Ui.time(Math.abs(delta)) + "\n" + Ui.time(seekTarget)
                                + " / " + Ui.time(dur));
                    } else if (mode == 2) {
                        float val = Math.max(0.01f, Math.min(1f, startBright - dy / view.getHeight()));
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = val;
                        getWindow().setAttributes(lp);
                        flash("Brightness " + Math.round(val * 100) + "%");
                    } else if (mode == 3) {
                        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int vol = Math.max(0, Math.min(max, startVol - Math.round(dy / view.getHeight() * max * 1.5f)));
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                        flash("Volume " + Math.round(vol * 100f / Math.max(1, max)) + "%");
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode == 1 && seekTarget >= 0) player.seekTo(seekTarget);
                    if (mode == 4 && zoom < 1.05f) {
                        zoom = 1f;
                        panX = panY = 0;
                        applyZoom();
                    }
                    mode = 0;
                    break;
            }
            return true;
        });
    }

    // ---------- lifecycle ----------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        savePosition();
        uris.clear();
        titles.clear();
        readIntent(intent);
        if (uris.isEmpty() || player == null) return;
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < uris.size(); i++) items.add(item(uris.get(i), titles.get(i), null));
        player.setMediaItems(items, 0, savedPosition(uris.get(0)));
        player.prepare();
        player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePosition();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player == null) return;
        if (backgroundPlay() && player.isPlaying() && !inPip && !isFinishing()) {
            playAsAudio(); // screen locked or app left: the sound carries on
            return;
        }
        player.pause(); // also when the PiP window is closed
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            savePosition();
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** Opens the player for a list of videos. */
    static void play(Activity a, List<Videos.Video> list, int index) {
        ArrayList<String> u = new ArrayList<>(), t = new ArrayList<>();
        for (Videos.Video v : list) {
            u.add(v.uri);
            t.add(v.title);
        }
        a.startActivity(new Intent(a, VideoPlayerActivity.class)
                .putStringArrayListExtra("uris", u).putStringArrayListExtra("titles", t).putExtra("index", index));
    }
}
