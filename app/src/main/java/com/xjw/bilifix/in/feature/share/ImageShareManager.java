package com.xjw.bilifix.in.feature.share;

import static com.xjw.bilifix.in.core.ModuleConstants.TARGET_PACKAGE;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.xjw.bilifix.in.core.HookApi;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Materializes host images into a guarded cache and launches Android's share chooser. */
final class ImageShareManager {
    private static final String FILE_PROVIDER_AUTHORITY = TARGET_PACKAGE + ".fileprovider";
    private static final long MAX_SHARE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_FALLBACK_PIXELS = 8L * 1024L * 1024L;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger fileSequence = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BiliFix-SystemShare");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Method cachedImageLookup;
    private volatile Method fallbackImageLookup;
    private volatile Method fileProviderGetUri;

    ImageShareManager(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        installGroup("system-share file provider", this::resolveFileProvider);
    }

    void startImageShare(
            Context context,
            String source,
            View fallbackView,
            String text,
            String label) {
        Context safeContext = context == null ? currentApplication() : context;
        if (safeContext == null) {
            module.warn("system share rejected: no context label=" + label);
            showToast("系统分享失败");
            return;
        }
        Context appContext = safeContext.getApplicationContext();
        if (appContext == null) {
            appContext = safeContext;
        }
        Bitmap fallback = snapshotView(fallbackView);
        Context finalContext = appContext;
        Bitmap finalFallback = fallback;
        module.info("system share queued: label=" + label
                + " source=" + describeSource(source)
                + " fallback=" + (fallback != null));
        executor.execute(() -> {
            File shareFile = null;
            try {
                shareFile = materializeSource(finalContext, source, label);
                if (shareFile == null && finalFallback != null) {
                    shareFile = saveBitmap(finalContext, finalFallback, label);
                }
                if (shareFile == null || shareFile.length() <= 0L) {
                    throw new IllegalStateException("no shareable image available");
                }
                Uri contentUri = fileProviderUri(finalContext, shareFile);
                String mime = detectMime(shareFile);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, contentUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri("BiliFix image", contentUri));
                if (text != null && !text.isEmpty()) {
                    send.putExtra(Intent.EXTRA_TEXT, text);
                }
                Intent chooser = Intent.createChooser(send, "分享到");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                File completedFile = shareFile;
                mainHandler.post(() -> {
                    try {
                        finalContext.startActivity(chooser);
                        module.info("system share chooser launched: label=" + label
                                + " file=" + completedFile.getName()
                                + " bytes=" + completedFile.length()
                                + " mime=" + mime);
                    } catch (Throwable throwable) {
                        module.error("system share chooser launch failed: label=" + label,
                                throwable);
                        showToast("系统分享失败");
                    }
                });
            } catch (Throwable throwable) {
                module.error("system share preparation failed: label=" + label, throwable);
                showToast("图片尚未加载完成，请稍后再试");
            } finally {
                if (finalFallback != null && !finalFallback.isRecycled()) {
                    try {
                        finalFallback.recycle();
                    } catch (Throwable ignored) {
                        // Nothing else owns snapshots produced by snapshotView().
                    }
                }
            }
        });
    }

    private File materializeSource(Context context, String source, String label)
            throws Throwable {
        if (source == null || source.isEmpty()) {
            return null;
        }
        String normalized = source.startsWith("//") ? "https:" + source : source;
        File direct = null;
        if (normalized.startsWith("file://")) {
            direct = new File(Uri.parse(normalized).getPath());
        } else if (normalized.startsWith("/")) {
            direct = new File(normalized);
        }
        if (isReadableImageCandidate(direct)) {
            return copyIntoShareCache(context, direct, label);
        }

        File cached = findCachedImage(normalized);
        if (isReadableImageCandidate(cached)) {
            module.debug("system share image cache hit: label=" + label
                    + " bytes=" + cached.length());
            return copyIntoShareCache(context, cached, label);
        }
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return null;
        }
        return downloadIntoShareCache(context, normalized, label);
    }

    private File findCachedImage(String source) {
        for (boolean original : new boolean[]{false, true}) {
            try {
                Method method = cachedImageLookup;
                if (method != null) {
                    Object value = invoke(method, null, source, original);
                    if (value instanceof File && isReadableImageCandidate((File) value)) {
                        return (File) value;
                    }
                }
            } catch (Throwable throwable) {
                module.debug("primary image cache lookup failed: " + throwable);
            }
        }
        for (boolean original : new boolean[]{false, true}) {
            try {
                Method method = fallbackImageLookup;
                if (method != null) {
                    Object value = invoke(method, null, source, original);
                    if (value instanceof File && isReadableImageCandidate((File) value)) {
                        return (File) value;
                    }
                }
            } catch (Throwable throwable) {
                module.debug("fallback image cache lookup failed: " + throwable);
            }
        }
        return null;
    }

    private File downloadIntoShareCache(Context context, String source, String label)
            throws Throwable {
        Class<?> clientClass = module.load(classLoader, "okhttp3.y");
        Class<?> requestBuilderClass = module.load(classLoader, "okhttp3.a0$a");
        Object client = clientClass.getConstructor().newInstance();
        Object requestBuilder = requestBuilderClass.getConstructor().newInstance();
        Method setUrl = findMethod(requestBuilderClass, "p", String.class);
        Method buildRequest = findMethod(requestBuilderClass, "b");
        invoke(setUrl, requestBuilder, source);
        Object request = invoke(buildRequest, requestBuilder);
        Method newCall = findCompatibleMethod(clientClass, "b", request.getClass());
        Object call = invoke(newCall, client, request);
        Method execute = findMethod(call.getClass(), "execute");
        Object response = null;
        File output = newShareFile(context, label, ".tmp");
        File completed = null;
        try {
            response = invoke(execute, call);
            boolean successful = Boolean.TRUE.equals(
                    invoke(findMethod(response.getClass(), "isSuccessful"), response));
            if (!successful) {
                module.warn("system share image download failed: label=" + label
                        + " status=non-success");
                return null;
            }
            Object body = invoke(findMethod(response.getClass(), "k"), response);
            if (body == null) {
                return null;
            }
            InputStream input = (InputStream) invoke(findMethod(body.getClass(), "k"), body);
            try (InputStream in = input;
                 OutputStream out = new FileOutputStream(output)) {
                copyLimited(in, out, MAX_SHARE_BYTES);
            } finally {
                closeQuietly(body);
            }
            if (isReadableImageCandidate(output)) {
                completed = moveToTypedShareFile(context, output, label);
                module.info("system share image downloaded: label=" + label
                        + " file=" + completed.getName()
                        + " bytes=" + completed.length());
                return completed;
            }
            deleteQuietly(output);
            return null;
        } finally {
            closeQuietly(response);
            if (completed == null || !output.equals(completed)) {
                deleteQuietly(output);
            }
        }
    }

    private File copyIntoShareCache(Context context, File source, String label)
            throws Throwable {
        File target = newShareFile(context, label, extensionForMime(detectMime(source)));
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copyLimited(input, output, MAX_SHARE_BYTES);
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        return target;
    }

    private File moveToTypedShareFile(Context context, File source, String label)
            throws Throwable {
        String mime = detectMime(source);
        File target = newShareFile(context, label, extensionForMime(mime));
        if (source.renameTo(target)) {
            return target;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copyLimited(input, output, MAX_SHARE_BYTES);
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        deleteQuietly(source);
        return target;
    }

    private File saveBitmap(Context context, Bitmap bitmap, String label) throws Throwable {
        File target = newShareFile(context, label, ".png");
        try (OutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("Bitmap.compress returned false");
            }
            output.flush();
        } catch (Throwable throwable) {
            deleteQuietly(target);
            throw throwable;
        }
        if (target.length() > MAX_SHARE_BYTES) {
            deleteQuietly(target);
            throw new IllegalStateException("snapshot exceeds share size limit");
        }
        module.info("system share fallback snapshot saved: label=" + label
                + " bytes=" + target.length());
        return target;
    }

    private File newShareFile(Context context, String label, String suffix) {
        File directory = new File(context.getCacheDir(), "bilifix_system_share");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("cannot create share cache directory");
        }
        String safeLabel = label.replaceAll("[^a-zA-Z0-9_-]", "_");
        return new File(directory, safeLabel + "_" + System.currentTimeMillis()
                + "_" + fileSequence.incrementAndGet() + suffix);
    }

    private Bitmap snapshotView(View view) {
        if (view == null) {
            return null;
        }
        try {
            Drawable drawable = view instanceof ImageView
                    ? ((ImageView) view).getDrawable() : null;
            int viewWidth = view.getWidth();
            int viewHeight = view.getHeight();
            int drawableWidth = drawable == null ? 0 : drawable.getIntrinsicWidth();
            int drawableHeight = drawable == null ? 0 : drawable.getIntrinsicHeight();
            int width = viewWidth > 0 ? viewWidth : drawableWidth;
            int height = viewHeight > 0 ? viewHeight : drawableHeight;
            if (width <= 0 || height <= 0) {
                module.warn("system share fallback unavailable: source="
                        + view.getClass().getName()
                        + " view=" + viewWidth + "x" + viewHeight
                        + " drawable=" + (drawable == null
                        ? "none" : drawable.getClass().getName())
                        + " intrinsic=" + drawableWidth + "x" + drawableHeight);
                return null;
            }
            double scale = 1.0d;
            long pixels = (long) width * (long) height;
            if (pixels > MAX_FALLBACK_PIXELS) {
                scale = Math.sqrt((double) MAX_FALLBACK_PIXELS / (double) pixels);
            }
            int outWidth = Math.max(1, (int) Math.round(width * scale));
            int outHeight = Math.max(1, (int) Math.round(height * scale));
            Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale((float) outWidth / (float) width,
                    (float) outHeight / (float) height);
            if (viewWidth > 0 && viewHeight > 0) {
                view.draw(canvas);
            } else if (drawable != null) {
                android.graphics.Rect oldBounds = new android.graphics.Rect(drawable.getBounds());
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
                drawable.setBounds(oldBounds);
            }
            module.debug("system share fallback captured: source="
                    + view.getClass().getName() + " view=" + viewWidth + "x" + viewHeight
                    + " intrinsic=" + drawableWidth + "x" + drawableHeight
                    + " output=" + outWidth + "x" + outHeight);
            return bitmap;
        } catch (Throwable throwable) {
            module.error("system share fallback capture failed", throwable);
            return null;
        }
    }

    private Uri fileProviderUri(Context context, File file) throws Throwable {
        Method method = fileProviderGetUri;
        if (method == null) {
            resolveFileProvider();
            method = fileProviderGetUri;
        }
        return (Uri) invoke(method, null, context, FILE_PROVIDER_AUTHORITY, file);
    }

    private void resolveFileProvider() throws Throwable {
        Class<?> providerClass = module.load(classLoader, "androidx.core.content.FileProvider");
        Method method = providerClass.getMethod(
                "getUriForFile", Context.class, String.class, File.class);
        method.setAccessible(true);
        fileProviderGetUri = method;
        module.info("system-share FileProvider resolved: authority="
                + FILE_PROVIDER_AUTHORITY);
    }

    void resolveImageCacheHelpers() {
        try {
            Class<?> extensionClass = module.load(classLoader,
                    "com.bilibili.lib.imageviewer.utils.ImageExtentionKt");
            cachedImageLookup = module.declaredMethod(
                    extensionClass, "X", String.class, boolean.class);
            module.info("primary image cache helper resolved");
        } catch (Throwable throwable) {
            module.error("primary image cache helper unavailable", throwable);
        }
        try {
            Class<?> helperClass = module.load(classLoader,
                    "com.bilibili.lib.image2.BiliImageLoaderHelper");
            fallbackImageLookup = module.declaredMethod(
                    helperClass, "p", String.class, boolean.class);
            module.info("fallback image cache helper resolved");
        } catch (Throwable throwable) {
            module.error("fallback image cache helper unavailable", throwable);
        }
    }

    private String detectMime(File file) {
        try (InputStream input = new FileInputStream(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            if (options.outMimeType != null && !options.outMimeType.isEmpty()) {
                return options.outMimeType;
            }
        } catch (Throwable ignored) {
            // Unknown image encodings can still be offered to Android as image/*.
        }
        return "image/*";
    }

    private static String extensionForMime(String mime) {
        if ("image/jpeg".equalsIgnoreCase(mime)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(mime)) {
            return ".png";
        }
        if ("image/webp".equalsIgnoreCase(mime)) {
            return ".webp";
        }
        if ("image/gif".equalsIgnoreCase(mime)) {
            return ".gif";
        }
        if ("image/heic".equalsIgnoreCase(mime)
                || "image/heif".equalsIgnoreCase(mime)) {
            return ".heic";
        }
        if ("image/bmp".equalsIgnoreCase(mime)
                || "image/x-ms-bmp".equalsIgnoreCase(mime)) {
            return ".bmp";
        }
        return ".img";
    }

    private static void copyLimited(InputStream input, OutputStream output, long maxBytes)
            throws Exception {
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("image exceeds share size limit");
            }
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static boolean isReadableImageCandidate(File file) {
        return file != null && file.isFile() && file.length() > 0L
                && file.length() <= MAX_SHARE_BYTES;
    }

    private static void closeQuietly(Object value) {
        if (value == null) {
            return;
        }
        try {
            if (value instanceof Closeable) {
                ((Closeable) value).close();
                return;
            }
            Method close = findMethod(value.getClass(), "close");
            invoke(close, value);
        } catch (Throwable ignored) {
            // Best-effort close for host OkHttp response/body objects.
        }
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.isFile()) {
                file.delete();
            }
        } catch (Throwable ignored) {
            // Stale cache entries remain recoverable by the app's normal cache cleanup.
        }
    }

    String describeSource(String source) {
        if (source == null || source.isEmpty()) {
            return "none";
        }
        try {
            Uri uri = Uri.parse(source);
            if (uri.getHost() != null) {
                return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
            }
        } catch (Throwable ignored) {
            // Non-URI sources are described only by kind below.
        }
        return source.startsWith("/") || source.startsWith("file://")
                ? "local-file" : "non-http";
    }

    void showToast(String message) {
        Context context = currentApplication();
        if (context == null) {
            return;
        }
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Method findCompatibleMethod(
            Class<?> owner, String name, Class<?> argumentType) throws NoSuchMethodException {
        Class<?> current = owner;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (name.equals(method.getName()) && parameters.length == 1
                        && parameters[0].isAssignableFrom(argumentType)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(owner.getName() + "." + name
                + "(" + argumentType.getName() + ")");
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }


    private Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object value = current.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable throwable) {
            module.debug("system share current application unavailable: " + throwable);
            return null;
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("system-share hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("system-share hook group unavailable: " + label, throwable);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}

