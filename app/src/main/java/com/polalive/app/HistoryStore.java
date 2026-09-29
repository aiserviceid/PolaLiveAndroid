package com.polalive.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryStore {
    private static final String PREF = "polalive_store";
    private static final String KEY_HISTORY = "history";
    private static final int MAX = 300;

    private final SharedPreferences prefs;

    public HistoryStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized List<Double> load() {
        String raw = prefs.getString(KEY_HISTORY, "");
        List<Double> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (String part : parts) {
            try {
                double v = Double.parseDouble(part);
                if (v >= 1.0 && v <= 100000) out.add(v);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public synchronized void add(double value) {
        List<Double> data = load();
        data.add(value);
        while (data.size() > MAX) data.remove(0);
        save(data);
    }

    public synchronized void undo() {
        List<Double> data = load();
        if (!data.isEmpty()) data.remove(data.size() - 1);
        save(data);
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    private void save(List<Double> data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.2f", data.get(i)));
        }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
    }
}
