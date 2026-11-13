package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** One folder or playlist: its sub-folders first, then its songs. */
public class PlaylistActivity extends Activity {
    private Library.Playlist pl;
    private SongAdapter songs;
    private BaseAdapter adapter;
    private List<Library.Playlist> folders = new ArrayList<>();
    private MiniPlayer mini;
    private TextView titleView, countView, pathView, empty;
    private String lastId;
    private Selection selection;
    private static final int A_MOVE = 1, A_COPY = 2, A_QUEUE = 3, A_PLAY = 4, A_REMOVE_HERE = 5, A_REMOVE_ECHO = 6;

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
        head.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 12));
        pathView = Ui.line(this, "", 12, Ui.DIM, false);
        titleView = Ui.text(this, pl.name, 26, Ui.TEXT, true);
        countView = Ui.text(this, "", 14, Ui.SUB, false);
        head.addView(pathView);
        head.addView(titleView);
        head.addView(countView);
        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView play = Ui.pill(this, "▶  Play", true);
        play.setOnClickListener(v -> playAll(false));
        TextView shuffle = Ui.pill(this, "Shuffle", false);
        shuffle.setOnClickListener(v -> playAll(true));
        TextView add = Ui.pill(this, "+ Add", false);
        add.setOnClickListener(this::addMenu);
        buttons.addView(play);
        buttons.addView(shuffle, margin());
        buttons.addView(add, margin());
        head.addView(buttons);
        root.addView(head);

        songs = new SongAdapter(this, this::songMenu);
        songs.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        selection = new Selection(this, songs, "Move", A_MOVE, new Selection.Actions() {
            @Override
            public void fillMenu(android.view.Menu m) {
                m.add(0, A_PLAY, 0, "Play");
                m.add(0, A_QUEUE, 1, "Add to queue");
                m.add(0, A_MOVE, 2, "Move to folder…");
                m.add(0, A_COPY, 3, "Copy to folder…");
                m.add(0, A_REMOVE_HERE, 4, "Remove from this folder");
                m.add(0, A_REMOVE_ECHO, 5, "Remove from Echo");
            }

            @Override
            public boolean onAction(int id, List<Library.Song> list) {
                switch (id) {
                    case A_PLAY: Hub.play(PlaylistActivity.this, list, 0, false); return true;
                    case A_QUEUE: Selection.queue(PlaylistActivity.this, list); return true;
                    case A_MOVE:
                    case A_COPY:
                        Selection.addToFolder(PlaylistActivity.this, list, pl, id == A_MOVE, () -> {
                            selection.exit();
                            refresh();
                        });
                        return false;
                    case A_REMOVE_HERE:
                        for (Library.Song s : list) pl.songIds.remove(s.id);
                        Library.get(PlaylistActivity.this).save();
                        Toast.makeText(PlaylistActivity.this, list.size() + " removed from this folder",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                        return true;
                    case A_REMOVE_ECHO:
                        Selection.removeFromEcho(PlaylistActivity.this, list, () -> {
                            selection.exit();
                            refresh();
                        });
                        return false;
                }
                return false;
            }
        });
        root.addView(selection.bar);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return folders.size() + songs.getCount(); }
            @Override public Object getItem(int i) { return i < folders.size() ? folders.get(i) : songs.getItem(i - folders.size()); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) { return i < folders.size() ? 0 : 1; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                if (i < folders.size()) return Folders.row(PlaylistActivity.this, folders.get(i), PlaylistActivity.this::refresh);
                return songs.getView(i - folders.size(), convert, parent);
            }
        };
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < folders.size()) {
                if (!selection.active()) Folders.open(this, folders.get(pos));
            } else if (selection.active()) {
                selection.toggle(pos - folders.size());
            } else {
                Library.get(this).markRecent(pl.id);
                Hub.play(this, songs.items(), pos - folders.size(), false);
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < folders.size()) {
                Folders.menu(this, v, folders.get(pos), this::refresh);
                return true;
            }
            selection.toggle(pos - folders.size());
            return true;
        });
        empty = Ui.text(this, "This folder is empty. Tap \"+ Add\" to add songs or a folder.", 15, Ui.SUB, false);
        empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), 0);
        root.addView(empty);
        list.setEmptyView(empty);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mini = new MiniPlayer(this, () -> {
            String id = mini == null ? null : mini.currentId();
            if (id == null ? lastId == null : id.equals(lastId)) return;
            lastId = id;
            songs.setCurrent(id);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
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

    void reload() {
        refresh();
    }

    private void refresh() {
        Library lib = Library.get(this);
        if (lib.playlist(pl.id) == null) { // deleted
            finish();
            return;
        }
        folders = lib.visibleChildren(pl.id);
        songs.setItems(lib.songsOf(pl));
        adapter.notifyDataSetChanged();
        titleView.setText(pl.name);
        countView.setText(pl.subtitle(folders.size()) + (pl.driveFolder != null ? "  ·  Google Drive" : ""));
        Library.Playlist parent = lib.playlist(pl.parentId);
        pathView.setText(parent == null ? "Playlists" : "Playlists › " + lib.path(parent));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) refresh();
    }

    private void playAll(boolean shuffle) {
        List<Library.Song> all = Library.get(this).allSongsIn(pl);
        if (all.isEmpty()) {
            Toast.makeText(this, "No songs in this folder yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Library.get(this).markRecent(pl.id);
        Hub.play(this, all, 0, shuffle);
    }

    private void addMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "New folder here");
        m.getMenu().add(0, 2, 1, "Add songs");
        m.getMenu().add(0, 3, 2, "Move a folder here");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) Folders.newFolder(this, pl.id, f -> refresh());
            else if (item.getItemId() == 2) addSongs();
            else moveHere();
            return true;
        });
        m.show();
    }

    /** Pick any other folder and move it into this one. */
    private void moveHere() {
        Library lib = Library.get(this);
        List<Library.Playlist> candidates = new ArrayList<>();
        for (Library.Playlist p : lib.playlists) {
            if (p.id.equals(pl.id) || pl.id.equals(p.parentId)) continue;
            if (lib.isInside(pl, p)) continue; // can't move a folder into its own sub-folder
            candidates.add(p);
        }
        java.util.Collections.sort(candidates, (x, y) -> lib.path(x).compareToIgnoreCase(lib.path(y)));
        Folders.pick(this, "Move into \"" + pl.name + "\"", candidates, false, target -> {
            target.parentId = pl.id;
            lib.save();
            refresh();
        });
    }

    private void songMenu(View anchor, Library.Song s, int pos) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Play next");
        m.getMenu().add(0, 2, 1, "Add to queue");
        m.getMenu().add(0, 6, 2, "Move to folder…");
        m.getMenu().add(0, 7, 3, "Copy to folder…");
        m.getMenu().add(0, 3, 4, "Move up");
        m.getMenu().add(0, 4, 5, "Move down");
        m.getMenu().add(0, 5, 6, "Remove from this folder");
        m.getMenu().add(0, 8, 7, "Remove from Echo");
        if (Selection.isDrive(s)) m.getMenu().add(0, 9, 8, "Open in Google Drive");
        m.setOnMenuItemClickListener(item -> {
            Library lib = Library.get(this);
            int i = pl.songIds.indexOf(s.id);
            switch (item.getItemId()) {
                case 1: Hub.playNext(this, s); Toast.makeText(this, "Plays next", Toast.LENGTH_SHORT).show(); break;
                case 2: Hub.addToQueue(this, s); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show(); break;
                case 3: if (i > 0) { pl.songIds.remove(i); pl.songIds.add(i - 1, s.id); lib.save(); } break;
                case 4: if (i >= 0 && i < pl.songIds.size() - 1) { pl.songIds.remove(i); pl.songIds.add(i + 1, s.id); lib.save(); } break;
                case 5: pl.songIds.remove(s.id); lib.save(); break;
                case 8: Selection.removeFromEcho(this, java.util.Collections.singletonList(s), this::refresh); break;
                case 9: Selection.openInDrive(this, s); break;
                case 6:
                case 7:
                    final boolean move = item.getItemId() == 6;
                    List<Library.Playlist> targets = lib.localPlaylists();
                    targets.remove(pl);
                    Folders.pick(this, (move ? "Move" : "Copy") + " \"" + s.title + "\" to", targets, false, target -> {
                        if (!target.songIds.contains(s.id)) target.songIds.add(s.id);
                        if (move) pl.songIds.remove(s.id);
                        lib.save();
                        refresh();
                        Toast.makeText(this, (move ? "Moved to " : "Copied to ") + target.name, Toast.LENGTH_SHORT).show();
                    });
                    break;
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
        Folders.menu(this, anchor, pl, this::refresh);
    }

    static void rename(Activity a, Library.Playlist pl, Runnable after) {
        Folders.rename(a, pl, after);
    }

    @Override
    public void onBackPressed() {
        if (selection != null && selection.active()) selection.exit();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (mini != null) mini.release();
        super.onDestroy();
    }
}
