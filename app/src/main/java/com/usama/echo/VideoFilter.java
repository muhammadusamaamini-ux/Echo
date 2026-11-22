package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Filter for videos: length, date, size and name (same choices as on the PC). Kept while Echo is open. */
final class VideoFilter {
    private VideoFilter() {
    }

    static String len = "", date = "", size = "", text = "";
    static double lenMin = -1, lenMax = -1; // minutes ("Between…")
    static long from = 0, to = 0;           // ms ("Between dates…")

    static final String[] LEN_CODES = {"", "u5", "5-20", "20-60", "o60", "custom"};
    static final String[] LEN_LABELS = {"Any length", "Under 5 minutes", "5 – 20 minutes", "20 – 60 minutes", "Over 1 hour", "Between…"};
    static final String[] DATE_CODES = {"", "today", "7", "30", "year", "custom"};
    static final String[] DATE_LABELS = {"Any date", "Today", "Last 7 days", "Last 30 days", "This year", "Between dates…"};
    static final String[] SIZE_CODES = {"", "u100", "100-1000", "o1000"};
    static final String[] SIZE_LABELS = {"Any size", "Under 100 MB", "100 MB – 1 GB", "Over 1 GB"};

    static boolean active() {
        return !len.isEmpty() || !date.isEmpty() || !size.isEmpty() || !text.trim().isEmpty();
    }

    static void clear() {
        len = date = size = text = "";
        lenMin = lenMax = -1;
        from = to = 0;
    }

    private static long ms(Videos.Video v) {
        return v.date < 100_000_000_000L ? v.date * 1000 : v.date; // MediaStore gives seconds
    }

    static boolean match(Videos.Video v) {
        double m = v.duration / 60000.0;
        switch (len) {
            case "u5": if (!(m < 5)) return false; break;
            case "5-20": if (!(m >= 5 && m < 20)) return false; break;
            case "20-60": if (!(m >= 20 && m < 60)) return false; break;
            case "o60": if (!(m >= 60)) return false; break;
            case "custom": if ((lenMin >= 0 && m < lenMin) || (lenMax >= 0 && m > lenMax)) return false; break;
            default: break;
        }
        long t = ms(v), now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        switch (date) {
            case "today": if (t < c.getTimeInMillis()) return false; break;
            case "7": if (t < now - 7 * 86_400_000L) return false; break;
            case "30": if (t < now - 30 * 86_400_000L) return false; break;
            case "year": c.set(Calendar.DAY_OF_YEAR, 1); if (t < c.getTimeInMillis()) return false; break;
            case "custom": if ((from > 0 && t < from) || (to > 0 && t > to + 86_399_999L)) return false; break;
            default: break;
        }
        double mb = v.size / 1048576.0;
        switch (size) {
            case "u100": if (!(mb < 100)) return false; break;
            case "100-1000": if (!(mb >= 100 && mb < 1024)) return false; break;
            case "o1000": if (!(mb >= 1024)) return false; break;
            default: break;
        }
        String q = text.trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() || (v.title != null && v.title.toLowerCase(Locale.ROOT).contains(q));
    }

    static List<Videos.Video> apply(List<Videos.Video> in) {
        List<Videos.Video> out = new ArrayList<>();
        for (Videos.Video v : in) if (match(v)) out.add(v);
        return out;
    }

