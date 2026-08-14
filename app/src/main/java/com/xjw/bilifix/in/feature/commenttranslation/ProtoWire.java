package com.xjw.bilifix.in.feature.commenttranslation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal protobuf wire codec for the three messages used by TranslateReply. */
final class ProtoWire {
    private ProtoWire() {
    }

    static byte[] encodeRequest(long type, long oid, long rpid) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32);
        writeTag(output, 1, 0);
        writeVarint(output, type);
        writeTag(output, 2, 0);
        writeVarint(output, oid);
        ByteArrayOutputStream packedRpids = new ByteArrayOutputStream(10);
        writeVarint(packedRpids, rpid);
        writeTag(output, 3, 2);
        writeBytes(output, packedRpids.toByteArray());
        return output.toByteArray();
    }

    static TranslationPayload decodeResponse(byte[] response, long expectedRpid) {
        Reader root = new Reader(response);
        List<Long> keys = new ArrayList<>();
        while (root.hasRemaining()) {
            int tag = root.readTag();
            if (tag == 0) {
                break;
            }
            if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                byte[] entryBytes = root.readBytes();
                MapEntry entry = decodeEntry(entryBytes);
                if (entry != null) {
                    keys.add(entry.rpid);
                    if (entry.rpid == expectedRpid && entry.message != null) {
                        return new TranslationPayload(entry.message, keys, response.length);
                    }
                }
            } else {
                root.skipField(tag & 7);
            }
        }
        return new TranslationPayload(null, keys, response.length);
    }

    static int readVarintField(byte[] message, int expectedField, int defaultValue) {
        Reader reader = new Reader(message);
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            if (tag == 0) {
                break;
            }
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (field == expectedField && wireType == 0) {
                return Reader.checkedInt(reader.readVarint());
            }
            reader.skipField(wireType);
        }
        return defaultValue;
    }

    private static MapEntry decodeEntry(byte[] bytes) {
        Reader entry = new Reader(bytes);
        long rpid = 0;
        String message = null;
        while (entry.hasRemaining()) {
            int tag = entry.readTag();
            if (tag == 0) {
                break;
            }
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (field == 1 && wireType == 0) {
                rpid = entry.readVarint();
            } else if (field == 2 && wireType == 2) {
                message = decodeReplyInfo(entry.readBytes());
            } else {
                entry.skipField(wireType);
            }
        }
        return new MapEntry(rpid, message);
    }

    private static String decodeReplyInfo(byte[] bytes) {
        Reader reply = new Reader(bytes);
        while (reply.hasRemaining()) {
            int tag = reply.readTag();
            if (tag == 0) {
                break;
            }
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (field == 17 && wireType == 2) {
                String message = decodeContent(reply.readBytes());
                if (message != null && !message.trim().isEmpty()) {
                    return message;
                }
            } else {
                reply.skipField(wireType);
            }
        }
        return null;
    }

    private static String decodeContent(byte[] bytes) {
        Reader content = new Reader(bytes);
        while (content.hasRemaining()) {
            int tag = content.readTag();
            if (tag == 0) {
                break;
            }
            if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                return new String(content.readBytes(), StandardCharsets.UTF_8);
            }
            content.skipField(tag & 7);
        }
        return null;
    }

    private static void writeTag(ByteArrayOutputStream output, int field, int wireType) {
        writeVarint(output, ((long) field << 3) | wireType);
    }

    private static void writeBytes(ByteArrayOutputStream output, byte[] bytes) {
        writeVarint(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write(((int) remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    static final class TranslationPayload {
        final String message;
        final List<Long> responseRpids;
        final int responseBytes;

        TranslationPayload(String message, List<Long> responseRpids, int responseBytes) {
            this.message = message;
            this.responseRpids = Collections.unmodifiableList(new ArrayList<>(responseRpids));
            this.responseBytes = responseBytes;
        }
    }

    private static final class MapEntry {
        final long rpid;
        final String message;

        MapEntry(long rpid, String message) {
            this.rpid = rpid;
            this.message = message;
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        boolean hasRemaining() {
            return position < bytes.length;
        }

        int readTag() {
            return hasRemaining() ? checkedInt(readVarint()) : 0;
        }

        long readVarint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int current = bytes[position++] & 0xff;
                value |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("malformed protobuf varint");
        }

        byte[] readBytes() {
            int length = checkedInt(readVarint());
            require(length);
            byte[] result = new byte[length];
            System.arraycopy(bytes, position, result, 0, length);
            position += length;
            return result;
        }

        void skipField(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint();
                    return;
                case 1:
                    skip(8);
                    return;
                case 2:
                    skip(checkedInt(readVarint()));
                    return;
                case 5:
                    skip(4);
                    return;
                default:
                    throw new IllegalArgumentException(
                            "unsupported protobuf wire type: " + wireType);
            }
        }

        private void skip(int count) {
            require(count);
            position += count;
        }

        private void require(int count) {
            if (count < 0 || position + count > bytes.length) {
                throw new IllegalArgumentException(
                        "truncated protobuf: position=" + position
                                + " count=" + count + " size=" + bytes.length);
            }
        }

        static int checkedInt(long value) {
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("protobuf length out of range: " + value);
            }
            return (int) value;
        }
    }
}
