package com.phonemedic.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_MEDIA = 10, REQ_GEO = 11, REQ_FILE = 12;

    private WebView web;
    private PermissionRequest pendingWebPerm;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private ValueCallback<Uri[]> fileCallback;
    private SensorManager sensorManager;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBarColors();

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        setContentView(web);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");

        // Sert les fichiers de assets/www sous une adresse https (contexte sécurisé pour caméra, micro, etc.)
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    return new WebResourceResponse(mime(path), "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                List<String> need = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA))
                        need.add(Manifest.permission.CAMERA);
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO))
                        need.add(Manifest.permission.RECORD_AUDIO);
                }
                if (need.isEmpty()) { request.grant(request.getResources()); return; }
                pendingWebPerm = request;
                requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                    return;
                }
                geoOrigin = origin;
                geoCallback = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir des fichiers"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    // ---------------------------------------------------------------- cycle de vie

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) js("window.onNativeResume && window.onNativeResume()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        sensorManager.unregisterListener(this);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applySystemBarColors();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.nativeBack ? window.nativeBack() : false)", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            String key = null;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP: key = "AudioVolumeUp"; break;
                case KeyEvent.KEYCODE_VOLUME_DOWN: key = "AudioVolumeDown"; break;
                case KeyEvent.KEYCODE_CAMERA: key = "Camera"; break;
                case KeyEvent.KEYCODE_HEADSETHOOK: key = "HeadsetHook"; break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: key = "MediaPlayPause"; break;
            }
            if (key != null) js("window.dispatchEvent(new KeyboardEvent('keydown',{key:'" + key + "'}))");
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- permissions et fichiers

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MEDIA && pendingWebPerm != null) {
            List<String> ok = new ArrayList<>();
            for (String r : pendingWebPerm.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
            }
            if (ok.isEmpty()) pendingWebPerm.deny();
            else pendingWebPerm.grant(ok.toArray(new String[0]));
            pendingWebPerm = null;
        } else if (code == REQ_GEO && geoCallback != null) {
            boolean ok = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILE || fileCallback == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- capteurs natifs

    @Override
    public void onSensorChanged(SensorEvent ev) {
        String kind = ev.sensor.getType() == Sensor.TYPE_PROXIMITY ? "proximity" : "light";
        js("window.onNativeSensor && window.onNativeSensor('" + kind + "'," + ev.values[0] + "," + ev.sensor.getMaximumRange() + ")");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ---------------------------------------------------------------- utilitaires

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarColors() {
        Window w = getWindow();
        int bg = isDark() ? Color.parseColor("#0E1524") : Color.parseColor("#E9EDF2");
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setBackgroundColor(bg);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        if (isDark()) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (isDark()) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
    }

    private double designCapacity() {
        try {
            Class<?> c = Class.forName("com.android.internal.os.PowerProfile");
            Object pp = c.getConstructor(Context.class).newInstance(this);
            Object v = c.getMethod("getBatteryCapacity").invoke(pp);
            return v instanceof Double ? (Double) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean usageAccessGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ PONT JAVASCRIPT
    // Accessible depuis la page sous window.Android

    private class Bridge {

        @JavascriptInterface
        public String device() {
            JSONObject o = new JSONObject();
            try {
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("brand", Build.BRAND);
                o.put("model", Build.MODEL);
                o.put("device", Build.DEVICE);
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("hardware", Build.HARDWARE);
                o.put("soc", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE);
                o.put("uptimeH", SystemClock.elapsedRealtime() / 3600000.0);
                o.put("securityPatch", Build.VERSION.SECURITY_PATCH);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String battery() {
            JSONObject o = new JSONObject();
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null) return o.toString();
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                o.put("level", level / (double) scale);
                o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                o.put("plugged", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
                o.put("health", b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
                o.put("temperature", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                o.put("voltage", b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0);
                o.put("technology", b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
                o.put("cycles", b.getIntExtra("android.os.extra.CYCLE_COUNT", -1));
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                o.put("chargeCounter", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
                o.put("currentNow", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
                o.put("designCapacity", designCapacity());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String memory() {
            JSONObject o = new JSONObject();
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                o.put("total", mi.totalMem);
                o.put("available", mi.availMem);
                o.put("low", mi.lowMemory);
                o.put("threshold", mi.threshold);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String storage() {
            JSONObject o = new JSONObject();
            try {
                StatFs st = new StatFs(Environment.getDataDirectory().getPath());
                o.put("total", st.getTotalBytes());
                o.put("free", st.getAvailableBytes());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String sensors() {
            JSONObject o = new JSONObject();
            try {
                JSONArray list = new JSONArray();
                for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) list.put(s.getName());
                o.put("list", list);
                o.put("proximity", sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null);
                o.put("light", sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public boolean startSensor(String kind) {
            int type = "proximity".equals(kind) ? Sensor.TYPE_PROXIMITY : Sensor.TYPE_LIGHT;
            Sensor s = sensorManager.getDefaultSensor(type);
            if (s == null) return false;
            sensorManager.registerListener(MainActivity.this, s, SensorManager.SENSOR_DELAY_UI);
            return true;
        }

        @JavascriptInterface
        public void stopSensors() {
            sensorManager.unregisterListener(MainActivity.this);
        }

        @JavascriptInterface
        public boolean torch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null && flash) {
                        cm.setTorchMode(id, on);
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public boolean vibrate(String pattern) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return false;
                long[] p;
                String t = pattern.trim();
                if (t.startsWith("[")) {
                    JSONArray a = new JSONArray(t);
                    p = new long[a.length() + 1];
                    p[0] = 0;
                    for (int i = 0; i < a.length(); i++) p[i + 1] = a.getLong(i);
                } else {
                    p = new long[]{0, Long.parseLong(t)};
                }
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1));
                else v.vibrate(p, -1);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // Joue une tonalité dans l'écouteur d'appel (haut-parleur du haut)
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void earpiece() {
            new Thread(() -> {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int prevMode = am.getMode();
                try {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    boolean routed = false;
                    if (Build.VERSION.SDK_INT >= 31) {
                        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                                routed = am.setCommunicationDevice(d);
                                break;
                            }
                        }
                    }
                    if (!routed) am.setSpeakerphoneOn(false);
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90);
                    tg.startTone(ToneGenerator.TONE_SUP_DIAL, 2500);
                    Thread.sleep(2700);
                    tg.release();
                } catch (Exception ignored) {
                } finally {
                    if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                    am.setMode(prevMode);
                }
            }).start();
        }

        @JavascriptInterface
        public String bluetooth() {
            try {
                BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
                if (a == null) return "absent";
                try {
                    return a.isEnabled() ? "enabled" : "disabled";
                } catch (SecurityException se) {
                    return "present";
                }
            } catch (Exception e) {
                return "unknown";
            }
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openSettings(String action) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(action);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void openAppInfo(String pkg) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void uninstall(String pkg) {
            ui.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public boolean usageAccess() {
            return usageAccessGranted();
        }

        // Liste les applis installées par l'utilisateur. Réponse asynchrone : window.onNativeApps(json)
        @JavascriptInterface
        public void apps() {
            new Thread(() -> {
                JSONArray out = new JSONArray();
                try {
                    PackageManager pm = getPackageManager();
                    boolean access = usageAccessGranted();
                    Map<String, Long> lastUsed = new HashMap<>();
                    if (access) {
                        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                        long now = System.currentTimeMillis();
                        Map<String, UsageStats> agg = usm.queryAndAggregateUsageStats(now - 180L * 24 * 3600 * 1000, now);
                        for (Map.Entry<String, UsageStats> e : agg.entrySet())
                            lastUsed.put(e.getKey(), e.getValue().getLastTimeUsed());
                    }
                    StorageStatsManager ssm = Build.VERSION.SDK_INT >= 26 && access
                            ? (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE) : null;
                    for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        if (ai.packageName.equals(getPackageName())) continue;
                        JSONObject o = new JSONObject();
                        o.put("pkg", ai.packageName);
                        o.put("name", pm.getApplicationLabel(ai).toString());
                        long apk = new File(ai.sourceDir).length();
                        if (ai.splitSourceDirs != null) for (String sp : ai.splitSourceDirs) apk += new File(sp).length();
                        o.put("apk", apk);
                        try {
                            PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                            o.put("installed", pi.firstInstallTime);
                            o.put("updated", pi.lastUpdateTime);
                        } catch (Exception ignored) {}
                        Long lu = lastUsed.get(ai.packageName);
                        o.put("lastUsed", lu != null ? lu : -1);
                        if (ssm != null) {
                            try {
                                StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
                                o.put("app", st.getAppBytes());
                                o.put("data", st.getDataBytes());
                                o.put("cache", st.getCacheBytes());
                            } catch (Exception ignored) {}
                        }
                        out.put(o);
                    }
                } catch (Exception ignored) {}
                String payload = JSONObject.quote(out.toString());
                js("window.onNativeApps && window.onNativeApps(JSON.parse(" + payload + "))");
            }).start();
        }

        // Enregistre l'image dans Images/Phone Medic puis ouvre le menu de partage
        @JavascriptInterface
        public boolean shareImage(String base64) {
            if (Build.VERSION.SDK_INT < 29) return false;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "phone-medic-" + System.currentTimeMillis() + ".png");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Phone Medic");
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(bytes);
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ui.post(() -> startActivity(Intent.createChooser(send, "Partager le rapport")));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void copy(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Rapport Phone Medic", text));
            });
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "1.0";
            }
        }
    }
}
