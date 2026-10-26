package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the NAS photo frame while Home is on screen: start() in onResume, stop() in onPause, nothing in the
 * background. One worker thread fetches the photo list now and then and one photo per interval; the next
 * photo is decoded at the frame's size and feathered there, then handed to PhotoFrameView.
 */
final class NasPhotoFrame {
    private static final long INDEX_MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final long MAX_PHOTO_BYTES = 80L * 1024 * 1024;
    private static final int TRIES_PER_TURN = 3;
    private static final String TAG = "NasPhotos";
    /** A turn (list, download, decode) taking longer than this is given up on. */
    private static final long STUCK_MS = 90_000L;

    private final Context context;
    private final PhotoFrameView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ExecutorService worker;
    private NasPhotos settings;
    private NasClient client;
    private NasPhotoIndex index;
    private int[] order = new int[0];
    private int cursor;
    private long indexFetchedAt;
    private boolean shown = true;
    private boolean running;
    /** Bumped by every start() and stop(), so work that finishes late cannot touch a frame that moved on. */
    private int generation;
    /** When the turn on the worker started, 0 while none is running. */
    private volatile long busySince;
    /** The photo on screen, for the frame's menu; null until the first one arrives. */
    private String currentPath;
    /** True while the frame has focus: the photo the owner is looking at stays put. */
    private boolean paused;
    /** skip() asked for the next photo: it comes even while the frame is focused and paused. */
    private boolean skipRequested;
    /** The NAS refused the account: no more requests until the settings change (Home recreates this frame). */
    private boolean refused;
    /** Photos skipped as hidden in one turn before the turn gives up (a list that is almost all hidden). */
    private static final int MAX_HIDDEN_SKIPS = 500;
    /** A favourite shows again no sooner than this; with five favourites at 20 % none comes round every minute. */
    private static final long FAVORITE_COOLDOWN_MS = 2 * 60 * 60 * 1000L;
    /** An "On this day" photo, at most once in this long. */
    private static final long MEMORY_COOLDOWN_MS = 6 * 60 * 60 * 1000L;
    /** Share of turns, on top of the favourites', that go to "On this day" when it has photos. */
    private static final float MEMORY_SHARE = 0.12f;
    /** When each favourite or memory was last on screen (worker thread only). */
    private final java.util.HashMap<String, Long> lastSpecial = new java.util.HashMap<>();
    /** The list day: "On this day" is worked out again after midnight. */
    private java.time.LocalDate loadedDay;
    /** Favourites changed on the TV: the cached list is read again (no fetch) on the next turn. */
    private volatile boolean favoritesChanged;
    private final Runnable tick = this::next;

    NasPhotoFrame(Context context, PhotoFrameView view, boolean animations) {
        this.context = context.getApplicationContext();
        this.view = view;
        view.setAnimate(animations);
        reload();
    }

    /** Settings may have changed (Home recreates after either settings page, so this is only the first read). */
    private void reload() {
        settings = NasPhotos.load(context);
        view.setVisibility(settings.active() ? View.VISIBLE : View.GONE);
        if (!settings.active()) view.clear();
        client = settings.active() ? new NasClient(settings) : null;
    }

    boolean active() {
        return settings.active();
    }

    /** The free space between the header and the dock app's name, in the frame view's coordinates. */
    void place(int left, int top, int right, int bottom) {
        if (!settings.active() || bottom - top < 80) return;
        view.setBox(left, top, right, bottom);
        if (running && !view.hasPhoto()) {
            schedule(0);
        }
    }

    /** Shown with the dock's top shelf; hidden while focus is down in the grid, where the tiles scroll up. */
    void setShown(boolean shown, boolean animate) {
        if (!settings.active() || this.shown == shown) return;
        this.shown = shown;
        view.animate().cancel();
        if (animate) view.animate().alpha(shown ? 1f : 0f).setDuration(shown ? 400 : 160).start();
        else view.setAlpha(shown ? 1f : 0f);
    }

    void start() {
        if (!settings.active() || running) return;
        running = true;
        generation++;
        if (worker == null) worker = newWorker();
        schedule(0);
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nas-photos");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    String currentPath() {
        return currentPath;
    }

    /** Held while the frame has focus, so the photo does not change under the owner's eyes. */
    void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** The next photo now, e.g. after the one on screen was hidden. */
    void skip() {
        skipRequested = true;
        schedule(0);
    }

    /** "Don't show this photo" (or its whole folder): remembered for good, and the next photo comes at once. */
    void hideCurrent(boolean wholeFolder) {
        if (currentPath == null) return;
        NasPathSet hidden = NasPathSet.hidden(context);
        if (wholeFolder) hidden.addFolderOf(currentPath);
        else hidden.addPhoto(currentPath);
        skip();
    }

