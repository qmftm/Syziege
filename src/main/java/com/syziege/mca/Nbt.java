package com.syziege.mca;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT reader. Parses a binary NBT stream into plain Java objects:
 * compounds become Map&lt;String,Object&gt;, lists become List&lt;Object&gt;,
 * arrays become byte[]/int[]/long[], primitives become their boxed types.
 */
public final class Nbt {

    private Nbt() {
    }

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    /** Reads a full NBT stream whose root tag is a compound. */
    public static Map<String, Object> readRootCompound(DataInput in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != TAG_COMPOUND) {
            throw new IOException("Root tag is not a compound: " + type);
        }
        in.readUTF(); // root name, usually empty
        return readCompound(in);
    }

    private static Map<String, Object> readCompound(DataInput in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInput in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] arr = new byte[in.readInt()];
                in.readFully(arr);
                return arr;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType));
                }
                return list;
            }
            case TAG_COMPOUND:
                return readCompound(in);
            case TAG_INT_ARRAY: {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readInt();
                }
                return arr;
            }
            case TAG_LONG_ARRAY: {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = in.readLong();
                }
                return arr;
            }
            default:
                throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getCompound(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    public static long[] getLongArray(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof long[] ? (long[]) v : null;
    }

    public static int getInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
}
