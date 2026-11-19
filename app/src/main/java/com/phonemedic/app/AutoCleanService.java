package com.phonemedic.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Automatise des gestes que l'utilisateur ferait lui-même dans les réglages :
 * ouvrir la fiche d'une appli puis appuyer sur « Vider le cache », ou confirmer une désinstallation.
 * Le service n'agit que pendant une série lancée depuis Phone Medic ; le reste du temps il ignore tout.
 */
public class AutoCleanService extends AccessibilityService {

    static AutoCleanService instance;
    static volatile boolean running;

    private static final String[] STORAGE = {"stockage et cache", "stockage et memoire cache", "stockage", "memoire de stockage",
            "storage & cache", "storage and cache", "storage", "effacer les donnees"};
    private static final String[] CLEAR = {"vider le cache", "effacer le cache", "vider la memoire cache", "effacer la memoire cache",
            "supprimer le cache", "clear cache"};
    private static final String[] CONFIRM = {"ok", "desinstaller", "uninstall", "supprimer", "oui"};
    private static final long STEP_TIMEOUT = 7000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private String mode, current;
    private int step, done, skipped, total;
    private long stepStart;
    private boolean scrolled;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* l'avancement se fait par tick() */ }

    @Override
    public void onInterrupt() {}

    // ------------------------------------------------------------------ pilotage

    static boolean start(String mode, List<String> pkgs) {
        if (instance == null || pkgs.isEmpty()) return false;
        instance.begin(mode, pkgs);
        return true;
    }

    static void stop() {
        if (instance != null && running) instance.finish(true);
    }

    private void begin(String m, List<String> pkgs) {
        mode = m;
        queue.clear();
        queue.addAll(pkgs);
        total = pkgs.size();
        done = 0;
        skipped = 0;
        running = true;
        next();
    }

    private void next() {
        h.removeCallbacksAndMessages(null);
        if (!running) return;
        current = queue.poll();
        if (current == null) { finish(false); return; }
        step = 0;
        scrolled = false;
        stepStart = SystemClock.uptimeMillis();
        Intent i = "uninstall".equals(mode)
                ? new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + current))
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + current));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception e) {
            skipped++;
            h.postDelayed(this::next, 300);
            return;
        }
        h.postDelayed(this::tick, 800);
    }

    private void tick() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();

        if ("uninstall".equals(mode) && step == 1) {
            if (!isInstalled(current)) { done++; h.postDelayed(this::next, 400); return; }
            if (now - stepStart > STEP_TIMEOUT) { skipped++; next(); return; }
            h.postDelayed(this::tick, 400);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && act(root, now)) return;
        if (now - stepStart > STEP_TIMEOUT) { skipped++; next(); return; }
        h.postDelayed(this::tick, 400);
    }

    /** Fait l'étape suivante si l'écran attendu est affiché. Renvoie true si la suite est déjà programmée. */
    private boolean act(AccessibilityNodeInfo root, long now) {
        if ("uninstall".equals(mode)) {
            AccessibilityNodeInfo b = byId(root, "android:id/button1");
            if (b == null) b = findText(root, CONFIRM);
            if (b != null && click(b)) {
                step = 1;
                stepStart = now;
                h.postDelayed(this::tick, 600);
                return true;
            }
            return false;
        }

        // Vider le cache : fiche de l'appli -> Stockage -> Vider le cache (-> confirmation éventuelle)
        if (step == 0) {
            AccessibilityNodeInfo clear = findText(root, CLEAR);
            if (clear != null) { step = 1; return false; }
            AccessibilityNodeInfo n = findText(root, STORAGE);
            if (n != null && click(n)) {
                step = 1;
                stepStart = now;
                h.postDelayed(this::tick, 900);
                return true;
            }
            if (!scrolled) { scrolled = scroll(root); }
            return false;
        }
        if (step == 1) {
            AccessibilityNodeInfo n = findText(root, CLEAR);
            if (n == null) return false;
            AccessibilityNodeInfo target = clickable(n);
            if (target != null && !target.isEnabled()) {
                // Cache déjà vide
                done++;
                h.postDelayed(this::next, 300);
                return true;
            }
            if (click(n)) {
                step = 2;
                stepStart = now;
                h.postDelayed(this::confirmIfAsked, 700);
                return true;
            }
        }
        return false;
    }

    /** Certains téléphones (Xiaomi…) demandent une confirmation après « Vider le cache ». */
    private void confirmIfAsked() {
        if (!running) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo b = byId(root, "android:id/button1");
            if (b != null) click(b);
        }
        done++;
        h.postDelayed(this::next, 500);
    }

    private void finish(boolean interrupted) {
        running = false;
        h.removeCallbacksAndMessages(null);
        try {
            JSONObject r = new JSONObject();
            r.put("mode", mode);
            r.put("done", done);
            r.put("skipped", skipped);
            r.put("total", total);
            r.put("interrupted", interrupted);
            if (!interrupted) {
                Intent back = new Intent(this, MainActivity.class)
                        .putExtra("autoResult", r.toString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(back);
            } else {
                MainActivity.pendingAutoResult = r.toString();
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ recherche dans l'écran

    private static String norm(CharSequence cs) {
        if (cs == null) return "";
        String s = Normalizer.normalize(cs.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replace(' ', ' ').replace('’', '\'').trim().toLowerCase(Locale.ROOT);
    }

    /** Premier élément dont le texte est exactement l'un des libellés (dans l'ordre de préférence). */
    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            AccessibilityNodeInfo hit = walk(root, label, 0);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo walk(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isVisibleToUser() && (label.equals(norm(n.getText())) || label.equals(norm(n.getContentDescription())))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = walk(n.getChild(i), label, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        for (AccessibilityNodeInfo n : l) if (n.isVisibleToUser() && n.isEnabled()) return n;
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 6; i++, n = n.getParent()) if (n.isClickable()) return n;
        return null;
    }

    private static boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickable(n);
        return c != null && c.isEnabled() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean scroll(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (scroll(n.getChild(i))) return true;
        return false;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
