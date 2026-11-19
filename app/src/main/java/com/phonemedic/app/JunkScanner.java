package com.phonemedic.app;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parcourt le stockage partagé (/sdcard) et range les fichiers inutiles par catégorie.
 * Le résultat complet reste en mémoire pour que la suppression porte exactement sur ce qui a été montré.
 */
public class JunkScanner {

    public interface Progress { void update(String phase, int count); }

    public static final String[] CATEGORIES = {"trash", "apk", "thumbs", "temp", "duplicates", "screenshots", "emptyDirs", "messaging", "big"};

    private static final long DAY = 24L * 3600 * 1000;
    private static final long BIG_FILE = 100L * 1024 * 1024;
    private static final Set<String> PROTECTED_DIRS = new HashSet<>(Arrays.asList(
            "dcim", "download", "pictures", "music", "movies", "documents", "android", "alarms",
            "notifications", "podcasts", "ringtones", "recordings", "audiobooks", "camera", "screenshots"));

    private final Map<String, List<File>> found = new LinkedHashMap<>();
    private final File root = Environment.getExternalStorageDirectory();

    public synchronized JSONObject scan(Progress progress) throws Exception {
        found.clear();
        for (String c : CATEGORIES) found.put(c, new ArrayList<>());
        long now = System.currentTimeMillis();
        List<File> candidates = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        Set<File> claimed = new HashSet<>();

        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        int count = 0;
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            String rel = dir.getAbsolutePath().substring(root.getAbsolutePath().length()).toLowerCase(Locale.ROOT);
            if (rel.startsWith("/android/data") || rel.startsWith("/android/obb")) continue;
            if (!dir.equals(root)) dirs.add(dir);
            for (File f : kids) {
                if (f.isDirectory()) { stack.push(f); continue; }
                count++;
                if (count % 400 == 0) progress.update("files", count);
                String name = f.getName().toLowerCase(Locale.ROOT);
                String path = f.getAbsolutePath().toLowerCase(Locale.ROOT);
                long size = f.length(), age = now - f.lastModified();

                String cat = null;
                if (name.startsWith(".trashed-")) cat = "trash";
                else if (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")) cat = "apk";
                else if (path.contains("/.thumbnails/")) cat = "thumbs";
                else if (name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".log")
                        || (name.endsWith(".part") && age > DAY) || name.endsWith(".crdownload")) cat = "temp";
                else if ((path.contains("/screenshots/")) && age > 30 * DAY) cat = "screenshots";
                else if ((path.contains("/whatsapp/media/") || path.contains("/telegram/")) && age > 90 * DAY
                        && !name.equals(".nomedia") && !path.contains("/databases/") && !path.contains("/backups/")) cat = "messaging";

                if (cat != null) { found.get(cat).add(f); claimed.add(f); continue; }
                if (size >= 50 * 1024 && !name.startsWith(".")) candidates.add(f);
            }
        }

        // Doublons exacts : même taille, puis même empreinte
        progress.update("duplicates", 0);
        Map<Long, List<File>> bySize = new HashMap<>();
        for (File f : candidates) bySize.computeIfAbsent(f.length(), k -> new ArrayList<>()).add(f);
        int hashed = 0;
        Set<File> dupSet = new HashSet<>();
        for (List<File> group : bySize.values()) {
            if (group.size() < 2) continue;
            Map<String, List<File>> byHash = new HashMap<>();
            for (File f : group) {
                if (++hashed % 50 == 0) progress.update("duplicates", hashed);
                String h = fingerprint(f);
                if (h != null) byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(f);
            }
            for (List<File> same : byHash.values()) {
                if (same.size() < 2) continue;
                File keep = same.get(0);
                for (File f : same) if (better(f, keep)) keep = f;
                for (File f : same) if (f != keep) { found.get("duplicates").add(f); dupSet.add(f); }
            }
        }

        // Gros fichiers (à vérifier, jamais cochés par défaut)
        for (File f : candidates) if (f.length() >= BIG_FILE && !dupSet.contains(f)) found.get("big").add(f);
        found.get("big").sort((a, b) -> Long.compare(b.length(), a.length()));

        // Dossiers vides (hors dossiers système standards)
        for (int i = dirs.size() - 1; i >= 0; i--) {
            File d = dirs.get(i);
            String n = d.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith(".") || PROTECTED_DIRS.contains(n)) continue;
            String[] l = d.list();
            if (l != null && l.length == 0) found.get("emptyDirs").add(d);
        }

        JSONObject out = new JSONObject();
        JSONObject cats = new JSONObject();
        for (Map.Entry<String, List<File>> e : found.entrySet()) {
            long total = 0;
            JSONArray items = new JSONArray();
            for (File f : e.getValue()) {
                long s = f.isFile() ? f.length() : 0;
                total += s;
                if (items.length() < 150) {
                    JSONObject it = new JSONObject();
                    it.put("p", f.getAbsolutePath().substring(root.getAbsolutePath().length()));
                    it.put("s", s);
                    it.put("m", f.lastModified());
                    items.put(it);
                }
            }
            JSONObject c = new JSONObject();
            c.put("count", e.getValue().size());
            c.put("size", total);
            c.put("items", items);
            cats.put(e.getKey(), c);
        }
        out.put("cats", cats);
        out.put("files", count);
        return out;
    }

    /** Supprime les catégories demandées, sauf les chemins exclus. Renvoie le nombre de fichiers et d'octets libérés. */
    public synchronized JSONObject delete(JSONArray cats, Set<String> excluded, List<String> deletedPaths) throws Exception {
        int deleted = 0, failed = 0;
        long freed = 0;
        for (int i = 0; i < cats.length(); i++) {
            List<File> list = found.get(cats.getString(i));
            if (list == null) continue;
            for (File f : new ArrayList<>(list)) {
                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
                if (excluded.contains(rel)) continue;
                long s = f.isFile() ? f.length() : 0;
                if (f.delete()) {
                    deleted++; freed += s; list.remove(f);
                    if (s > 0) deletedPaths.add(f.getAbsolutePath());
                } else failed++;
            }
        }
        JSONObject o = new JSONObject();
        o.put("deleted", deleted);
        o.put("failed", failed);
        o.put("freed", freed);
        return o;
    }

    /** Version à garder : d'abord celle de l'appareil photo, sinon la plus ancienne. */
    private static boolean better(File a, File b) {
        boolean ca = a.getAbsolutePath().toLowerCase(Locale.ROOT).contains("/dcim/camera");
        boolean cb = b.getAbsolutePath().toLowerCase(Locale.ROOT).contains("/dcim/camera");
        if (ca != cb) return ca;
        return a.lastModified() < b.lastModified();
    }

    /** Empreinte rapide : fichier entier s'il est petit, sinon début + milieu + fin. */
    private static String fingerprint(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            long len = r.length();
            int chunk = 64 * 1024;
            byte[] buf = new byte[chunk];
            if (len <= 3L * chunk) {
                int n;
                while ((n = r.read(buf)) > 0) md.update(buf, 0, n);
            } else {
                long[] offsets = {0, len / 2, len - chunk};
                for (long off : offsets) {
                    r.seek(off);
                    r.readFully(buf);
                    md.update(buf);
                }
            }
            md.update(Long.toString(len).getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
