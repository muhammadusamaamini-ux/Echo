package com.usama.echo;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A list of songs: tile, title, artist and a ⋮ menu button. */
final class SongAdapter extends BaseAdapter {
    interface More {
        void onMore(View anchor, Library.Song song, int position);
    }

    private final Context ctx;
    private final More more;
    private List<Library.Song> items = new ArrayList<>();
    private String currentId;

    SongAdapter(Context ctx, More more) {
        this.ctx = ctx;
        this.more = more;
    }

    void setItems(List<Library.Song> list) {
        items = list;
        notifyDataSetChanged();
    }

    List<Library.Song> items() {
        return items;
    }

    void setCurrent(String id) {
        boolean changed = id == null ? currentId != null : !id.equals(currentId);
        currentId = id;
        if (changed) notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    private static final class Holder {
        TextView tile, title, sub;
        ImageButton more;
    }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        Holder h;
        if (convert == null) {
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
        h.tile.setText(letter);
        h.tile.setBackground(Ui.round(Ui.colorFor(s.title), Ui.dp(ctx, 6)));
        h.title.setText(s.title);
        h.title.setTextColor(s.id.equals(currentId) ? Ui.ACCENT : Ui.TEXT);
        h.sub.setText(s.subtitle());
        final int pos = position;
        h.more.setOnClickListener(v -> more.onMore(v, s, pos));
        return convert;
    }
}