    private static String label(String[] codes, String[] labels, String code) {
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(code)) return labels[i];
        return "";
    }

    static String summary() {
        List<String> p = new ArrayList<>();
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        if (len.equals("custom")) p.add((lenMin >= 0 ? fmt(lenMin) : "0") + "–" + (lenMax >= 0 ? fmt(lenMax) : "∞") + " min");
        else if (!len.isEmpty()) p.add(label(LEN_CODES, LEN_LABELS, len).toLowerCase(Locale.ROOT));
        if (date.equals("custom")) p.add((from > 0 ? df.format(from) : "…") + " to " + (to > 0 ? df.format(to) : "today"));
        else if (!date.isEmpty()) p.add(label(DATE_CODES, DATE_LABELS, date).toLowerCase(Locale.ROOT));
        if (!size.isEmpty()) p.add(label(SIZE_CODES, SIZE_LABELS, size).toLowerCase(Locale.ROOT));
        if (!text.trim().isEmpty()) p.add("name has \"" + text.trim() + "\"");
        return android.text.TextUtils.join(" · ", p);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** The filter screen. after runs when you press Show or Clear. */
    static void show(Activity a, Runnable after) {
        LinearLayout box = Ui.column(a);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, Ui.dp(a, 8), pad, 0);
        final String[] pick = {len, date, size};
        // length
        final EditText min = num(a, lenMin), max = num(a, lenMax);
        LinearLayout lenRow = Ui.row(a);
        lenRow.addView(min, Ui.weight());
        lenRow.addView(Ui.text(a, "  to  ", 14, Ui.SUB, false));
        lenRow.addView(max, Ui.weight());
        lenRow.addView(Ui.text(a, "  min", 14, Ui.SUB, false));
        section(a, box, "Length", LEN_CODES, LEN_LABELS, 0, pick, lenRow);
        // date
        final long[] range = {from, to};
        LinearLayout dateRow = Ui.row(a);
        final TextView fromB = Ui.pill(a, range[0] > 0 ? DateFormat.getDateInstance().format(range[0]) : "From…", false);
        final TextView toB = Ui.pill(a, range[1] > 0 ? DateFormat.getDateInstance().format(range[1]) : "To…", false);
        fromB.setOnClickListener(v -> pickDate(a, range, 0, fromB));
        toB.setOnClickListener(v -> pickDate(a, range, 1, toB));
        dateRow.addView(fromB);
        dateRow.addView(Ui.text(a, "  to  ", 14, Ui.SUB, false));
        dateRow.addView(toB);
        section(a, box, "Date", DATE_CODES, DATE_LABELS, 1, pick, dateRow);
        section(a, box, "Size", SIZE_CODES, SIZE_LABELS, 2, pick, null);
        box.addView(Ui.text(a, "Name", 15, Ui.TEXT, true));
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setHint("Part of the name (optional)");
        name.setText(text);
        box.addView(name);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("Filter videos").setView(sv)
                .setPositiveButton("Show", (d, w) -> {
                    len = pick[0]; date = pick[1]; size = pick[2];
                    lenMin = parse(min); lenMax = parse(max);
                    from = range[0]; to = range[1];
                    text = name.getText().toString();
                    after.run();
                })
                .setNeutralButton("Clear", (d, w) -> { clear(); after.run(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void section(Activity a, LinearLayout box, String title, String[] codes, String[] labels, int slot, String[] pick, LinearLayout extra) {
        TextView t = Ui.text(a, title, 15, Ui.TEXT, true);
        t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4));
        box.addView(t);
        RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < codes.length; i++) {
            RadioButton r = new RadioButton(a);
            r.setText(labels[i]);
            r.setTextColor(Ui.TEXT);
            r.setId(1000 + slot * 100 + i);
            g.addView(r);
            if (codes[i].equals(pick[slot])) r.setChecked(true);
        }
        box.addView(g);
        if (extra != null) {
            extra.setVisibility("custom".equals(pick[slot]) ? android.view.View.VISIBLE : android.view.View.GONE);
            box.addView(extra);
        }
        g.setOnCheckedChangeListener((gr, id) -> {
            pick[slot] = codes[id - 1000 - slot * 100];
            if (extra != null) extra.setVisibility("custom".equals(pick[slot]) ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }

    private static EditText num(Activity a, double v) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine(true);
        if (v >= 0) e.setText(fmt(v));
        return e;
    }

    private static double parse(EditText e) {
        try {
            String s = e.getText().toString().trim();
            return s.isEmpty() ? -1 : Double.parseDouble(s);
        } catch (Exception x) {
            return -1;
        }
    }

    private static void pickDate(Activity a, long[] range, int which, TextView label) {
        Calendar c = Calendar.getInstance();
        if (range[which] > 0) c.setTimeInMillis(range[which]);
        new DatePickerDialog(a, (dp, y, m, d) -> {
            Calendar k = Calendar.getInstance();
            k.set(y, m, d, 0, 0, 0);
            k.set(Calendar.MILLISECOND, 0);
            range[which] = k.getTimeInMillis();
            label.setText(DateFormat.getDateInstance().format(range[which]));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
}
