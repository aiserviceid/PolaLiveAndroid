package com.polalive.app;

import java.util.List;
import java.util.Locale;

public final class AnalysisEngine {
    private AnalysisEngine() {}

    private static char bucket(double v) {
        if (v < 2.0) return 'L';
        if (v < 5.0) return 'M';
        return 'H';
    }

    private static int distanceSince(List<Double> d, double threshold) {
        for (int i = d.size() - 1, distance = 0; i >= 0; i--, distance++) {
            if (d.get(i) >= threshold) return distance;
        }
        return d.size();
    }

    private static int streakBelow(List<Double> d, double threshold) {
        int s = 0;
        for (int i = d.size() - 1; i >= 0; i--) {
            if (d.get(i) < threshold) s++; else break;
        }
        return s;
    }

    public static String summary(List<Double> d, long ocrMs) {
        if (d.isEmpty()) {
            return "LIVE • N=0\nBelum ada hasil.\nTap AREA lalu letakkan kotak pada multiplier terbaru.";
        }

        int n = d.size();
        double last = d.get(n - 1);
        int streak2 = streakBelow(d, 2.0);
        int since5 = distanceSince(d, 5.0);
        int since10 = distanceSince(d, 10.0);

        int w = Math.min(20, n);
        int low20 = 0, high20 = 0;
        for (int i = n - w; i < n; i++) {
            double v = d.get(i);
            if (v < 2.0) low20++;
            if (v >= 5.0) high20++;
        }

        int len = Math.min(4, n);
        StringBuilder pattern = new StringBuilder();
        for (int i = n - len; i < n; i++) pattern.append(bucket(d.get(i)));

        int cL = 0, cM = 0, cH = 0, samples = 0;
        if (len >= 2) {
            for (int i = 0; i + len < n; i++) {
                boolean same = true;
                for (int j = 0; j < len; j++) {
                    if (bucket(d.get(i + j)) != pattern.charAt(j)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    char next = bucket(d.get(i + len));
                    samples++;
                    if (next == 'L') cL++;
                    else if (next == 'M') cM++;
                    else cH++;
                }
            }
        }

        String historical;
        if (samples == 0) {
            historical = "Pola " + pattern + " • belum ada sampel historis";
        } else {
            historical = String.format(Locale.US,
                    "Pola %s • sampel %d\nSesudah pola: <2 %.0f%% | 2–5 %.0f%% | 5+ %.0f%%",
                    pattern, samples,
                    100.0 * cL / samples,
                    100.0 * cM / samples,
                    100.0 * cH / samples);
            if (samples < 10) historical += "\n(sampel masih kecil)";
        }

        return String.format(Locale.US,
                "LIVE • N=%d • OCR %dms\nTerakhir %.2fx\n<2 streak %d • ≥5: %d ronde • ≥10: %d ronde\n20R: <2 %.0f%% • 5+ %.0f%%\n%s",
                n, ocrMs, last, streak2, since5, since10,
                100.0 * low20 / w,
                100.0 * high20 / w,
                historical);
    }
}