    /** "Show this photo more" (or its folder), or the same again to take it off; the next turns see it. */
    void toggleFavorite(boolean wholeFolder) {
        if (currentPath == null) return;
        NasPathSet favorites = NasPathSet.favorites(context);
        if (wholeFolder) {
            if (favorites.listsFolderOf(currentPath)) favorites.removeFolderOf(currentPath);
            else favorites.addFolderOf(currentPath);
        } else if (favorites.listsPhoto(currentPath)) {
            favorites.removePhoto(currentPath);
        } else {
            favorites.addPhoto(currentPath);
        }
        favoritesChanged = true;
    }

    void stop() {
        running = false;
        generation++;
        main.removeCallbacks(tick);
    }

    /** Memory is short with Home in the background: the photos are fetched again on the way back. */
    void release() {
        view.clear();
    }

    void close() {
        stop();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    /**
     * The one way a turn is queued: any turn already waiting is dropped first, so start(), place(), skip(), a busy
     * worker and a finished turn can never leave two chains of turns running side by side.
     */
    private void schedule(long delayMs) {
        main.removeCallbacks(tick);
        main.postDelayed(tick, delayMs);
    }

    private void next() {
        if (!running || worker == null || refused) return;
        int w = view.boxWidth();
        int h = view.boxHeight();
        if (w <= 0 || h <= 0) return;   // place() posts the first turn once the box is known
        if (paused && !skipRequested) {
            schedule(2_000L);
            return;
        }
        long since = busySince;
        if (since != 0) {
            if (SystemClock.elapsedRealtime() - since < STUCK_MS) {
                schedule(5_000L);   // the last turn is still on its way; don't queue another
                return;
            }
            // A download or decode that never returns (a NAS gone mid-transfer, a file the decoder chokes on)
            // must not freeze the frame for good: leave that thread behind and carry on with a new one.
            Log.w(TAG, "a photo turn is stuck; starting over");
            worker.shutdownNow();
            worker = newWorker();
            busySince = 0;
        }
        skipRequested = false;
        final int gen = generation;
        final long started = SystemClock.elapsedRealtime();
        busySince = started;
        worker.execute(() -> {
            Bitmap photo = null;
            String label = "";
            String shownPath = null;
            long delay = settings.intervalSeconds * 1000L;
            try {
                ensureIndex();
                if (order.length == 0) {
                    NasPhotos.setStatus(context, "empty", 0);
                    delay = Math.max(delay, 60_000L);
                } else {
                    NasPathSet hidden = NasPathSet.hidden(context);
                    for (int i = 0, skips = 0; i < TRIES_PER_TURN && photo == null && skips < MAX_HIDDEN_SKIPS; ) {
                        // The first try of a turn may go to a favourite or "On this day"; retries are ordinary.
                        String[] special = i == 0 && skips == 0 ? pickSpecial() : null;
                        String path = special != null ? special[0] : index.pathAt(nextOffset());
                        if (hidden.contains(path)) {   // hidden since the list was loaded
                            skips++;
                            continue;
                        }
                        i++;
                        try {
                            photo = fetch(path, w, h);
                            label = label(path, special == null ? null : special[1]);
                            shownPath = path;
                            if (special != null) lastSpecial.put(path, SystemClock.elapsedRealtime());
                        } catch (NasClient.Refused e) {
                            throw e;
                        } catch (IOException | RuntimeException | OutOfMemoryError e) {
                            Log.w(TAG, "skipped one photo: " + e);
                            NasPhotos.setStatus(context, "photo: " + e.getMessage(), order.length);
                        }
                    }
                    if (photo != null) NasPhotos.setStatus(context, "ok", order.length);
                    else delay = Math.max(delay, 60_000L);
                }
            } catch (NasClient.Refused e) {
                Log.w(TAG, "stopping: " + e.getMessage());
                NasPhotos.setStatus(context, "refused: " + e.getMessage(), 0);
                main.post(() -> refused = true);
                if (busySince == started) busySince = 0;
                return;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "no photo list: " + e);
                NasPhotos.setStatus(context, "list: " + e.getMessage(), 0);
                delay = Math.max(delay, 120_000L);   // the NAS is asleep or unreachable: try again later
            }
            final Bitmap done = photo;
            final String text = label;
            final String donePath = shownPath;
            final long wait = delay;
            if (busySince == started) busySince = 0;   // a turn given up on must not clear its successor's
            main.post(() -> {
                if (gen != generation || !running) return;
                if (done != null) {
                    view.show(done, text);
                    currentPath = donePath;
                }
                schedule(wait);
            });
        });
    }

