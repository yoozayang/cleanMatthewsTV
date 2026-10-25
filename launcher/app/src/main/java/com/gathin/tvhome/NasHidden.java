package com.gathin.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Photos and folders the owner chose not to see again ("Don't show this photo" on the frame). Kept on the TV
 * in the app's files (not the cache, so it lasts), one share-relative path per line; a line ending in "/" is a
 * folder and hides everything under it. Survives a new photo list, a launcher update and a NAS change.
 */
final class NasHidden {
    private static final String FILE = "nas_hidden.txt";
    private static NasHidden instance;

    private final File file;
    private final TreeSet<String> entries = new TreeSet<>();

    private NasHidden(File file) {
        this.file = file;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isEmpty()) entries.add(line);
            }
        } catch (IOException ignored) {
            // No list yet: nothing is hidden.
        }
    }

    static synchronized NasHidden get(Context context) {
        if (instance == null) instance = new NasHidden(new File(context.getApplicationContext().getFilesDir(), FILE));
        return instance;
    }

    synchronized boolean hides(String path) {
        return hides(entries, path);
    }

    /** A path is hidden when it is listed itself, or a folder above it is ("2023/Kyoto/"). */
    static boolean hides(Set<String> entries, String path) {
        if (entries.isEmpty()) return false;
        if (entries.contains(path)) return true;
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            if (entries.contains(path.substring(0, slash + 1))) return true;
        }
        return false;
    }

    synchronized void hidePhoto(String path) {
        if (entries.add(path)) save();
    }

    synchronized void hideFolder(String photoPath) {
        String folder = folderOf(photoPath);
        if (!folder.isEmpty() && entries.add(folder)) save();
    }

    synchronized void restore(List<String> paths) {
        if (entries.removeAll(paths)) save();
    }

    synchronized void restoreAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        save();
    }

    synchronized List<String> list() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    synchronized int size() {
        return entries.size();
    }

    /** "2023/Kyoto/" for "2023/Kyoto/IMG_1.HEIC"; "" for a photo at the top of the share. */
    static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash + 1);
    }

    /** Written next to the old list and renamed over it, so a power cut never leaves half of it. */
    private void save() {
        File tmp = new File(file.getPath() + ".part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String e : entries) w.write(e + "\n");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }
}
