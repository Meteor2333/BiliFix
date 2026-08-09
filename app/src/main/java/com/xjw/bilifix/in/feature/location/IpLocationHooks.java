package com.xjw.bilifix.in.feature.location;

import android.content.Context;
import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Supplies the domestic request identity needed by the host's existing IP location UI. */
public final class IpLocationHooks {
    private static final String DOMESTIC_MOBI_APP = "android";
    private static final String REPLY_SERVICE =
            "bilibili.main.community.reply.v1.Reply/";
    private static final String PROFILE_PATH = "/x/v2/space";

    private static final Set<String> COMMENT_RPC_READ_METHODS = immutableSet(
            "MainList",
            "DetailList",
            "DialogList",
            "PreviewList",
            "ReplyInfo",
            "SearchItem",
            "SearchItemPreHook",
            "ShareRepliesInfo");

    private static final Set<String> COMMENT_REST_READ_PATHS = immutableSet(
            "/x/v2/reply",
            "/x/v2/reply/main",
            "/x/v2/reply/reply",
            "/x/v2/reply/reply/cursor",
            "/x/v2/reply/folded",
            "/x/v2/reply/reply/folded",
            "/x/v2/reply/msg_feed_list");

    private final HookApi module;
    private final ClassLoader classLoader;
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();
    private final AtomicInteger requestLogCount = new AtomicInteger();
    private final AtomicInteger metadataLogCount = new AtomicInteger();
    private final AtomicInteger commentHitLogCount = new AtomicInteger();
    private final AtomicInteger commentMissLogCount = new AtomicInteger();
    private final AtomicInteger profileLogCount = new AtomicInteger();

    private volatile ProtoMobiAppRewriter metadataRewriter;
    private volatile ProtoMobiAppRewriter deviceRewriter;

