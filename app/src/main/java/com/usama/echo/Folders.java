package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.util.List;

/** Shared actions for folders and playlists: create, open, rename, move, delete. */
final class Folders {
    private Folders() {}

    interface NameCallback {
        void onName(String name);
    }

    interface PickCallback {
        /** target is null for "Top level". */
        void onPick(Library.Playlist target);
    }

    // ---------- rows ----------

    /** A row for a folder or playlist, with a ⋮ button. */
    static LinearLayout row(Activity a, Library.Playlist p, Runnable changed) {
        Library lib = Library.get(a);
        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        int sub = lib.visibleChildren(p.id).size();
        if (p.youtube) {
            android.widget.TextView yt = Ui.tile(a, "▶", 52, Ui.YT);
            yt.setBackground(Ui.ytGrad(Ui.dp(a, 12)));
            row.addView(yt);
        } else {
            ImageView icon = new ImageView(a);
            icon.setImageResource(sub > 0 || p.songIds.isEmpty() ? R.drawable.ic_folder : R.drawable.ic_music);
            icon.setColorFilter(Ui.TEXT);
            int pad = Ui.dp(a, 13);
            icon.setPadding(pad, pad, pad, pad);
            icon.setBackground(Ui.gradFor(p.name, Ui.dp(a, 12)));
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
        }
        LinearLayout col = Ui.column(a);
        col.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 4), 0);
        col.addView(Ui.line(a, p.name, 16, Ui.TEXT, false));
        String subtitle = p.subtitle(sub) + (p.driveFolder != null ? "  ·  Drive" : "");
        col.addView(Ui.line(a, subtitle, 13, Ui.SUB, false));
        row.addView(col, Ui.weight());
        ImageButton more = Ui.icon(a, R.drawable.ic_more, 40, Ui.SUB);
        more.setOnClickListener(v -> menu(a, v, p, changed));
        row.addView(more);
        row.setMinimumHeight(Ui.dp(a, 68));
        return row;
    }

    /** The "+ New folder" row. */
    static LinearLayout addRow(Activity a, String label) {
        LinearLayout row = Ui.row(a);
        row.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        android.widget.TextView plus = Ui.tile(a, "+", 52, Ui.SURFACE2);
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        row.addView(plus);
        android.widget.TextView t = Ui.line(a, label, 16, Ui.TEXT, true);
        t.setPadding(Ui.dp(a, 14), 0, 0, 0);
        row.addView(t, Ui.weight());
        row.setMinimumHeight(Ui.dp(a, 68));
        return row;
    }

    static void open(Activity a, Library.Playlist p) {
        Library.get(a).markRecent(p.id);
        a.startActivity(new Intent(a, p.youtube ? YouTubeActivity.class : PlaylistActivity.class).putExtra("id", p.id));
    }

    /** ⋮ → Hidden folders: open or unhide them. */
    static void showHidden(Activity a, Runnable changed) {
        Library lib = Library.get(a);
        List<Library.Playlist> hidden = lib.hiddenFolders();
        if (hidden.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Hidden folders")
                    .setMessage("Nothing is hidden. To hide a folder or playlist, tap its ⋮ and choose Hide.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) names[i] = lib.path(hidden.get(i));
        new AlertDialog.Builder(a).setTitle("Hidden folders")
                .setItems(names, (d, which) -> {
                    Library.Playlist p = hidden.get(which);
                    new AlertDialog.Builder(a).setTitle(p.name)
                            .setPositiveButton("Open", (d2, w2) -> open(a, p))
                            .setNeutralButton("Unhide", (d2, w2) -> {
                                p.hidden = false;
                                lib.save();
                                changed.run();
                            })
                            .setNegativeButton("Cancel", null).show();
                })
                .setNegativeButton("Close", null).show();
    }

    // ---------- menu ----------

    static void menu(Activity a, View anchor, Library.Playlist p, Runnable changed) {
        Library lib = Library.get(a);
        PopupMenu m = new PopupMenu(a, anchor);
        if (!p.youtube) {
            m.getMenu().add(0, 1, 0, "Play all");
            m.getMenu().add(0, 2, 1, "Shuffle all");
        }
        if (p.driveFolder != null) m.getMenu().add(0, 6, 2, "Sync with Drive now");
        if (!p.youtube) m.getMenu().add(0, 7, 3, "New folder inside");
        m.getMenu().add(0, 3, 4, "Rename");
        m.getMenu().add(0, 4, 5, "Move to…");
        m.getMenu().add(0, 8, 6, p.hidden ? "Unhide" : "Hide");
        m.getMenu().add(0, 5, 7, "Delete");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Hub.play(a, lib.allSongsIn(p), 0, false); break;
                case 2: Hub.play(a, lib.allSongsIn(p), 0, true); break;
                case 3: rename(a, p, changed); break;
                case 4: move(a, p, changed); break;
                case 5: delete(a, p, changed); break;
                case 6: Drive.syncNow(a, p); break;
                case 8:
                    p.hidden = !p.hidden;
                    lib.save();
                    Toast.makeText(a, p.hidden ? "Hidden. Find it in ⋮ → Hidden folders." : "Shown again",
                            Toast.LENGTH_SHORT).show();
                    changed.run();
                    break;
                case 7: newFolder(a, p.id, f -> { changed.run(); open(a, f); }); break;
            }
            return true;
        });
        m.show();
    }

    interface Created {
        void onCreated(Library.Playlist folder);
    }

    static void newFolder(Activity a, String parentId, Created done) {
        askName(a, parentId == null ? "New folder" : "New folder inside", "New folder", n -> {
            Library.Playlist f = Library.get(a).newPlaylist(n, parentId);
            done.onCreated(f);
        });
    }

    static void rename(Activity a, Library.Playlist p, Runnable after) {
        askName(a, "Rename", p.name, n -> {
            p.name = n;
            Library.get(a).save();
            after.run();
        });
    }

    static void move(Activity a, Library.Playlist p, Runnable after) {
        Library lib = Library.get(a);
        pick(a, "Move \"" + p.name + "\" to", lib.moveTargets(p), true, target -> {
            p.parentId = target == null ? null : target.id;
            lib.save();
            Toast.makeText(a, "Moved to " + (target == null ? "Playlists" : lib.path(target)), Toast.LENGTH_SHORT).show();
            after.run();
        });
    }

    static void delete(Activity a, Library.Playlist p, Runnable after) {
        Library lib = Library.get(a);
        int inside = lib.countTree(p);
        int songs = p.youtube ? 0 : lib.songsOnlyIn(p).size();
        StringBuilder msg = new StringBuilder("This removes \"" + p.name + "\" from Echo");
        if (inside > 0 || songs > 0) {
            msg.append(" together with");
            if (inside > 0) msg.append(" ").append(inside).append(inside == 1 ? " folder" : " folders");
            if (inside > 0 && songs > 0) msg.append(" and");
            if (songs > 0) msg.append(" ").append(songs).append(songs == 1 ? " song" : " songs");
            msg.append(" inside it");
        }
        msg.append(".\n\nSongs that are also in your other folders stay there. Nothing is deleted from "
                + "Google Drive or your phone");
        if (p.driveFolder != null) msg.append(", and Echo stops syncing this Drive folder");
        msg.append(".");
        new AlertDialog.Builder(a).setTitle("Delete folder").setMessage(msg.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    int n = lib.deleteTreeWithSongs(p);
                    Toast.makeText(a, "Deleted \"" + p.name + "\"" + (n > 0 ? " and " + n + (n == 1 ? " song" : " songs") : ""),
                            Toast.LENGTH_SHORT).show();
                    after.run();
                }).show();
    }

    /** Lets you choose a folder. With allowTop, the first choice is the top level. */
    static void pick(Activity a, String title, List<Library.Playlist> targets, boolean allowTop, PickCallback cb) {
        Library lib = Library.get(a);
        int off = allowTop ? 1 : 0;
        String[] names = new String[targets.size() + off];
        if (allowTop) names[0] = "⌂  Playlists (top level)";
        for (int i = 0; i < targets.size(); i++) names[i + off] = lib.path(targets.get(i));
        if (names.length == 0) {
            Toast.makeText(a, "There are no folders yet", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a).setTitle(title)
                .setItems(names, (d, which) -> cb.onPick(which < off ? null : targets.get(which - off)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void askName(Activity a, String title, String def, NameCallback cb) {
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        LinearLayout box = Ui.column(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(a).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    String n = e.getText().toString().trim();
                    cb.onName(n.isEmpty() ? def : n);
                }).show();
        e.requestFocus();
    }
}
