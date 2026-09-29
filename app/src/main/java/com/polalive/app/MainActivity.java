package com.polalive.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private HistoryStore store;
    private TextView status;
    private EditText manual;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new HistoryStore(this);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        refresh();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(30));
        root.setBackgroundColor(Color.rgb(15,16,32));
        scroll.addView(root);

        TextView title = text("PolaLive", 28, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = text("Overlay analisis live • OCR lokal • riwayat maks. 300 ronde", 14, 0xFFB8B8C8);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        TextView guide = text(
                "Cara pakai:\n1. Izinkan tampil di atas aplikasi lain.\n2. Tekan MULAI LIVE dan izinkan rekam layar.\n3. Buka game, tap AREA pada overlay, geser kotak ke multiplier terbaru, lalu SIMPAN.\n\nKotak AREA hanya untuk kalibrasi dan otomatis disembunyikan saat pembacaan live.",
                15, Color.WHITE);
        guide.setLineSpacing(0, 1.15f);
        root.addView(guide);

        Button overlay = button("1 • IZINKAN OVERLAY");
        overlay.setOnClickListener(v -> requestOverlay());
        root.addView(overlay, lp());

        Button start = button("2 • MULAI LIVE");
        start.setOnClickListener(v -> startCaptureFlow());
        root.addView(start, lp());

        Button stop = button("STOP LIVE");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
        });
        root.addView(stop, lp());

        status = text("", 15, 0xFFE9E9F1);
        status.setPadding(0, dp(20), 0, dp(10));
        root.addView(status);

        TextView manualTitle = text("Tes / input manual", 17, Color.WHITE);
        manualTitle.setTypeface(null, 1);
        manualTitle.setPadding(0, dp(14), 0, dp(6));
        root.addView(manualTitle);

        manual = new EditText(this);
        manual.setHint("contoh: 1.37");
        manual.setHintTextColor(0xFF77778A);
        manual.setTextColor(Color.WHITE);
        manual.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(manual, lp());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button add = button("TAMBAH");
        Button undo = button("UNDO");
        row.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        undoLp.setMargins(dp(8),0,0,0);
        row.addView(undo, undoLp);
        root.addView(row, lp());

        add.setOnClickListener(v -> addManual());
        undo.setOnClickListener(v -> { store.undo(); refresh(); notifyOverlay(); });

        Button clear = button("HAPUS SEMUA DATA");
        clear.setOnClickListener(v -> { store.clear(); refresh(); notifyOverlay(); });
        root.addView(clear, lp());

        TextView note = text(
                "Catatan: aplikasi ini membaca multiplier yang terlihat di layar dan menampilkan statistik historis. Pola historis tidak menjamin hasil ronde berikutnya. Jika aplikasi sumber memblokir screen capture (FLAG_SECURE), OCR tidak dapat membaca layar tersebut.",
                13, 0xFF9D9DAC);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin overlay sudah aktif", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void startCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan izin overlay terlebih dahulu", Toast.LENGTH_LONG).show();
            requestOverlay();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_START);
            i.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Toast.makeText(this, "Live dimulai. Buka game lalu kalibrasi AREA.", Toast.LENGTH_LONG).show();
        }
    }

    private void addManual() {
        try {
            String raw = manual.getText().toString().trim().replace(',', '.').replace("x", "");
            double v = Double.parseDouble(raw);
            if (v < 1.0 || v > 100000) throw new NumberFormatException();
            store.add(v);
            manual.setText("");
            refresh();
            notifyOverlay();
        } catch (Exception e) {
            Toast.makeText(this, "Masukkan multiplier valid, mis. 1.37", Toast.LENGTH_SHORT).show();
        }
    }

    private void notifyOverlay() {
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(CaptureService.ACTION_REFRESH);
        startService(i);
    }

    private void refresh() {
        List<Double> d = store.load();
        StringBuilder recent = new StringBuilder();
        int start = Math.max(0, d.size() - 12);
        for (int i = d.size() - 1; i >= start; i--) {
            if (recent.length() > 0) recent.append("  ");
            recent.append(String.format(Locale.US, "%.2fx", d.get(i)));
        }
        status.setText(AnalysisEngine.summary(d, 0) + "\n\nTerbaru:\n" + (recent.length() == 0 ? "—" : recent));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(8), 0, 0);
        return p;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8001);
        }
    }
}
