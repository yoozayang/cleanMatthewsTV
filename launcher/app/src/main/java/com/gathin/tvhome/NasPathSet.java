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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A list of NAS photos and folders kept on the TV: the ones the owner hid ("Don't show this photo") and the ones
 * they want to see more often ("Show this photo more"). In the app's files (not the cache, so it lasts), one
 * share-relative path per line; a line ending in "/" is a folder and covers everything under it. Survives a new
 * photo list, a launcher update and a NAS change.
 */
final class NasPathSet {
    private static NasPathSet hidden;
    private static NasPathSet favorites;

    private final File file;
    private final TreeSet<String> entries = new TreeSet<>();

    private NasPathSet(File file) {
        this.file = file;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isEmpty()) entries.add(line);
            }
        } catch (IOException ignored) {
            // No list yet: it is empty.
        }
    }

    /** Photos and folders the frame never shows. */
    static synchronized NasPathSet hidden(Context context) {
        if (hidden == null) hidden = new NasPathSet(new File(context.getApplicationContext().getFilesDir(), "nas_hidden.txt"));
        return hidden;
    }

    /** Photos and folders the frame shows more often (NasPhotos.favoriteRate, with a cool-down per photo). */
    static synchronized NasPathSet favorites(Context context) {
        if (favorites == null) favorites = new NasPathSet(new File(context.getApplicationContext().getFilesDir(), "nas_favorites.txt"));
        return favorites;
    }

    synchronized boolean contains(String path) {
        return covers(entries, path);
    }

    /** A path is covered when it is listed itself, or a folder above it is ("2023/Kyoto/"). */
    static boolean covers(Set<String> entries, String path) {
        if (entries.isEmpty()) return false;
        if (entries.contains(path)) return true;
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            if (entries.contains(path.substring(0, slash + 1))) return true;
        }
        return false;
    }

    /** Listed by its own path (not only through a folder). */
    synchronized boolean listsPhoto(String path) {
        return entries.contains(path);
    }

    synchronized boolean listsFolderOf(String path) {
        String folder = folderOf(path);
        return !folder.isEmpty() && entries.contains(folder);
    }

    synchronized void addPhoto(String path) {
        if (entries.add(path)) save();
    }

    synchronized void addFolderOf(String photoPath) {
        String folder = folderOf(photoPath);
        if (!folder.isEmpty() && entries.add(folder)) save();
    }

    synchronized void removePhoto(String path) {
        if (entries.remove(path)) save();
    }

    synchronized void removeFolderOf(String photoPath) {
        if (entries.remove(folderOf(photoPath))) save();
    }

    synchronized void remove(Collection<String> paths) {
        if (entries.removeAll(paths)) save();
    }

    synchronized void clear() {
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
