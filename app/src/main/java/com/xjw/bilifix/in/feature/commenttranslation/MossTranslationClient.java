package com.xjw.bilifix.in.feature.commenttranslation;

import com.xjw.bilifix.in.core.HookApi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Calls TranslateReply through the host Moss service and its authenticated middlewares. */
final class MossTranslationClient {
    private static final String FULL_METHOD_NAME =
            "bilibili.main.community.reply.v1.Reply/TranslateReply";

    private final HookApi module;
    private final Object mossService;
    private final Object methodDescriptor;
    private final Method parseEmpty;
    private final Method emptyToByteArray;
    private final Method blockingUnaryCall;

    MossTranslationClient(HookApi module, ClassLoader classLoader) throws Throwable {
        this.module = module;

        Class<?> replyMossClass = module.load(
                classLoader, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss");
        Object replyMoss = replyMossClass.getConstructor().newInstance();
        Field serviceField = module.declaredField(replyMossClass, "service");
        mossService = serviceField.get(replyMoss);

        Class<?> descriptorClass = module.load(classLoader, "io.grpc.MethodDescriptor");
        Class<?> descriptorBuilderClass = module.load(
                classLoader, "io.grpc.MethodDescriptor$b");
        Class<?> methodTypeClass = module.load(
                classLoader, "io.grpc.MethodDescriptor$MethodType");
        Class<?> marshallerClass = module.load(
                classLoader, "io.grpc.MethodDescriptor$c");
        Class<?> generatedMessageClass = module.load(
                classLoader, "com.google.protobuf.GeneratedMessageLite");
        Class<?> emptyClass = module.load(classLoader, "com.google.protobuf.Empty");
        Class<?> httpRuleClass = module.load(
                classLoader, "com.bilibili.lib.moss.api.MossHttpRule");

        parseEmpty = module.publicMethod(emptyClass, "parseFrom", byte[].class);
        emptyToByteArray = module.publicMethod(emptyClass, "toByteArray");

        Object requestMarshaller = createMarshaller(
                marshallerClass, emptyClass, parseEmpty, emptyToByteArray, true);
        Object responseMarshaller = createMarshaller(
                marshallerClass, emptyClass, parseEmpty, emptyToByteArray, false);

        Method newBuilder = module.publicMethod(descriptorClass, "i");
        Method setType = module.publicMethod(
                descriptorBuilderClass, "f", methodTypeClass);
        Method setName = module.publicMethod(
                descriptorBuilderClass, "b", String.class);
        Method setRequestMarshaller = module.publicMethod(
                descriptorBuilderClass, "c", marshallerClass);
        Method setResponseMarshaller = module.publicMethod(
                descriptorBuilderClass, "d", marshallerClass);
        Method setSampled = module.publicMethod(
                descriptorBuilderClass, "e", boolean.class);
        Method build = module.publicMethod(descriptorBuilderClass, "a");

        Object builder = module.invoke(newBuilder, null);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object unary = Enum.valueOf((Class<? extends Enum>) methodTypeClass, "UNARY");
        module.invoke(setType, builder, unary);
        module.invoke(setName, builder, FULL_METHOD_NAME);
        module.invoke(setSampled, builder, true);
        module.invoke(setRequestMarshaller, builder, requestMarshaller);
        module.invoke(setResponseMarshaller, builder, responseMarshaller);
        methodDescriptor = module.invoke(build, builder);

        blockingUnaryCall = module.publicMethod(
                mossService.getClass(), "blockingUnaryCall", descriptorClass,
                generatedMessageClass, httpRuleClass);
        module.info("comment translation Moss client ready: method=" + FULL_METHOD_NAME
                + " service=" + mossService.getClass().getName());
    }

    ProtoWire.TranslationPayload translate(long type, long oid, long rpid) throws Throwable {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        byte[] requestBytes = ProtoWire.encodeRequest(type, oid, rpid);
        Object request = module.invoke(parseEmpty, null, requestBytes);
        module.info("comment translation request started: type=" + type
                + " oid=" + oid + " rpid=" + rpid
                + " requestBytes=" + requestBytes.length);
        Object response = module.invoke(
                blockingUnaryCall, mossService, methodDescriptor, request, null);
        byte[] responseBytes = response == null
                ? new byte[0]
                : (byte[]) module.invoke(emptyToByteArray, response);
        ProtoWire.TranslationPayload payload =
                ProtoWire.decodeResponse(responseBytes, rpid);
        module.info("comment translation response received: type=" + type
                + " oid=" + oid + " rpid=" + rpid
                + " elapsedMs="
                + (android.os.SystemClock.elapsedRealtime() - startedAt)
                + " responseBytes=" + payload.responseBytes
                + " responseRpids=" + payload.responseRpids
                + " translatedChars="
                + (payload.message == null ? 0 : payload.message.length()));
        return payload;
    }

    private Object createMarshaller(
            Class<?> marshallerClass,
            Class<?> emptyClass,
            Method parseMethod,
            Method toByteArrayMethod,
            boolean request) {
        return Proxy.newProxyInstance(
                marshallerClass.getClassLoader(),
                new Class<?>[]{marshallerClass},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("a".equals(methodName) && args != null && args.length == 1) {
                        return new ByteArrayInputStream(
                                (byte[]) module.invoke(toByteArrayMethod, args[0]));
                    }
                    if ("c".equals(methodName) && args != null && args.length == 1) {
                        byte[] bytes = readAll((InputStream) args[0]);
                        return module.invoke(parseMethod, null, bytes);
                    }
                    if ("toString".equals(methodName)) {
                        return "BiliFixTranslateReply"
                                + (request ? "Request" : "Response") + "Marshaller";
                    }
                    if ("hashCode".equals(methodName)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(methodName)) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    if (method.getReturnType() == emptyClass) {
                        return module.invoke(parseMethod, null, new byte[0]);
                    }
                    return null;
                });
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
