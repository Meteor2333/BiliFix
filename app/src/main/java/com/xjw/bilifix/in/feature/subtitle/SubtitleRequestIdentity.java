package com.xjw.bilifix.in.feature.subtitle;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;

/** Rewrites only the Moss identity headers used while loading video subtitle metadata. */
final class SubtitleRequestIdentity {
    static final String MOBI_APP = "android_hd";
    static final int BUILD = 2001100;
    static final int APP_ID = 5;
    static final String VERSION_NAME = "2.0.1";
    static final String CHANNEL = "master";

    private final ProtoIdentityRewriter metadata;
    private final ProtoIdentityRewriter device;
    private final ProtoFawkesRewriter fawkes;

    SubtitleRequestIdentity(HookApi module, ClassLoader classLoader) throws Throwable {
        metadata = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.Metadata", false);
        device = new ProtoIdentityRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.device.Device", true);
        fawkes = new ProtoFawkesRewriter(
                module, classLoader, "com.bapis.bilibili.metadata.fawkes.FawkesReq");
    }

    RewriteResult rewriteMetadata(byte[] source) throws Throwable {
        return metadata.rewrite(source);
    }

    RewriteResult rewriteDevice(byte[] source) throws Throwable {
        return device.rewrite(source);
    }

    RewriteResult rewriteFawkes(byte[] source) throws Throwable {
        return fawkes.rewrite(source);
    }

    static String identity(boolean includeDeviceDetails) {
        String value = MOBI_APP + "/" + BUILD + "/" + CHANNEL;
        if (includeDeviceDetails) {
            value += "/appId=" + APP_ID + "/version=" + VERSION_NAME;
        }
        return value;
    }

    private static final class ProtoIdentityRewriter {
        private final HookApi module;
        private final boolean includesDeviceDetails;
        private final Method parseFrom;
        private final Method getMobiApp;
        private final Method getBuild;
        private final Method getChannel;
        private final Method getAppId;
        private final Method getVersionName;
        private final Method toBuilder;
        private final Method setMobiApp;
        private final Method setBuild;
        private final Method setChannel;
        private final Method setAppId;
        private final Method setVersionName;
        private final Method build;
        private final Method toByteArray;

        private ProtoIdentityRewriter(
                HookApi module,
                ClassLoader classLoader,
                String messageClassName,
                boolean includesDeviceDetails) throws Throwable {
            this.module = module;
            this.includesDeviceDetails = includesDeviceDetails;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getMobiApp = module.publicMethod(messageClass, "getMobiApp");
            getBuild = module.publicMethod(messageClass, "getBuild");
            getChannel = module.publicMethod(messageClass, "getChannel");
            getAppId = includesDeviceDetails
                    ? module.publicMethod(messageClass, "getAppId") : null;
            getVersionName = includesDeviceDetails
                    ? module.publicMethod(messageClass, "getVersionName") : null;
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setMobiApp = module.publicMethod(builderClass, "setMobiApp", String.class);
            setBuild = module.publicMethod(builderClass, "setBuild", int.class);
            setChannel = module.publicMethod(builderClass, "setChannel", String.class);
            setAppId = includesDeviceDetails
                    ? module.publicMethod(builderClass, "setAppId", int.class) : null;
            setVersionName = includesDeviceDetails
                    ? module.publicMethod(builderClass, "setVersionName", String.class) : null;
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        private RewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalIdentity = module.invoke(getMobiApp, message)
                    + "/" + module.invoke(getBuild, message)
                    + "/" + module.invoke(getChannel, message);
            if (includesDeviceDetails) {
                originalIdentity += "/appId=" + module.invoke(getAppId, message)
                        + "/version=" + module.invoke(getVersionName, message);
            }

            Object builder = module.invoke(toBuilder, message);
            module.invoke(setMobiApp, builder, MOBI_APP);
            module.invoke(setBuild, builder, BUILD);
            module.invoke(setChannel, builder, CHANNEL);
            if (includesDeviceDetails) {
                module.invoke(setAppId, builder, APP_ID);
                module.invoke(setVersionName, builder, VERSION_NAME);
            }
            Object rewritten = module.invoke(build, builder);
            Object bytes = module.invoke(toByteArray, rewritten);
            if (!(bytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned " + summarize(bytes));
            }
            return new RewriteResult(
                    (byte[]) bytes, originalIdentity, identity(includesDeviceDetails));
        }
    }

    private static final class ProtoFawkesRewriter {
        private final HookApi module;
        private final Method parseFrom;
        private final Method getAppkey;
        private final Method toBuilder;
        private final Method setAppkey;
        private final Method build;
        private final Method toByteArray;

        private ProtoFawkesRewriter(
                HookApi module, ClassLoader classLoader, String messageClassName)
                throws Throwable {
            this.module = module;
            Class<?> messageClass = module.load(classLoader, messageClassName);
            Class<?> builderClass = module.load(classLoader, messageClassName + "$b");
            parseFrom = module.publicMethod(messageClass, "parseFrom", byte[].class);
            getAppkey = module.publicMethod(messageClass, "getAppkey");
            toBuilder = module.publicMethod(messageClass, "toBuilder");
            setAppkey = module.publicMethod(builderClass, "setAppkey", String.class);
            build = module.publicMethod(builderClass, "build");
            toByteArray = module.publicMethod(messageClass, "toByteArray");
        }

        private RewriteResult rewrite(byte[] source) throws Throwable {
            Object message = module.invoke(parseFrom, null, (Object) source);
            String originalAppkey = String.valueOf(module.invoke(getAppkey, message));
            Object builder = module.invoke(toBuilder, message);
            module.invoke(setAppkey, builder, MOBI_APP);
            Object rewritten = module.invoke(build, builder);
            Object bytes = module.invoke(toByteArray, rewritten);
            if (!(bytes instanceof byte[])) {
                throw new IllegalStateException("toByteArray returned " + summarize(bytes));
            }
            return new RewriteResult(
                    (byte[]) bytes, "appkey=" + originalAppkey, "appkey=" + MOBI_APP);
        }
    }

    static final class RewriteResult {
        final byte[] bytes;
        final String originalIdentity;
        final String rewrittenIdentity;

        private RewriteResult(
                byte[] bytes, String originalIdentity, String rewrittenIdentity) {
            this.bytes = bytes;
            this.originalIdentity = originalIdentity;
            this.rewrittenIdentity = rewrittenIdentity;
        }
    }

    private static String summarize(Object value) {
        return value == null ? "null" : value.getClass().getName() + "(" + value + ")";
    }
}
