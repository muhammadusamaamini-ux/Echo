package com.usama.echo;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A list of songs: tile, title, artist and a ⋮ menu button. */
final class SongAdapter extends BaseAdapter {
    interface More {
        void onMore(View anchor, Library.Song song, int position);
    }

    private final Context ctx;
    private final More more;
    private List<Library.Song> items = new ArrayList<>();
    private String currentId;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;

    SongAdapter(Context ctx, More more) {
        this.ctx = ctx;
        this.more = more;
    }

    void setItems(List<Library.Song> list) {
        items = list;
        // forget selected songs that are no longer in the list
        Set<String> ids = new java.util.HashSet<>();
        for (Library.Song s : list) ids.add(s.id);
        selected.retainAll(ids);
        notifyDataSetChanged();
    }

    // ---------- selection ----------

    boolean selecting() { return selecting; }

    void setSelecting(boolean on) {
        selecting = on;
        notifyDataSetChanged();
    }

    void toggle(int position) {
        if (position < 0 || position >= items.size()) return;
        String id = items.get(position).id;
        if (!selected.remove(id)) selected.add(id);
        selecting = true;
        notifyDataSetChanged();
    }

    void selectAll() {
        for (Library.Song s : items) selected.add(s.id);
        selecting = true;
        notifyDataSetChanged();
    }

    void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
    }

    int selectedCount() { return selected.size(); }

    List<Library.Song> selectedSongs() {
        List<Library.Song> out = new ArrayList<>();
        for (Library.Song s : items) if (selected.contains(s.id)) out.add(s);
        return out;
    }

    List<Library.Song> items() {
        return items;
    }

    void setCurrent(String id) {
        boolean changed = id == null ? currentId != null : !id.equals(currentId);
        currentId = id;
        if (changed) notifyDataSetChanged();
    }

    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int i) { return ListGrid.isGrid(ctx) ? 1 : 0; }
    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    private static final class Holder {
        boolean grid;
        TextView tile, title, sub;
        ImageButton more;
    }

    /** A tile (grid view) for a song. */
    private View tile(int position, View convert) {
        Holder h;
        if (convert == null || !(convert.getTag() instanceof Holder) || ((Holder) convert.getTag()).grid == false) {
            LinearLayout c = Ui.column(ctx);
            c.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 6));
            h = new Holder();
            h.grid = true;
            h.tile = Ui.text(ctx, "", 40, Ui.TEXT, true);
            h.tile.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 104));
            tlp.rightMargin = Ui.dp(ctx, 6);
            c.addView(h.tile, tlp);
            LinearLayout row = Ui.row(ctx);
            row.setPadding(0, Ui.dp(ctx, 6), 0, 0);
            LinearLayout col = Ui.column(ctx);
            h.title = Ui.line(ctx, "", 14, Ui.TEXT, true);
            h.sub = Ui.line(ctx, "", 11, Ui.SUB, false);
            col.addView(h.title);
            col.addView(h.sub);
            row.addView(col, Ui.weight());
            h.more = Ui.icon(ctx, R.drawable.ic_more, 34, Ui.SUB);
            row.addView(h.more);
            c.addView(row);
            c.setTag(h);
            convert = c;
        } else {
            h = (Holder) convert.getTag();
        }
        final Library.Song s = items.get(position);
        boolean sel = selected.contains(s.id);
        String letter = s.title == null || s.title.trim().isEmpty() ? "♪" :
                s.title.trim().substring(0, s.title.trim().offsetByCodePoints(0, 1)).toUpperCase();
        h.tile.setText(sel ? "✓" : letter);
        h.tile.setBackground(sel ? Ui.accentGrad(Ui.dp(ctx, 12)) : Ui.gradFor(s.title, Ui.dp(ctx, 12)));
        convert.setBackground(sel ? Ui.round(0x3300D2FF, Ui.dp(ctx, 16)) : Ui.glass(ctx, 16));
        h.title.setText(s.title);
        h.title.setTextColor(s.id.equals(currentId) ? Ui.ACCENT : Ui.TEXT);
        h.sub.setText(s.artist == null || s.artist.isEmpty() ? ("drive".equals(s.source) ? "Drive" : "Unknown artist") : s.artist);
        h.more.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
        final int pos = position;
        h.more.setOnClickListener(v -> more.onMore(v, s, pos));
        return convert;
    }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        if (ListGrid.isGrid(ctx)) return tile(position, convert);
        Holder h;
        if (convert == null || !(convert.getTag() instanceof Holder) || ((Holder) convert.getTag()).grid) {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8));
            h = new Holder();
            h.tile = Ui.tile(ctx, "", 48, Ui.SURFACE2);
            row.addView(h.tile);
            LinearLayout col = Ui.column(ctx);
            col.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0);
            h.title = Ui.line(ctx, "", 16, Ui.TEXT, false);
            h.sub = Ui.line(ctx, "", 13, Ui.SUB, false);
            col.addView(h.title);
            col.addView(h.sub);
            row.addView(col, Ui.weight());
            h.more = Ui.icon(ctx, R.drawable.ic_more, 40, Ui.SUB);
            row.addView(h.more);
            row.setTag(h);
            convert = row;
        } else {
            h = (Holder) convert.getTag();
        }
        final Library.Song s = items.get(position);
        String letter = s.title == null || s.title.trim().isEmpty() ? "♪" :
                s.title.trim().substring(0, s.title.trim().offsetByCodePoints(0, 1)).toUpperCase();
        boolean sel = selected.contains(s.id);
        h.tile.setText(sel ? "✓" : letter);
        h.tile.setBackground(sel ? Ui.accentGrad(Ui.dp(ctx, 10)) : Ui.gradFor(s.title, Ui.dp(ctx, 10)));
        convert.setBackgroundColor(sel ? 0x3300D2FF : 0);
        h.more.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
        h.title.setText(s.title);
        h.title.setTextColor(s.id.equals(currentId) ? Ui.ACCENT : Ui.TEXT);
        h.sub.setText(s.subtitle());
        final int pos = position;
        h.more.setOnClickListener(v -> more.onMore(v, s, pos));
        return convert;
    }
}
