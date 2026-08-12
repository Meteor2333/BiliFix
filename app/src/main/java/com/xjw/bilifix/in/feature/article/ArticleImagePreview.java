package com.xjw.bilifix.in.feature.article;

import static com.xjw.bilifix.in.core.ModuleConstants.PROJECT_NEW_ISSUE_URL;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.xjw.bilifix.in.core.HookApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Owns the native article-image bridge and viewer integration. */
final class ArticleImagePreview {
    private static final String IMAGE_BRIDGE_NAME = "BiliFixBridge";
    private static final int MAX_IMAGE_BRIDGE_JSON_LENGTH = 256 * 1024;
    private static final int MAX_IMAGE_PREVIEW_COUNT = 128;
    private static final int MAX_ARTICLE_URL_LENGTH = 512;

    private final HookApi module;
    private final PageAccess pageAccess;
    private final Set<WebView> preparedBridges = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private volatile Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Method columnWebViewGetBiliWebView;
    private volatile Method biliWebViewGetInnerView;
    private volatile Constructor<?> imageDataConstructor;
    private volatile Field imageDataUrl;
    private volatile Field imageDataWidth;
    private volatile Field imageDataHeight;
    private volatile Field imageDataSize;
    private volatile Field imageDataOriginWidth;
    private volatile Field imageDataOriginHeight;
    private volatile Object nativeImageViewer;
    private volatile Method nativeImageViewerOpen;

    ArticleImagePreview(HookApi module, PageAccess pageAccess) {
        this.module = module;
        this.pageAccess = pageAccess;
    }

    void configureWebViewAccess(Method getBiliWebView, Method getInnerView) {
        columnWebViewGetBiliWebView = getBiliWebView;
        biliWebViewGetInnerView = getInnerView;
    }

    void install(ClassLoader classLoader) {
        install("native column image viewer", () -> {
            Class<?> imageDataClass = load(classLoader,
                    "com.bilibili.moduleservice.base.ImageData");
            Class<?> imageViewerClass = load(classLoader,
                    "com.bilibili.column.ui.detail.image.f");
            Constructor<?> dataConstructor = imageDataClass.getConstructor();
            Constructor<?> viewerConstructor = imageViewerClass.getConstructor();
            Method viewerOpen = declaredMethod(imageViewerClass,
                    "a", Context.class, List.class, int.class);

            imageDataConstructor = dataConstructor;
            imageDataUrl = declaredField(imageDataClass, "url");
            imageDataWidth = declaredField(imageDataClass, "width");
            imageDataHeight = declaredField(imageDataClass, "height");
            imageDataSize = declaredField(imageDataClass, "size");
            imageDataOriginWidth = declaredField(imageDataClass, "originWidth");
            imageDataOriginHeight = declaredField(imageDataClass, "originheight");
            nativeImageViewer = viewerConstructor.newInstance();
            nativeImageViewerOpen = viewerOpen;
            info("native image viewer resolved: delegate="
                    + imageViewerClass.getName() + " method=" + viewerOpen);
        });
    }

