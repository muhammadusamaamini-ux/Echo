package com.usama.naghma;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;

/** Full-screen player: artwork, seek bar and controls. */
public class NowPlayingActivity extends Activity implements Player.Listener {
    private MediaController c;
    private ImageView art;
    private TextView title, artist, pos, dur;
    private SeekBar seek;
    private ImageButton play, shuffle, repeat;
    private boolean dragging;
    private byte[] artData;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.topBar(this, "Now playing", R.drawable.ic_down);
        ImageButton queue = Ui.icon(this, R.drawable.ic_queue, 44, Ui.TEXT);
        queue.setOnClickListener(v -> showQueue());
        bar.addView(queue);
        root.addView(bar);

        int side = Ui.dp(this, 28);
        LinearLayout body = Ui.column(this);
        body.setPadding(side, Ui.dp(this, 8), side, Ui.dp(this, 24));
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        int size = Math.min(getResources().getDisplayMetrics().widthPixels - 2 * side,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(this, 10)));
        art.setClipToOutline(true);
        body.addView(art, new LinearLayout.LayoutParams(size, size));

        LinearLayout info = Ui.column(this);
        info.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 8));
        title = Ui.line(this, "", 22, Ui.TEXT, true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        artist = Ui.line(this, "", 15, Ui.SUB, false);
        info.addView(title);
        info.addView(artist);
        body.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        seek.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        body.addView(seek, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout times = Ui.row(this);
        pos = Ui.text(this, "0:00", 12, Ui.SUB, false);
        dur = Ui.text(this, "0:00", 12, Ui.SUB, false);
        times.addView(pos, Ui.weight());
        times.addView(dur);
        body.addView(times, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout ctr = Ui.row(this);
        ctr.setGravity(Gravity.CENTER);
        ctr.setPadding(0, Ui.dp(this, 16), 0, 0);
        shuffle = Ui.icon(this, R.drawable.ic_shuffle, 48, Ui.SUB);
        ImageButton prev = Ui.icon(this, R.drawable.ic_prev, 56, Ui.TEXT);
        play = Ui.icon(this, R.drawable.ic_play, 72, 0xFF000000);
        Ui.ripple(play, Ui.TEXT, Ui.dp(this, 36));
        ImageButton next = Ui.icon(this, R.drawable.ic_next, 56, Ui.TEXT);
        repeat = Ui.icon(this, R.drawable.ic_repeat, 48, Ui.SUB);
        ctr.addView(shuffle);
        ctr.addView(spacer());
        ctr.addView(prev);
        ctr.addView(spacer());
        ctr.addView(play);
        ctr.addView(spacer());
        ctr.addView(next);
        ctr.addView(spacer());
        ctr.addView(repeat);
        body.addView(ctr);

        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        play.setOnClickListener(v -> { if (c != null) Hub.togglePlay(c); });
        prev.setOnClickListener(v -> { if (c != null) c.seekToPrevious(); });
        next.setOnClickListener(v -> { if (c != null && c.hasNextMediaItem()) c.seekToNextMediaItem(); });
        shuffle.setOnClickListener(v -> { if (c != null) c.setShuffleModeEnabled(!c.getShuffleModeEnabled()); });
        repeat.setOnClickListener(v -> {
            if (c == null) return;
            int m = c.getRepeatMode();
            c.setRepeatMode(m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                    : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && c != null) pos.setText(Ui.time(c.getDuration() * p / 1000));
            }

            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (c != null && c.getDuration() > 0) c.seekTo(c.getDuration() * s.getProgress() / 1000);
            }
        });

        Hub.with(this, ctrl -> {
            if (isDestroyed()) return;
            c = ctrl;
            c.addListener(this);
            update();
        });
        handler.post(tick);
    }

    private android.view.View spacer() {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        return v;
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        update();
    }

    private void update() {
        if (c == null) return;
        MediaItem item = c.getCurrentMediaItem();
        if (item == null) {
            finish();
            return;
        }
        MediaMetadata md = c.getMediaMetadata();
        CharSequence t = md.title != null ? md.title : item.mediaMetadata.title;
        CharSequence a = md.artist != null ? md.artist : item.mediaMetadata.artist;
        title.setText(t == null ? "" : t);
        artist.setText(a == null || a.length() == 0 ? "Unknown artist" : a);
        play.setImageResource(c.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuffle.setColorFilter(c.getShuffleModeEnabled() ? Ui.ACCENT : Ui.SUB);
        int rm = c.getRepeatMode();
        repeat.setImageResource(rm == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(rm == Player.REPEAT_MODE_OFF ? Ui.SUB : Ui.ACCENT);
        MiniPlayer.setArt(art, md.artworkData, artData);
        artData = md.artworkData;
        updateTime();
    }

    private void updateTime() {
        if (c == null || dragging) return;
        long d = c.getDuration();
        long p = c.getCurrentPosition();
        if (d < 0) d = 0;
        seek.setProgress(d > 0 ? (int) (p * 1000 / d) : 0);
        pos.setText(Ui.time(p));
        dur.setText(Ui.time(d));
    }

    private void showQueue() {
        if (c == null || c.getMediaItemCount() == 0) return;
        int n = c.getMediaItemCount();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            MediaMetadata m = c.getMediaItemAt(i).mediaMetadata;
            names[i] = (i == c.getCurrentMediaItemIndex() ? "▶  " : "") + (m.title == null ? "Song" : m.title)
                    + (m.artist == null || m.artist.length() == 0 ? "" : "  —  " + m.artist);
        }
        new AlertDialog.Builder(this).setTitle("Queue" + (c.getShuffleModeEnabled() ? " (shuffle on)" : ""))
                .setItems(names, (d, which) -> {
                    c.seekToDefaultPosition(which);
                    c.play();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (c != null) c.removeListener(this);
        super.onDestroy();
    }
}
