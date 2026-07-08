package com.syziege.mca;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads chunks straight out of a Minecraft Anvil region file (r.X.Z.mca)
 * without going through the Bukkit world — this lets the web map render
 * terrain from the map files on disk, including unloaded chunks.
 */
public final class RegionFile implements AutoCloseable {

    private static final int SECTOR_SIZE = 4096;

    private final RandomAccessFile raf;
    private final int[] offsets = new int[1024];

    public RegionFile(Path path) throws IOException {
        this.raf = new RandomAccessFile(path.toFile(), "r");
        if (raf.length() >= 8192) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int b = i * 4;
                offsets[i] = ((header[b] & 0xFF) << 16) | ((header[b + 1] & 0xFF) << 8) | (header[b + 2] & 0xFF);
                offsets[i] = (offsets[i] << 8) | (header[b + 3] & 0xFF);
            }
        }
    }

    /**
     * Reads the NBT root compound for the chunk at local coordinates
     * (0..31, 0..31), or null if the chunk is not present in this region.
     */
    public Map<String, Object> readChunk(int localX, int localZ) throws IOException {
        int packed = offsets[(localX & 31) + (localZ & 31) * 32];
        int sectorOffset = packed >>> 8;
        int sectorCount = packed & 0xFF;
        if (sectorOffset == 0 || sectorCount == 0) {
            return null;
        }
        long pos = (long) sectorOffset * SECTOR_SIZE;
        if (pos + 5 > raf.length()) {
            return null;
        }
        raf.seek(pos);
        int length = raf.readInt();
        if (length <= 0 || length > sectorCount * SECTOR_SIZE) {
            return null;
        }
        int compression = raf.readUnsignedByte();
        byte[] data = new byte[length - 1];
        raf.readFully(data);

        InputStream raw = new ByteArrayInputStream(data);
        InputStream decompressed;
        switch (compression & 0x7F) {
            case 1:
                decompressed = new GZIPInputStream(raw);
                break;
            case 2:
                decompressed = new InflaterInputStream(raw);
                break;
            case 3:
                decompressed = raw;
                break;
            default:
                return null;
        }
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(decompressed))) {
            return Nbt.readRootCompound(in);
        }
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