    boolean prepareBeforeLoad(
            Activity activity,
            Object columnWebView,
            String loadUrl,
            String source) throws Throwable {
        registerSettingsReceiver(activity);
        ensureSettingsLoaded(activity);

        Object biliWebView = columnWebView == null ? null
                : invoke(columnWebViewGetBiliWebView, columnWebView);
        Object innerView = biliWebView == null ? null
                : invoke(biliWebViewGetInnerView, biliWebView);
        if (!(innerView instanceof WebView)) {
            warn("pre-load image bridge found unsupported inner WebView: source="
                    + source + " class=" + (innerView == null
                    ? "null" : innerView.getClass().getName()));
            return false;
        }

        WebView webView = (WebView) innerView;
        long urlCvid = parseArticleCvidFromLoadUrl(loadUrl);
        long activeCvid = pageAccess.getCvid(activity);
        long expectedCvid = urlCvid > 0L ? urlCvid : activeCvid;
        boolean articleEnabled = isArticleFixEnabled();
        boolean nativeReady = isNativeImageViewerReady();
        boolean imagePreviewEnabled = articleEnabled
                && isImagePreviewEnabled() && nativeReady;
        if (!articleEnabled) {
            try {
                webView.removeJavascriptInterface(IMAGE_BRIDGE_NAME);
            } catch (Throwable throwable) {
                debug("image bridge removal failed: " + throwable);
            }
            preparedBridges.remove(webView);
            info("pre-load image bridge disabled: source=" + source
                    + " cvid=" + expectedCvid
                    + " articleFix=false"
                    + " imagePreview=false"
                    + " nativeReady=" + nativeReady);
            return false;
        }
        try {
            webView.addJavascriptInterface(
                    new ArticleImageBridge(this, activity, webView, expectedCvid),
                    IMAGE_BRIDGE_NAME);
            preparedBridges.add(webView);
            info("pre-load image bridge attached: source=" + source
                    + " cvid=" + expectedCvid
                    + " urlCvid=" + urlCvid
                    + " activeCvid=" + activeCvid
                    + " mainThread=" + (Looper.myLooper() == Looper.getMainLooper())
                    + " name=" + IMAGE_BRIDGE_NAME
                    + " imagePreview=" + imagePreviewEnabled
                    + " target=" + safeSchema(loadUrl));
            return true;
        } catch (Throwable throwable) {
            preparedBridges.remove(webView);
            error("pre-load image bridge attachment failed: source=" + source
                    + " cvid=" + expectedCvid, throwable);
            return false;
        }
    }

    boolean isPrepared(WebView webView, long cvid) {
        boolean prepared = preparedBridges.contains(webView);
        boolean enabled = isArticleFixEnabled() && isImagePreviewEnabled();
        boolean nativeReady = isNativeImageViewerReady();
        debug("image bridge state at renderer injection: cvid=" + cvid
                + " prepared=" + prepared
                + " enabled=" + enabled
                + " nativeReady=" + nativeReady);
        return prepared && enabled && nativeReady;
    }

    private static long parseArticleCvidFromLoadUrl(String loadUrl) {
        if (loadUrl == null || loadUrl.isEmpty()) {
            return 0L;
        }
        try {
            Uri uri = Uri.parse(loadUrl);
            String path = uri.getPath();
            if (path != null && (path.startsWith("/read/native")
                    || path.startsWith("/read/mobile"))) {
                return parsePositiveLong(uri.getQueryParameter("id"));
            }
            if (path != null && path.startsWith("/read/cv")) {
                String segment = uri.getLastPathSegment();
                if (segment != null && segment.startsWith("cv")) {
                    return parsePositiveLong(segment.substring(2));
                }
            }
        } catch (Throwable ignored) {
            // The caller will retain the live activity cvid as the expected value.
        }
        return 0L;
    }

    private boolean isNativeImageViewerReady() {
        return imageDataConstructor != null
                && imageDataUrl != null
                && imageDataWidth != null
                && imageDataHeight != null
                && imageDataSize != null
                && imageDataOriginWidth != null
                && imageDataOriginHeight != null
                && nativeImageViewer != null
                && nativeImageViewerOpen != null;
    }

    private void handleImagePreviewRequest(
            Activity activity,
            WebView webView,
            long expectedCvid,
            String imagesJson,
            int requestedIndex) {
        int jsonLength = imagesJson == null ? 0 : imagesJson.length();
        debug("image preview requested: cvid=" + expectedCvid
                + " requestedIndex=" + requestedIndex
                + " jsonLength=" + jsonLength);
        if (!isArticleFixEnabled() || !isImagePreviewEnabled()) {
            info("image preview ignored by setting: cvid=" + expectedCvid
                    + " articleFix=" + isArticleFixEnabled()
                    + " imagePreview=" + isImagePreviewEnabled());
            return;
        }
        if (activity == null || webView == null || imagesJson == null
                || imagesJson.isEmpty()
                || imagesJson.length() > MAX_IMAGE_BRIDGE_JSON_LENGTH) {
            warn("image preview rejected: cvid=" + expectedCvid
                    + " activity=" + activity + " webView=" + webView
                    + " jsonLength=" + jsonLength);
            return;
        }
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        handler.post(() -> openNativeImageViewer(
                activity, webView, expectedCvid, imagesJson, requestedIndex));
    }

