package com.usama.echo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Plays an imported YouTube playlist in YouTube's own player (needs internet, keeps the screen on). */
public class YouTubeActivity extends Activity {
    private Library.Playlist pl;
    private WebView web;
    private int current = -1;
    private BaseAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint("AddJavascriptInterface")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pl = Library.get(this).playlist(getIntent().getStringExtra("id"));
        if (pl == null) {
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.topBar(this, pl.name, R.drawable.ic_back);
        ImageButton menu = Ui.icon(this, R.drawable.ic_more, 44, Ui.TEXT);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu);
        root.addView(bar);

        web = YouTube.webView(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, w * 9 / 16));

        LinearLayout controls = Ui.row(this);
        controls.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 4));
        TextView info = Ui.line(this, pl.subtitle(), 13, Ui.SUB, false);
        controls.addView(info, Ui.weight());
        ImageButton prev = Ui.icon(this, R.drawable.ic_prev, 44, Ui.TEXT);
        prev.setOnClickListener(v -> js("player.previousVideo()"));
        ImageButton next = Ui.icon(this, R.drawable.ic_next, 44, Ui.TEXT);
        next.setOnClickListener(v -> js("player.nextVideo()"));
        controls.addView(prev);
        controls.addView(next);
        root.addView(controls);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView match = Ui.pill(this, "Find these in my songs", true);
        match.setOnClickListener(v -> matchWithLibrary());
        actions.addView(match);
        TextView open = Ui.pill(this, "Open in YouTube", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        open.setOnClickListener(v -> YouTube.openInYouTube(this, pl.ytListId, null));
        actions.addView(open, lp);
        TextView save = Ui.pill(this, "Download", false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = Ui.dp(this, 8);
        save.setOnClickListener(v -> {
            if (current >= 0 && current < pl.ytItems.size()) {
                Saver.start(this, pl.ytItems.get(current).videoId);
            } else {
                Toast.makeText(this, "Play a video first, or tap and hold a video in the list", Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(save, lp2);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(actions);
        root.addView(scroll);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return pl.ytItems.size(); }
            @Override public Object getItem(int i) { return pl.ytItems.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                Library.YtItem it = pl.ytItems.get(i);
                LinearLayout row = Ui.row(YouTubeActivity.this);
                row.setPadding(Ui.dp(YouTubeActivity.this, 16), Ui.dp(YouTubeActivity.this, 8),
                        Ui.dp(YouTubeActivity.this, 16), Ui.dp(YouTubeActivity.this, 8));
                TextView num = Ui.text(YouTubeActivity.this, String.valueOf(i + 1), 14,
                        i == current ? Ui.ACCENT : Ui.DIM, false);
                num.setMinWidth(Ui.dp(YouTubeActivity.this, 32));
                row.addView(num);
                LinearLayout col = Ui.column(YouTubeActivity.this);
                TextView t = Ui.line(YouTubeActivity.this, it.title, 15, i == current ? Ui.ACCENT : Ui.TEXT, false);
                TextView s = Ui.line(YouTubeActivity.this, it.author, 13, Ui.SUB, false);
                col.addView(t);
                col.addView(s);
                row.addView(col, Ui.weight());
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Library.get(this).markRecent(pl.id);
            js("playAt(" + pos + ")");
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Library.YtItem it = pl.ytItems.get(pos);
            new AlertDialog.Builder(this).setTitle(it.title)
                    .setItems(new String[]{"Download (audio or video)", "Open in YouTube"}, (d, which) -> {
                        if (which == 0) Saver.start(this, it.videoId);
                        else YouTube.openInYouTube(this, null, it.videoId);
                    }).show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onState(int state, int index) {
                main.post(() -> {
                    if (state == 1) Hub.pauseIfPlaying(); // playing: stop Echo's own music
                    if (index != current) {
                        current = index;
                        adapter.notifyDataSetChanged();
                    }
                });
            }

            @JavascriptInterface
            public void onError(int code) {
                main.post(() -> {
                    if (code == 101 || code == 150 || code == 100) {
                        Toast.makeText(YouTubeActivity.this, "This video can't play here, skipping", Toast.LENGTH_SHORT).show();
                        js("player.nextVideo()");
                    } else {
                        Toast.makeText(YouTubeActivity.this, "YouTube error " + code, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "Android");

        JSONArray ids = new JSONArray();
        for (Library.YtItem it : pl.ytItems) ids.put(it.videoId);
        String js = "var ids=" + ids + ";"
                + "function ready(){player.cuePlaylist(ids,0);}"
                + "function playAt(i){player.loadPlaylist(ids,i);}"
                + "function state(s){var i=-1;try{i=player.getPlaylistIndex();}catch(e){}Android.onState(s,i);}";
        web.loadDataWithBaseURL(YouTube.base(this), YouTube.page(this, YouTube.playerVars(this), js),
                "text/html", "utf-8", null);

        String note = getIntent().getStringExtra("note");
        if (note != null) Toast.makeText(this, note, Toast.LENGTH_LONG).show();
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript("try{" + code + "}catch(e){}", null);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Rename");
        m.getMenu().add(0, 2, 1, "Delete playlist");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                PlaylistActivity.rename(this, pl, () -> recreate());
            } else {
                new AlertDialog.Builder(this).setMessage("Delete \"" + pl.name + "\"?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            Library lib = Library.get(this);
                            lib.playlists.remove(pl);
                            lib.save();
                            finish();
                        }).show();
            }
            return true;
        });
        m.show();
    }

    /** Makes a normal playlist from the songs in your library that match these videos. */
    private void matchWithLibrary() {
        Library lib = Library.get(this);
        List<Library.Song> all = lib.sortedSongs();
        List<String> found = new ArrayList<>();
        for (Library.YtItem it : pl.ytItems) {
            Library.Song s = Importer.match(it, all);
            if (s != null && !found.contains(s.id)) found.add(s.id);
        }
        if (found.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No matches")
                    .setMessage("None of these videos matched songs in your library. Add your songs "
                            + "(for example a Google Drive folder) first, then try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        int total = pl.ytItems.size();
        new AlertDialog.Builder(this).setTitle("Found " + found.size() + " of " + total)
                .setMessage("Make a playlist \"" + pl.name + " (my songs)\" with the " + found.size()
                        + " songs found? It will play offline and with the screen off.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Make playlist", (d, w) -> {
                    Library.Playlist np = lib.newPlaylist(pl.name + " (my songs)");
                    np.songIds.addAll(found);
                    lib.save();
                    startActivity(new android.content.Intent(this, PlaylistActivity.class).putExtra("id", np.id));
                }).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            js("player.pauseVideo()");
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
