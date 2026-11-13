package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Long-press to select songs, then act on all of them at once. */
final class Selection {
    interface Actions {
        void fillMenu(Menu m);

        /** Return true when done, to leave selection mode. */
        boolean onAction(int id, List<Library.Song> songs);
    }

    final LinearLayout bar;
    private final Activity a;
    private final SongAdapter adapter;
    private final TextView count;

    Selection(Activity a, SongAdapter adapter, String primaryLabel, int primaryId, Actions actions) {
        this.a = a;
        this.adapter = adapter;
        bar = Ui.row(a);
        bar.setBackgroundColor(0xE62A1F5C);
        bar.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        TextView close = Ui.text(a, "✕", 20, Ui.TEXT, false);
        close.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4));
        close.setOnClickListener(v -> exit());
        bar.addView(close);
        count = Ui.line(a, "", 16, Ui.TEXT, true);
        bar.addView(count, Ui.weight());
        TextView all = Ui.pill(a, "All", false);
        all.setOnClickListener(v -> {
            if (adapter.selectedCount() == adapter.getCount()) adapter.clearSelection();
            else adapter.selectAll();
            update();
        });
        bar.addView(all);
        TextView primary = Ui.pill(a, primaryLabel, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(a, 6);
        primary.setOnClickListener(v -> run(actions, primaryId));
        bar.addView(primary, lp);
        android.widget.ImageButton more = Ui.icon(a, R.drawable.ic_more, 40, Ui.TEXT);
        more.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(a, v);
            actions.fillMenu(m.getMenu());
            m.setOnMenuItemClickListener(item -> {
                run(actions, item.getItemId());
                return true;
            });
            m.show();
        });
        bar.addView(more);
        bar.setVisibility(View.GONE);
    }

    private void run(Actions actions, int id) {
        List<Library.Song> songs = adapter.selectedSongs();
        if (songs.isEmpty()) return;
        if (actions.onAction(id, songs)) exit();
    }

    boolean active() {
        return adapter.selecting();
    }

    /** Long press or tap while selecting. */
    void toggle(int position) {
        adapter.toggle(position);
        update();
    }

    void update() {
        int n = adapter.selectedCount();
        if (n == 0) {
            exit();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        count.setText(n + " selected");
    }

    void exit() {
        adapter.clearSelection();
        adapter.setSelecting(false);
        bar.setVisibility(View.GONE);
    }

    // ---------- shared actions ----------

    static void addToFolder(Activity a, List<Library.Song> songs, Library.Playlist from, boolean move, Runnable after) {
        Library lib = Library.get(a);
        List<Library.Playlist> targets = lib.localPlaylists();
        if (from != null) targets.remove(from);
        String what = songs.size() == 1 ? "\"" + songs.get(0).title + "\"" : songs.size() + " songs";
        Folders.pick(a, (move ? "Move " : (from == null ? "Add " : "Copy ")) + what + " to", targets, false, target -> {
            for (Library.Song s : songs) {
                if (!target.songIds.contains(s.id)) target.songIds.add(s.id);
                if (move && from != null) from.songIds.remove(s.id);
            }
            lib.save();
            Toast.makeText(a, (move ? "Moved to " : "Added to ") + target.name, Toast.LENGTH_SHORT).show();
            after.run();
        });
    }

    static void queue(Activity a, List<Library.Song> songs) {
        for (Library.Song s : songs) Hub.addToQueue(a, s);
        Toast.makeText(a, songs.size() + " added to queue", Toast.LENGTH_SHORT).show();
    }

    /** Removes songs from Echo. Files in Google Drive or on the phone are never deleted. */
    static void removeFromEcho(Activity a, List<Library.Song> songs, Runnable after) {
        boolean drive = false, copied = false;
        for (Library.Song s : songs) {
            if ("drive".equals(s.source)) drive = true;
            if ("file".equals(s.source)) copied = true;
        }
        String what = songs.size() == 1 ? "\"" + songs.get(0).title + "\"" : songs.size() + " songs";
        StringBuilder msg = new StringBuilder("Remove " + what + " from Echo? They are taken out of every folder.");
        if (drive) msg.append("\n\nSongs from Google Drive stay in your Drive. Echo can only view your shared "
                + "folder, so it can't delete files there. To delete one from Drive, use \"Open in Google Drive\".");
        if (copied) msg.append("\n\nSongs you picked from phone files are deleted from Echo's storage.");
        new AlertDialog.Builder(a).setTitle("Remove from Echo").setMessage(msg.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    Library.get(a).deleteSongs(songs);
                    after.run();
                }).show();
    }

    static boolean isDrive(Library.Song s) {
        return "drive".equals(s.source) && s.id.startsWith("gd_");
    }

    static void openInDrive(Activity a, Library.Song s) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://drive.google.com/file/d/" + s.id.substring(3) + "/view")));
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't open Google Drive", Toast.LENGTH_SHORT).show();
        }
    }
}