    private void openNativeImageViewer(
            Activity activity,
            WebView webView,
            long expectedCvid,
            String imagesJson,
            int requestedIndex) {
        try {
            if (!isNativeImageViewerReady()) {
                warn("image preview unavailable: native viewer was not resolved");
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                debug("image preview skipped for dead activity: cvid=" + expectedCvid);
                return;
            }
            long activeCvid = pageAccess.getCvid(activity);
            String pageUrl = webView.getUrl();
            boolean staleCvid = activeCvid <= 0L
                    || (expectedCvid > 0L && activeCvid != expectedCvid);
            if (staleCvid || !pageAccess.isTrustedArticleUrl(pageUrl)) {
                warn("image preview rejected for stale or untrusted page: expectedCvid="
                        + expectedCvid + " activeCvid=" + activeCvid
                        + " page=" + safeSchema(pageUrl));
                return;
            }

            JSONArray sourceImages = new JSONArray(imagesJson);
            int sourceCount = Math.min(sourceImages.length(), MAX_IMAGE_PREVIEW_COUNT);
            ArrayList<Object> imageData = new ArrayList<>(sourceCount);
            int selectedIndex = -1;
            for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
                JSONObject source = sourceImages.optJSONObject(sourceIndex);
                if (source == null) {
                    continue;
                }
                String url = normalizeImageUrl(source.optString("url", null));
                if (url == null) {
                    debug("image preview skipped invalid URL at sourceIndex=" + sourceIndex);
                    continue;
                }
                Object data = imageDataConstructor.newInstance();
                int width = clampImageDimension(source.optInt("width", 0));
                int height = clampImageDimension(source.optInt("height", 0));
                imageDataUrl.set(data, url);
                imageDataWidth.setInt(data, width);
                imageDataHeight.setInt(data, height);
                imageDataSize.setLong(data, 0L);
                imageDataOriginWidth.setInt(data, 0);
                imageDataOriginHeight.setInt(data, 0);
                if (sourceIndex == requestedIndex) {
                    selectedIndex = imageData.size();
                }
                imageData.add(data);
            }
            if (imageData.isEmpty()) {
                warn("image preview rejected: no valid images, cvid=" + expectedCvid);
                return;
            }
            if (selectedIndex < 0 || selectedIndex >= imageData.size()) {
                selectedIndex = Math.max(0,
                        Math.min(requestedIndex, imageData.size() - 1));
            }

            invoke(nativeImageViewerOpen, nativeImageViewer,
                    activity, imageData, selectedIndex);
            info("native image viewer opened: cvid=" + activeCvid
                    + " expectedCvid=" + expectedCvid
                    + " images=" + imageData.size()
                    + " selectedIndex=" + selectedIndex);
        } catch (Throwable throwable) {
            error("native image viewer launch failed: cvid=" + expectedCvid
                    + " requestedIndex=" + requestedIndex, throwable);
        }
    }

    private void handleArticleIssueReport(
            Activity activity,
            WebView webView,
            long expectedCvid,
            String requestedArticleUrl) {
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        handler.post(() -> copyLinkAndOpenIssue(
                activity, webView, expectedCvid, requestedArticleUrl));
    }

    private void copyLinkAndOpenIssue(
            Activity activity,
            WebView webView,
            long expectedCvid,
            String requestedArticleUrl) {
        try {
            if (!isArticleFixEnabled() || activity == null || webView == null
                    || activity.isFinishing() || activity.isDestroyed()) {
                warn("article feedback rejected: cvid=" + expectedCvid
                        + " articleFix=" + isArticleFixEnabled());
                return;
            }
            long activeCvid = pageAccess.getCvid(activity);
            String pageUrl = webView.getUrl();
            String articleUrl = normalizeArticleUrl(requestedArticleUrl);
            boolean staleCvid = activeCvid <= 0L
                    || (expectedCvid > 0L && activeCvid != expectedCvid);
            if (staleCvid || !pageAccess.isTrustedArticleUrl(pageUrl)
                    || articleUrl == null) {
                warn("article feedback rejected for stale or untrusted page: expectedCvid="
                        + expectedCvid + " activeCvid=" + activeCvid
                        + " page=" + safeSchema(pageUrl)
                        + " report=" + safeSchema(requestedArticleUrl));
                return;
            }

            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                warn("article feedback could not access clipboard: cvid=" + activeCvid);
                Toast.makeText(activity, "无法复制专栏链接", Toast.LENGTH_SHORT).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("BiliFix专栏链接", articleUrl));

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_NEW_ISSUE_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                activity.startActivity(intent);
                Toast.makeText(activity, "专栏链接已复制", Toast.LENGTH_SHORT).show();
                info("article feedback opened: cvid=" + activeCvid
                        + " article=" + safeSchema(articleUrl));
            } catch (Throwable throwable) {
                Toast.makeText(activity, "链接已复制，请前往GitHub反馈",
                        Toast.LENGTH_SHORT).show();
                error("article feedback page launch failed: cvid=" + activeCvid,
                        throwable);
            }
        } catch (Throwable throwable) {
            error("article feedback failed: cvid=" + expectedCvid, throwable);
        }
    }

    private static String normalizeArticleUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ARTICLE_URL_LENGTH) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            boolean web = "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
            boolean bilibili = "www.bilibili.com".equalsIgnoreCase(host)
                    || "m.bilibili.com".equalsIgnoreCase(host);
            if (!web || !bilibili || path == null
                    || !(path.startsWith("/read/cv") || path.startsWith("/opus/"))) {
                return null;
            }
            return uri.buildUpon().scheme("https").build().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int clampImageDimension(int value) {
        return Math.max(0, Math.min(value, 100_000));
    }

    private static String normalizeImageUrl(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()) {
                return null;
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return uri.buildUpon().scheme("https").build().toString();
            }
            return uri.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }



    private boolean isArticleFixEnabled() {
        return module.isArticleFixEnabled();
    }

    private boolean isImagePreviewEnabled() {
        return module.isImagePreviewEnabled();
    }

    private void registerSettingsReceiver(Context context) {
        module.ensureFeatureSettings(context);
    }

    private void ensureSettingsLoaded(Context context) {
        module.ensureFeatureSettings(context);
    }

    private Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return module.load(loader, name);
    }

    private Method declaredMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return module.declaredMethod(owner, name, parameterTypes);
    }

    private Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        return module.declaredField(owner, name);
    }

    private Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        return module.invoke(method, receiver, args);
    }

    private void debug(String message) {
        module.debug(message);
    }

    private void info(String message) {
        module.info(message);
    }

    private void warn(String message) {
        module.warn(message);
    }

    private void error(String message, Throwable throwable) {
        module.error(message, throwable);
    }

    private void install(String label, ThrowingAction action) {
        try {
            action.run();
            info("hook group ready: " + label);
        } catch (Throwable throwable) {
            error("hook group unavailable: " + label, throwable);
        }
    }

    private static String safeSchema(String schema) {
        if (schema == null) {
            return "null";
        }
        try {
            Uri uri = Uri.parse(schema);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Throwable ignored) {
            return "<invalid-schema>";
        }
    }

    private static long parsePositiveLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static final class ArticleImageBridge {
        private final WeakReference<ArticleImagePreview> previewReference;
        private final WeakReference<Activity> activityReference;
        private final WeakReference<WebView> webViewReference;
        private final long cvid;

        ArticleImageBridge(
                ArticleImagePreview preview,
                Activity activity,
                WebView webView,
                long cvid) {
            previewReference = new WeakReference<>(preview);
            activityReference = new WeakReference<>(activity);
            webViewReference = new WeakReference<>(webView);
            this.cvid = cvid;
        }

        @JavascriptInterface
        public void openImages(String imagesJson, int index) {
            ArticleImagePreview preview = previewReference.get();
            if (preview == null) {
                return;
            }
            preview.handleImagePreviewRequest(
                    activityReference.get(), webViewReference.get(), cvid, imagesJson, index);
        }

        @JavascriptInterface
        public void reportArticleIssue(String articleUrl) {
            ArticleImagePreview preview = previewReference.get();
            if (preview == null) {
                return;
            }
            preview.handleArticleIssueReport(
                    activityReference.get(), webViewReference.get(), cvid, articleUrl);
        }
    }

    interface PageAccess {
        long getCvid(Activity activity) throws Throwable;

        boolean isTrustedArticleUrl(String url);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }
}
