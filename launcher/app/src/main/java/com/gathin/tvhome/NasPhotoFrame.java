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
            main.removeCallbacks(tick);
            main.post(tick);
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
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nas-photos");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        main.post(tick);
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

    private void next() {
        if (!running || worker == null) return;
        int w = view.boxWidth();
        int h = view.boxHeight();
        if (w <= 0 || h <= 0) return;   // place() posts the first turn once the box is known
        final int gen = generation;
        worker.execute(() -> {
            Bitmap photo = null;
            String label = "";
            long delay = settings.intervalSeconds * 1000L;
            try {
                ensureIndex();
                if (order.length == 0) {
                    NasPhotos.setStatus(context, "empty", 0);
                    delay = Math.max(delay, 60_000L);
                } else {
                    for (int i = 0; i < TRIES_PER_TURN && photo == null; i++) {
                        String path = index.pathAt(nextOffset());
                        try {
                            photo = fetch(path, w, h);
                            label = settings.caption ? caption(path) : "";
                        } catch (IOException | RuntimeException e) {
                            NasPhotos.setStatus(context, "photo: " + e.getMessage(), order.length);
                        }
                    }
                    if (photo != null) NasPhotos.setStatus(context, "ok", order.length);
                    else delay = Math.max(delay, 60_000L);
                }
            } catch (IOException | RuntimeException e) {
                NasPhotos.setStatus(context, "list: " + e.getMessage(), 0);
                delay = Math.max(delay, 120_000L);   // the NAS is asleep or unreachable: try again later
            }
            final Bitmap done = photo;
            final String text = label;
            final long wait = delay;
            main.post(() -> {
                if (gen != generation || !running) return;
                if (done != null) view.show(done, text);
                main.postDelayed(tick, wait);
            });
        });
    }

    /** Fetches the list on the first turn and every few hours after; a failed fetch keeps the cached one. */
    private void ensureIndex() throws IOException {
        long now = SystemClock.elapsedRealtime();
        boolean stale = indexFetchedAt == 0 || now - indexFetchedAt > INDEX_MAX_AGE_MS;
        if (stale) {
            IOException failure = null;
            try {
                NasPhotoIndex.refresh(context, client, settings);
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
            client.download(path, file, MAX_PHOTO_BYTES, null);
            Bitmap decoded = decode(file, boxH);
            if (decoded == null) throw new IOException("cannot decode");
            Bitmap feathered = PhotoFrameView.feather(decoded, boxW, boxH);
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

    /** The folders the photo is in, "2023 · Kyoto", as a quiet caption. */
    static String caption(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash).replace("/", " · ");
    }
}
