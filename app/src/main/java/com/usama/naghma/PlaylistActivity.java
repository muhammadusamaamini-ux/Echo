package com.usama.naghma;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** One of your own playlists. */
public class PlaylistActivity extends Activity {
    private Library.Playlist pl;
    private SongAdapter adapter;
    private MiniPlayer mini;
    private TextView titleView, countView;

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

        LinearLayout bar = Ui.topBar(this, "", R.drawable.ic_back);
        ImageButton menu = Ui.icon(this, R.drawable.ic_more, 44, Ui.TEXT);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu);
        root.addView(bar);

        LinearLayout head = Ui.column(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 12));
        titleView = Ui.text(this, pl.name, 26, Ui.TEXT, true);
        countView = Ui.text(this, "", 14, Ui.SUB, false);
        head.addView(titleView);
        head.addView(countView);
        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView play = Ui.pill(this, "▶  Play", true);
        play.setOnClickListener(v -> Hub.play(this, adapter.items(), 0, false));
        TextView shuffle = Ui.pill(this, "Shuffle", false);
        shuffle.setOnClickListener(v -> Hub.play(this, adapter.items(), 0, true));
        TextView add = Ui.pill(this, "+ Add songs", false);
        add.setOnClickListener(v -> addSongs());
        buttons.addView(play);
        buttons.addView(shuffle, margin());
        buttons.addView(add, margin());
        head.addView(buttons);
        root.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new SongAdapter(this, this::songMenu);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> Hub.play(this, adapter.items(), pos, false));
        TextView empty = Ui.text(this, "No songs yet. Tap \"+ Add songs\".", 15, Ui.SUB, false);
        empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), 0);
        root.addView(empty);
        list.setEmptyView(empty);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mini = new MiniPlayer(this, () -> adapter.setCurrent(mini == null ? null : mini.currentId()));
        root.addView(mini.view);
        setContentView(root);
        refresh();
    }

    private LinearLayout.LayoutParams margin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        return lp;
    }

    private void refresh() {
        List<Library.Song> songs = Library.get(this).songsOf(pl);
        adapter.setItems(songs);
        titleView.setText(pl.name);
        countView.setText(pl.subtitle());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) refresh();
    }

    private void songMenu(View anchor, Library.Song s, int pos) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play next");
        m.getMenu().add(0, 2, 1, "Add to queue");
        m.getMenu().add(0, 3, 2, "Move up");
        m.getMenu().add(0, 4, 3, "Move down");
        m.getMenu().add(0, 5, 4, "Remove from this playlist");
        m.setOnMenuItemClickListener(item -> {
            Library lib = Library.get(this);
            int i = pl.songIds.indexOf(s.id);
            switch (item.getItemId()) {
                case 1: Hub.playNext(this, s); Toast.makeText(this, "Plays next", Toast.LENGTH_SHORT).show(); break;
                case 2: Hub.addToQueue(this, s); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show(); break;
                case 3: if (i > 0) { pl.songIds.remove(i); pl.songIds.add(i - 1, s.id); lib.save(); } break;
                case 4: if (i >= 0 && i < pl.songIds.size() - 1) { pl.songIds.remove(i); pl.songIds.add(i + 1, s.id); lib.save(); } break;
                case 5: pl.songIds.remove(s.id); lib.save(); break;
            }
            refresh();
            return true;
        });
        m.show();
    }

    private void addSongs() {
        final Library lib = Library.get(this);
        final List<Library.Song> all = lib.sortedSongs();
        if (all.isEmpty()) {
            Toast.makeText(this, "Your library is empty. Import some music first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[all.size()];
        final boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Library.Song s = all.get(i);
            names[i] = s.title + (s.artist == null || s.artist.isEmpty() ? "" : "  —  " + s.artist);
            checked[i] = pl.songIds.contains(s.id);
        }
        new AlertDialog.Builder(this).setTitle("Add songs")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < all.size(); i++) {
                        String id = all.get(i).id;
                        if (checked[i] && !pl.songIds.contains(id)) pl.songIds.add(id);
                        if (!checked[i]) pl.songIds.remove(id);
                    }
                    lib.save();
                    refresh();
                }).show();
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Rename");
        m.getMenu().add(0, 2, 1, "Delete playlist");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                rename(this, pl, this::refresh);
            } else {
                new AlertDialog.Builder(this).setMessage("Delete \"" + pl.name + "\"? The songs stay in your library.")
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

    static void rename(Activity a, Library.Playlist pl, Runnable after) {
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(pl.name);
        e.setSelectAllOnFocus(true);
        LinearLayout box = Ui.column(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(a).setTitle("Rename playlist").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    pl.name = n;
                    Library.get(a).save();
                    after.run();
                }).show();
    }

    @Override
    protected void onDestroy() {
        if (mini != null) mini.release();
        super.onDestroy();
    }
}
