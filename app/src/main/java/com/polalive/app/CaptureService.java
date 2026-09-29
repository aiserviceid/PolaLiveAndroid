package com.polalive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.polalive.START";
    public static final String ACTION_STOP = "com.polalive.STOP";
    public static final String ACTION_REFRESH = "com.polalive.REFRESH";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final int NOTIF_ID = 41;
    private static final String CHANNEL = "polalive_live";
    private static final Pattern MULT = Pattern.compile("(\\d{1,4}(?:[\\.,]\\d{1,2})?)\\s*[xX×]");
    private static final Pattern COUNTDOWN = Pattern.compile("\\b\\d{1,2}\\s*[sS]\\b");

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View panel;
    private TextView stats;
    private Button areaButton;
    private View cropBox;
    private WindowManager.LayoutParams cropParams;
    private boolean calibrating = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer recognizer;
    private boolean processing = false;
    private long lastFrameAt = 0L;
    private long lastOcrMs = 0L;

    private boolean armed = true;
    private int noMultiplierFrames = 0;
    private Double candidate = null;
    private int candidateCount = 0;
    private long lastAcceptedAt = 0L;
    private double lastAccepted = -1;

    private int screenW, screenH, densityDpi;
    private HistoryStore store;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        store = new HistoryStore(this);
        prefs = getSharedPreferences("polalive_store", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        readMetrics();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            if (panel != null) {
                refreshOverlay();
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification());
            if (!Settings.canDrawOverlays(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (panel == null) createPanel();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) {
                data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                //noinspection deprecation
                data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (data != null && projection == null) startProjection(resultCode, data);
            refreshOverlay();
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void createPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD11131E);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFF6C4DFF);
        box.setBackground(bg);

        stats = new TextView(this);
        stats.setTextColor(Color.WHITE);
        stats.setTextSize(12);
        stats.setLineSpacing(0, 1.05f);
        box.addView(stats, new LinearLayout.LayoutParams(dp(255), -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        areaButton = smallButton("AREA");
        Button undo = smallButton("UNDO");
        Button close = smallButton("×");
        row.addView(areaButton, new LinearLayout.LayoutParams(0, dp(38), 1));
        row.addView(undo, new LinearLayout.LayoutParams(0, dp(38), 1));
        row.addView(close, new LinearLayout.LayoutParams(dp(46), dp(38)));
        box.addView(row);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(275), WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(8);
        p.y = dp(48);
        wm.addView(box, p);
        panel = box;

        makeDraggable(stats, box, p, false);
        areaButton.setOnClickListener(v -> toggleCalibration());
        undo.setOnClickListener(v -> { store.undo(); refreshOverlay(); });
        close.setOnClickListener(v -> stopEverything());
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(2,0,2,0);
        return b;
    }

    private void toggleCalibration() {
        if (!calibrating) {
            calibrating = true;
            areaButton.setText("SIMPAN");
            showCropBox();
        } else {
            calibrating = false;
            areaButton.setText("AREA");
            saveCropFromParams();
            hideCropBox();
            armed = true;
            noMultiplierFrames = 0;
            candidate = null;
            candidateCount = 0;
        }
    }

    private void showCropBox() {
        if (cropBox != null) return;
        readMetrics();
        float nx = prefs.getFloat("crop_x", 0.03f);
        float ny = prefs.getFloat("crop_y", 0.54f);
        float nw = prefs.getFloat("crop_w", 0.22f);
        float nh = prefs.getFloat("crop_h", 0.055f);
        int w = Math.max(dp(80), Math.round(screenW * nw));
        int h = Math.max(dp(35), Math.round(screenH * nh));

        TextView v = new TextView(this);
        v.setText("  GESER AREA HASIL");
        v.setTextColor(0xFFFFD54F);
        v.setTextSize(10);
        v.setGravity(Gravity.TOP | Gravity.LEFT);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x08000000);
        gd.setStroke(dp(2), 0xFFFFD54F);
        gd.setCornerRadius(dp(8));
        v.setBackground(gd);

        cropParams = new WindowManager.LayoutParams(
                w, h, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        cropParams.gravity = Gravity.TOP | Gravity.START;
        cropParams.x = Math.max(0, Math.min(screenW - w, Math.round(screenW * nx)));
        cropParams.y = Math.max(0, Math.min(screenH - h, Math.round(screenH * ny)));
        wm.addView(v, cropParams);
        cropBox = v;
        makeDraggable(v, v, cropParams, true);
    }

    private void hideCropBox() {
        if (cropBox != null) {
            try { wm.removeView(cropBox); } catch (Exception ignored) {}
            cropBox = null;
            cropParams = null;
        }
    }

    private void makeDraggable(View touchView, View windowView, WindowManager.LayoutParams p, boolean clamp) {
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        touchView.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = p.x; startY[0] = p.y;
                    downX[0] = e.getRawX(); downY[0] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    p.x = startX[0] + Math.round(e.getRawX() - downX[0]);
                    p.y = startY[0] + Math.round(e.getRawY() - downY[0]);
                    if (clamp) {
                        p.x = Math.max(0, Math.min(screenW - p.width, p.x));
                        p.y = Math.max(0, Math.min(screenH - p.height, p.y));
                    }
                    try { wm.updateViewLayout(windowView, p); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });
    }

    private void saveCropFromParams() {
        if (cropParams == null) return;
        prefs.edit()
                .putFloat("crop_x", cropParams.x / (float)screenW)
                .putFloat("crop_y", cropParams.y / (float)screenH)
                .putFloat("crop_w", cropParams.width / (float)screenW)
                .putFloat("crop_h", cropParams.height / (float)screenH)
                .apply();
    }

    private void startProjection(int resultCode, Intent data) {
        readMetrics();
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> stopEverything()); }
        }, main);

        captureThread = new HandlerThread("PolaLiveCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "PolaLiveDisplay",
                screenW, screenH, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        imageReader.setOnImageAvailableListener(this::onImage, captureHandler);
    }

    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            if (processing || calibrating || now - lastFrameAt < 180) return;
            lastFrameAt = now;
            processing = true;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenW;
            int bmpW = screenW + rowPadding / pixelStride;
            Bitmap wide = Bitmap.createBitmap(bmpW, screenH, Bitmap.Config.ARGB_8888);
            wide.copyPixelsFromBuffer(buffer);

            int[] r = cropRect();
            int left = Math.max(0, Math.min(screenW - 1, r[0]));
            int top = Math.max(0, Math.min(screenH - 1, r[1]));
            int right = Math.max(left + 1, Math.min(screenW, r[0] + r[2]));
            int bottom = Math.max(top + 1, Math.min(screenH, r[1] + r[3]));
            Bitmap crop = Bitmap.createBitmap(wide, left, top, right - left, bottom - top);
            wide.recycle();

            long started = SystemClock.elapsedRealtime();
            InputImage input = InputImage.fromBitmap(crop, 0);
            Task<Text> task = recognizer.process(input);
            task.addOnSuccessListener(text -> {
                lastOcrMs = SystemClock.elapsedRealtime() - started;
                handleText(text.getText());
            }).addOnFailureListener(e -> {
                noMultiplierFrames++;
            }).addOnCompleteListener(t -> {
                crop.recycle();
                processing = false;
            });
        } catch (Exception e) {
            processing = false;
        } finally {
            if (image != null) image.close();
        }
    }

    private int[] cropRect() {
        float nx = prefs.getFloat("crop_x", 0.03f);
        float ny = prefs.getFloat("crop_y", 0.54f);
        float nw = prefs.getFloat("crop_w", 0.22f);
        float nh = prefs.getFloat("crop_h", 0.055f);
        return new int[]{Math.round(screenW * nx), Math.round(screenH * ny),
                Math.round(screenW * nw), Math.round(screenH * nh)};
    }

    private void handleText(String raw) {
        if (raw == null) raw = "";
        Matcher m = MULT.matcher(raw.replace('\n',' '));
        if (m.find()) {
            noMultiplierFrames = 0;
            try {
                double value = Double.parseDouble(m.group(1).replace(',', '.'));
                if (value < 1.0 || value > 100000) return;
                if (candidate != null && Math.abs(candidate - value) < 0.005) candidateCount++;
                else { candidate = value; candidateCount = 1; }

                if (armed && candidateCount >= 2) {
                    long now = SystemClock.elapsedRealtime();
                    // Safety debounce only; identical values are still accepted after a real countdown/gap.
                    if (!(Math.abs(lastAccepted - value) < 0.005 && now - lastAcceptedAt < 1200)) {
                        store.add(value);
                        lastAccepted = value;
                        lastAcceptedAt = now;
                    }
                    armed = false;
                    candidateCount = 0;
                    refreshOverlay();
                }
            } catch (Exception ignored) {}
        } else {
            candidate = null;
            candidateCount = 0;
            if (COUNTDOWN.matcher(raw).find()) {
                armed = true;
                noMultiplierFrames = 0;
            } else {
                noMultiplierFrames++;
                if (noMultiplierFrames >= 3) armed = true;
            }
        }
    }

    private void refreshOverlay() {
        main.post(() -> {
            if (stats == null) return;
            List<Double> data = store.load();
            stats.setText(AnalysisEngine.summary(data, lastOcrMs));
        });
    }

    private void readMetrics() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        densityDpi = dm.densityDpi;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readMetrics();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "PolaLive", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Analisis live sedang berjalan");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("PolaLive aktif")
                .setContentText("Membaca area multiplier dan memperbarui overlay")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void stopEverything() {
        hideCropBox();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null; stats = null;
        }
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) {}
        try { if (captureThread != null) captureThread.quitSafely(); } catch (Exception ignored) {}
        imageReader = null; virtualDisplay = null; projection = null; captureThread = null; captureHandler = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (panel != null || projection != null) stopEverything();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
