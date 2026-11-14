package com.usama.echo;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ListView;

/**
 * A list that can be shown as rows (☰) or as tiles (▦). The choice is remembered for the whole app.
 * The adapter asks {@link #isGrid(Context)} to decide whether to make a row or a tile.
 */
final class ListGrid extends FrameLayout {
    final ListView list;
    final GridView grid;
    private BaseAdapter adapter;

    ListGrid(Context c) {
        super(c);
        list = new ListView(c);
        list.setDivider(null);
        grid = new GridView(c);
        grid.setNumColumns(2);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(c, 8));
        grid.setVerticalSpacing(Ui.dp(c, 8));
        int p = Ui.dp(c, 12);
        grid.setPadding(p, Ui.dp(c, 4), p, p);
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        apply();
    }

    static boolean isGrid(Context c) {
        return c.getSharedPreferences("view", Context.MODE_PRIVATE).getBoolean("grid", false);
    }

    static void setGrid(Context c, boolean on) {
        c.getSharedPreferences("view", Context.MODE_PRIVATE).edit().putBoolean("grid", on).apply();
    }

    private View empty;

    void setAdapter(BaseAdapter a) {
        adapter = a;
        list.setAdapter(a);
        grid.setAdapter(a);
        a.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                showRightView();
            }
        });
        showRightView();
    }

    void setOnItemClickListener(AdapterView.OnItemClickListener l) {
        list.setOnItemClickListener(l);
        grid.setOnItemClickListener(l);
    }

    void setOnItemLongClickListener(AdapterView.OnItemLongClickListener l) {
        list.setOnItemLongClickListener(l);
        grid.setOnItemLongClickListener(l);
    }

    /** Shown instead of the list when it has nothing in it. */
    void setEmptyView(View v) {
        empty = v;
        showRightView();
    }

    /** Shows rows or tiles to match the saved choice. */
    void apply() {
        showRightView();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void showRightView() {
        boolean g = isGrid(getContext());
        boolean none = adapter == null || adapter.isEmpty();
        if (empty != null) empty.setVisibility(none ? VISIBLE : GONE);
        boolean showList = !(none && empty != null);
        list.setVisibility(showList && !g ? VISIBLE : GONE);
        grid.setVisibility(showList && g ? VISIBLE : GONE);
    }

    /** The ☰ / ▦ button. It shows the view you'll switch to. */
    static ImageButton toggleButton(Context c, Runnable onChange) {
        final ImageButton b = Ui.icon(c, isGrid(c) ? R.drawable.ic_view_list : R.drawable.ic_view_grid, 44, Ui.TEXT);
        b.setOnClickListener(v -> {
            boolean now = !isGrid(c);
            setGrid(c, now);
            b.setImageResource(now ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
            android.widget.Toast.makeText(c, now ? "Tile view" : "List view", android.widget.Toast.LENGTH_SHORT).show();
            onChange.run();
        });
        return b;
    }

    /** Keeps a toggle button's picture right (e.g. after coming back from another screen). */
    static void syncButton(ImageButton b, Context c) {
        b.setImageResource(isGrid(c) ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
    }
}