    /** Fetches the list on the first turn and every few hours after; a failed fetch keeps the cached one. */
    private void ensureIndex() throws IOException {
        long now = SystemClock.elapsedRealtime();
        boolean stale = indexFetchedAt == 0 || now - indexFetchedAt > INDEX_MAX_AGE_MS;
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!stale && (favoritesChanged || !today.equals(loadedDay))) {
            // Only the favourites or the date moved: read the cached list again, nothing from the NAS.
            favoritesChanged = false;
            loadedDay = today;
            index = NasPhotoIndex.load(context, settings);
            order = index.offsets().clone();
            cursor = 0;
            if (settings.shuffle) shuffle(order);
            return;
        }
        if (stale) {
            favoritesChanged = false;
            loadedDay = today;
            IOException failure = null;
            try {
                NasPhotoIndex.refresh(context, client, settings);
            } catch (NasClient.Refused e) {
                throw e;
            } catch (IOException e) {
                failure = e;
            }
            indexFetchedAt = now;
            index = NasPhotoIndex.load(context, settings);
            order = index.offsets().clone();
            cursor = 0;
            if (settings.shuffle) shuffle(order);
            if (order.length == 0 && failure != null) throw failure;
        }
    }

    /**
     * {path, "fav"|"mem"} when this turn goes to a favourite or to "On this day", or null for an ordinary one.
     * A dice roll against the owner's share, then a few random picks for one that is past its cool-down, so a
     * handful of favourites come round often without the frame showing nothing else.
     */
    private String[] pickSpecial() throws IOException {
        float roll = random.nextFloat();
        float favShare = settings.favoriteRate / 100f;
        int[] favs = index.favorites();
        if (roll < favShare && favs.length > 0) {
            String p = cooled(favs, FAVORITE_COOLDOWN_MS);
            if (p != null) return new String[]{p, "fav"};
        } else if (roll < favShare + MEMORY_SHARE && index.memories().length > 0) {
            String p = cooled(index.memories(), MEMORY_COOLDOWN_MS);
            if (p != null) return new String[]{p, "mem"};
        }
        return null;
    }

    private String cooled(int[] offsets, long cooldownMs) throws IOException {
        long now = SystemClock.elapsedRealtime();
        for (int tries = 0; tries < Math.min(8, offsets.length * 2); tries++) {
            String p = index.pathAt(offsets[random.nextInt(offsets.length)]);
            Long at = lastSpecial.get(p);
            if ((at == null || now - at >= cooldownMs) && !NasPathSet.hidden(context).contains(p)) return p;
        }
        return null;
    }

    /** The caption: its folder, with a heart for a favourite or "3 years ago today" for a memory. */
    private String label(String path, String kind) {
        String folder = settings.caption ? caption(path) : "";
        if ("mem".equals(kind)) {
            int[] d = NasPhotos.dateOf(path);
            int years = d == null ? 0 : java.time.LocalDate.now().getYear() - d[0];
            String when = context.getString(R.string.nas_memory_label, years);
            return folder.isEmpty() ? when : when + " · " + folder;
        }
        if ("fav".equals(kind)) return folder.isEmpty() ? "\u2665" : "\u2665 " + folder;
        return folder;
    }

    private int nextOffset() {
        if (cursor >= order.length) {
            cursor = 0;
            if (settings.shuffle) shuffle(order);
        }
        return order[cursor++];
    }

    private void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private Bitmap fetch(String path, int boxW, int boxH) throws IOException {
        File file = new File(context.getCacheDir(), "nas_photo");
        try {
            long t0 = SystemClock.elapsedRealtime();
            client.download(path, file, MAX_PHOTO_BYTES, null);
            long t1 = SystemClock.elapsedRealtime();
            Bitmap decoded = decode(file, boxH);
            if (decoded == null) throw new IOException("cannot decode");
            long t2 = SystemClock.elapsedRealtime();
            Bitmap feathered = PhotoFrameView.feather(decoded, boxW, boxH, view.mirrored());
            Log.d(TAG, file.length() / 1024 + " KB in " + (t1 - t0) + " ms, decoded " + decoded.getWidth() + "x"
                    + decoded.getHeight() + " in " + (t2 - t1) + " ms, feathered in "
                    + (SystemClock.elapsedRealtime() - t2) + " ms");
            if (feathered != decoded) decoded.recycle();
            return feathered;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Decoded no larger than needed for the frame's height, turned upright from its EXIF orientation. */
    private static Bitmap decode(File file, int targetH) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder reads HEIC as well as JPEG and applies the EXIF orientation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                if (h > targetH) {
                    float s = (float) targetH / h;
                    decoder.setTargetSize(Math.max(1, Math.round(w * s)), targetH);
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int rotation = rotation(file);
        int h = rotation % 180 == 0 ? bounds.outHeight : bounds.outWidth;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (h / (opts.inSampleSize * 2) >= targetH) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
        if (b == null || rotation == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rotation);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static int rotation(File file) {
        try {
            switch (new ExifInterface(file.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** The folder the photo is in, its own name only ("Kyoto" for "2023/Kyoto/a.jpg"), as a quiet caption. */
    static String caption(String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "";
        String folder = path.substring(0, slash);
        return folder.substring(folder.lastIndexOf('/') + 1);
    }
}