    public IpLocationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installGroup("profile and REST comment request identity", this::installRestIdentityHooks);
        installGroup("Moss comment request identity", this::installMossIdentityHooks);
        installGroup("comment response diagnostics", this::installCommentDiagnostics);
        installGroup("profile bottom-tag diagnostics", this::installProfileBottomTagDiagnostics);
        installGroup("profile header-tag diagnostics", this::installProfileHeaderTagDiagnostics);
    }

    private void installRestIdentityHooks() throws Throwable {
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Class<?> interceptorClass = module.load(classLoader,
                "com.bilibili.okretro.interceptor.a");
        Class<?> libBiliClass = module.load(classLoader,
                "com.bilibili.nativelibrary.LibBili");
        Class<?> configClass = module.load(classLoader, "dc.a");

        Method requestUrl = module.declaredMethod(requestClass, "l");
        Method requestVerb = module.declaredMethod(requestClass, "h");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", requestClass);
        Method addCommonParam = module.declaredMethod(
                interceptorClass, "addCommonParam", Map.class);
        Method domesticAppKey = module.declaredMethod(libBiliClass, "f", String.class);
        Method userAgent = module.declaredMethod(configClass, "c");

        module.deoptimizeFeatureMethod(intercept);
        module.deoptimizeFeatureMethod(addCommonParam);

        module.addHook("IP location targeted REST scope", intercept, hookChain -> {
            Object request = hookChain.getArg(0);
            String url = String.valueOf(module.invoke(requestUrl, request));
            String verb = String.valueOf(module.invoke(requestVerb, request));
            ScopeKind kind = classifyRestRequest(url, verb);
            if (kind == null) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = kind.logName + " " + uri.getHost() + uri.getEncodedPath();
            logTargetRequest(source);
            return withScope(kind, source, hookChain::proceed);
        });

        module.addHook("IP location domestic REST parameters", addCommonParam, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !module.isIpLocationEnabled()) {
                return result;
            }
            Object value = hookChain.getArg(0);
            if (!(value instanceof Map)) {
                module.warn("IP location REST parameters unavailable: source="
                        + scope.source + " value=" + summarize(value));
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> parameters = (Map<Object, Object>) value;
            parameters.put("mobi_app", DOMESTIC_MOBI_APP);
            parameters.put("appkey",
                    module.invoke(domesticAppKey, null, DOMESTIC_MOBI_APP));
            module.debug("IP location REST parameters rewritten: source="
                    + scope.source + " mobi_app=" + DOMESTIC_MOBI_APP
                    + " build=" + parameters.get("build"));
            return result;
        });

        module.addHook("IP location domestic REST user agent", userAgent, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || !scope.kind.isRest() || !(result instanceof String)
                    || !module.isIpLocationEnabled()) {
                return result;
            }
            String original = (String) result;
            String rewritten = original.replace(
                    "mobi_app/android_i", "mobi_app/android");
            if (!original.equals(rewritten)) {
                module.debug("IP location REST user agent rewritten: source="
                        + scope.source);
            }
            return rewritten;
        });
    }

    private void installMossIdentityHooks() throws Throwable {
        ProtoMobiAppRewriter metadata = new ProtoMobiAppRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata");
        ProtoMobiAppRewriter device = new ProtoMobiAppRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device");
        metadataRewriter = metadata;
        deviceRewriter = device;

        Class<?> metadataFactoryClass = module.load(classLoader, "if1.a");
        Method createMetadata = module.declaredMethod(metadataFactoryClass, "n");
        Method createDevice = module.declaredMethod(metadataFactoryClass, "k");
        module.deoptimizeFeatureMethod(createMetadata);
        module.deoptimizeFeatureMethod(createDevice);
        installProtoRewriteHook("IP location Moss metadata", createMetadata, metadata);
        installProtoRewriteHook("IP location Moss device", createDevice, device);

        Class<?> methodDescriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> generatedMessageClass = module.load(classLoader,
                "com.google.protobuf.GeneratedMessageLite");
        Class<?> responseHandlerClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossResponseHandler");
        Class<?> httpRuleClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossHttpRule");
        Class<?> serviceClass = module.load(classLoader,
                "com.bilibili.lib.moss.api.MossServiceImp");
        Method descriptorName = module.declaredMethod(methodDescriptorClass, "c");
        Method asyncUnaryCall = module.declaredMethod(serviceClass, "asyncUnaryCall",
                methodDescriptorClass, generatedMessageClass,
                responseHandlerClass, httpRuleClass);
        Method blockingUnaryCall = module.declaredMethod(serviceClass, "blockingUnaryCall",
                methodDescriptorClass, generatedMessageClass, httpRuleClass);
        module.deoptimizeFeatureMethod(asyncUnaryCall);
        module.deoptimizeFeatureMethod(blockingUnaryCall);
        installMossCallScope("IP location async comment RPC", asyncUnaryCall, descriptorName);
        installMossCallScope("IP location blocking comment RPC", blockingUnaryCall,
                descriptorName);

        installMossOkHttpScope();
    }

    private void installProtoRewriteHook(
            String label, Method factory, ProtoMobiAppRewriter rewriter) {
        module.addHook(label, factory, hookChain -> {
            Object result = hookChain.proceed();
            RequestScope scope = requestScope.get();
            if (scope == null || scope.kind != ScopeKind.COMMENT_RPC
                    || !module.isIpLocationEnabled() || !(result instanceof byte[])) {
                return result;
            }
            try {
                ProtoRewriteResult rewritten = rewriter.rewrite((byte[]) result);
                if (metadataLogCount.incrementAndGet() <= 30) {
                    module.debug(label + " rewritten: source=" + scope.source
                            + " oldMobiApp=" + rewritten.originalMobiApp
                            + " newMobiApp=" + rewritten.rewrittenMobiApp
                            + " bytes=" + rewritten.bytes.length);
                }
                return rewritten.bytes;
            } catch (Throwable throwable) {
                module.error(label + " rewrite failed; original metadata retained: source="
                        + scope.source, throwable);
                return result;
            }
        });
    }

    private void installMossCallScope(
            String label, Method callMethod, Method descriptorName) {
        module.addHook(label, callMethod, hookChain -> {
            Object descriptor = hookChain.getArg(0);
            String fullMethodName = String.valueOf(
                    module.invoke(descriptorName, descriptor));
            if (!isCommentReadRpc(fullMethodName)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            logTargetRequest("Moss " + fullMethodName);
            return withScope(ScopeKind.COMMENT_RPC,
                    "Moss " + fullMethodName, hookChain::proceed);
        });
    }

    private void installMossOkHttpScope() throws Throwable {
        Class<?> interceptorClass = module.load(classLoader, "cg1.a");
        Class<?> chainClass = module.load(classLoader, "okhttp3.u$a");
        Class<?> requestClass = module.load(classLoader, "okhttp3.a0");
        Method intercept = module.declaredMethod(interceptorClass, "intercept", chainClass);
        Method getRequest = module.declaredMethod(chainClass, "request");
        Method getUrl = module.declaredMethod(requestClass, "l");
        module.deoptimizeFeatureMethod(intercept);

        module.addHook("IP location Moss OkHttp scope", intercept, hookChain -> {
            Object chain = hookChain.getArg(0);
            Object request = module.invoke(getRequest, chain);
            String url = String.valueOf(module.invoke(getUrl, request));
            if (!isCommentReadRpc(url)) {
                return hookChain.proceed();
            }
            module.ensureFeatureSettings(currentApplication());
            if (!module.isIpLocationEnabled()) {
                return hookChain.proceed();
            }
            Uri uri = Uri.parse(url);
            String source = "Moss-OkHttp " + uri.getEncodedPath();
            logTargetRequest(source);
            return withScope(ScopeKind.COMMENT_RPC, source, hookChain::proceed);
        });
    }

    private void installCommentDiagnostics() throws Throwable {
        Class<?> rpcControlClass = module.load(classLoader,
                "com.bapis.bilibili.main.community.reply.v1.ReplyControl");
        Method getLocation = module.declaredMethod(rpcControlClass, "getLocation");
        module.addHook("IP location comment RPC response", getLocation, hookChain -> {
            Object result = hookChain.proceed();
            logCommentLocation("ReplyControl.getLocation", result);
            return result;
        });

        Class<?> commentClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment");
        Class<?> restControlClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.model.BiliComment$ReplyControl");
        Class<?> viewModelClass = module.load(classLoader,
                "com.bilibili.app.comm.comment2.comments.viewmodel.t0");
        Field replyControl = module.declaredField(commentClass, "replyControl");
        Field restLocation = module.declaredField(restControlClass, "location");
        Method bindComment = module.declaredMethod(viewModelClass, "N", commentClass);
        module.addHook("IP location comment2 binding", bindComment, hookChain -> {
            if (module.isIpLocationEnabled()) {
                Object comment = hookChain.getArg(0);
                Object control = comment == null ? null : replyControl.get(comment);
                logCommentLocation("comment2 binding",
                        control == null ? null : restLocation.get(control));
            }
            return hookChain.proceed();
        });
    }

    private void installProfileBottomTagDiagnostics() throws Throwable {
        Class<?> containerClass = module.load(classLoader,
                "com.bilibili.app.authorspace.ui.SpaceHeaderBottomTagsContainer");
        Class<?> tagClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.b");
        Method render = module.declaredMethod(containerClass, "r", List.class);
        Field type = module.declaredField(tagClass, "b");
        Field title = module.declaredField(tagClass, "d");
        module.addHook("IP location profile bottom tags", render, hookChain -> {
            if (module.isIpLocationEnabled()) {
                logProfileTags("space_tag_bottom", hookChain.getArg(0), type, title);
            }
            return hookChain.proceed();
        });
    }

    private void installProfileHeaderTagDiagnostics() throws Throwable {
        Class<?> containerClass = module.load(classLoader,
                "com.bilibili.app.authorspace.ui.headerinfo.HeaderInfoMultiLineTags");
        Class<?> tagClass = module.load(classLoader,
                "com.bilibili.app.authorspace.api.BiliHeaderTag");
        Method render = module.declaredMethod(containerClass, "s", List.class);
        Field type = module.declaredField(tagClass, "type");
        Field title = module.declaredField(tagClass, "text");
        module.addHook("IP location profile header tags", render, hookChain -> {
            if (module.isIpLocationEnabled()) {
                logProfileTags("space_tag", hookChain.getArg(0), type, title);
            }
            return hookChain.proceed();
        });
    }

    private void logCommentLocation(String source, Object value) {
        if (!module.isIpLocationEnabled()) {
            return;
        }
        String location = value instanceof String ? (String) value : null;
        if (location != null && !location.isEmpty()) {
            if (commentHitLogCount.incrementAndGet() <= 20) {
                module.info("IP location received for comment: source=" + source
                        + " value=" + location);
            }
        } else if (commentMissLogCount.incrementAndGet() <= 5) {
            module.debug("IP location absent from comment response: source=" + source);
        }
    }

    private void logProfileTags(String source, Object value, Field type, Field title) {
        int sequence = profileLogCount.incrementAndGet();
        if (sequence > 20) {
            return;
        }
        if (!(value instanceof List)) {
            module.debug("IP location profile tags absent: source=" + source
                    + " value=" + summarize(value));
            return;
        }
        List<?> tags = (List<?>) value;
        for (Object tag : tags) {
            try {
                if (tag != null && "location".equals(type.get(tag))) {
                    module.info("IP location received for profile: source=" + source
                            + " value=" + title.get(tag));
                    return;
                }
            } catch (Throwable throwable) {
                module.error("IP location profile tag inspection failed: source=" + source,
                        throwable);
                return;
            }
        }
        module.debug("IP location absent from profile tags: source=" + source
                + " count=" + tags.size());
    }

    private ScopeKind classifyRestRequest(String rawUrl, String verb) {
        if (!"GET".equalsIgnoreCase(verb)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            String path = uri.getEncodedPath();
            if ("app.bilibili.com".equalsIgnoreCase(host) && PROFILE_PATH.equals(path)) {
                return ScopeKind.PROFILE_REST;
            }
            if ("api.bilibili.com".equalsIgnoreCase(host)
                    && COMMENT_REST_READ_PATHS.contains(path)) {
                return ScopeKind.COMMENT_REST;
            }
        } catch (Throwable throwable) {
            module.debug("IP location REST classification skipped: " + throwable);
        }
        return null;
    }

    private static boolean isCommentReadRpc(String value) {
        if (value == null) {
            return false;
        }
        int serviceIndex = value.indexOf(REPLY_SERVICE);
        if (serviceIndex < 0) {
            return false;
        }
        int methodStart = serviceIndex + REPLY_SERVICE.length();
        int methodEnd = methodStart;
        while (methodEnd < value.length()) {
            char current = value.charAt(methodEnd);
            if (current == '?' || current == '#' || current == '/') {
                break;
            }
            methodEnd++;
        }
        return COMMENT_RPC_READ_METHODS.contains(value.substring(methodStart, methodEnd));
    }

    private Object withScope(
            ScopeKind kind, String source, ThrowingSupplier action) throws Throwable {
        RequestScope previous = requestScope.get();
        requestScope.set(new RequestScope(kind, source));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                requestScope.remove();
            } else {
                requestScope.set(previous);
            }
        }
    }

    private void logTargetRequest(String source) {
        if (requestLogCount.incrementAndGet() <= 30) {
            module.info("IP location domestic identity enabled: source=" + source
                    + " mobi_app=" + DOMESTIC_MOBI_APP);
        }
    }

    private Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installGroup(String label, ThrowingAction action) {
        try {
            action.run();
            module.info("IP location hook group ready: " + label);
        } catch (Throwable throwable) {
            module.error("IP location hook group unavailable: " + label, throwable);
        }
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "(" + value + ")";
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private enum ScopeKind {
        PROFILE_REST("profile-rest"),
        COMMENT_REST("comment-rest"),
        COMMENT_RPC("comment-rpc");

        private final String logName;

        ScopeKind(String logName) {
            this.logName = logName;
        }

        private boolean isRest() {
            return this == PROFILE_REST || this == COMMENT_REST;
        }
    }

    private static final class RequestScope {
        private final ScopeKind kind;
        private final String source;

        private RequestScope(ScopeKind kind, String source) {
            this.kind = kind;
            this.source = source;
        }
    }

    private static final class ProtoMobiAppRewriter {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getMobiApp;
        private final Method toBuilder;
        private final Method setMobiApp;
        private final Method build;
        private final Method toByteArray;

        private ProtoMobiAppRewriter(
                HookApi module, ClassLoader classLoader, String messageClassName)
                throws Throwable {
            this.module = module;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getMobiApp = module.publicMethod(messageClass, "getMobiApp");
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setMobiApp = module.publicMethod(builderClass, "setMobiApp", String.class);
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        private ProtoRewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String original = String.valueOf(module.invoke(getMobiApp, message));
            if (DOMESTIC_MOBI_APP.equals(original)) {
                return new ProtoRewriteResult(source, original, original);
            }
            Object builder = module.invoke(toBuilder, message);
            module.invoke(setMobiApp, builder, DOMESTIC_MOBI_APP);
            Object rewrittenMessage = module.invoke(build, builder);
            Object rewrittenBytes = module.invoke(toByteArray, rewrittenMessage);
            if (!(rewrittenBytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned "
                        + summarize(rewrittenBytes));
            }
            return new ProtoRewriteResult(
                    (byte[]) rewrittenBytes, original, DOMESTIC_MOBI_APP);
        }
    }

    private static final class ProtoRewriteResult {
        private final byte[] bytes;
        private final String originalMobiApp;
        private final String rewrittenMobiApp;

        private ProtoRewriteResult(
                byte[] bytes, String originalMobiApp, String rewrittenMobiApp) {
            this.bytes = bytes;
            this.originalMobiApp = originalMobiApp;
            this.rewrittenMobiApp = rewrittenMobiApp;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Throwable;
    }
}
