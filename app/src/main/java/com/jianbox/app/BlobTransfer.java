package com.jianbox.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/** Bounded, ordered writes for a renderer-owned file. No partial file is marked complete. */
final class BlobTransfer {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final int CHUNK_BYTES = 48 * 1024;
    private final File directory, temporary;
    private final String name;
    private final long expected;
    private final BufferedOutputStream output;
    private long written;
    private int nextSequence;
    private boolean closed;

    BlobTransfer(File directory, String name, long expected) throws IOException {
        if (expected < 0 || expected > MAX_BYTES) throw new IOException("临时文件大小无效或超过 512 MB");
        this.directory = directory.getCanonicalFile();
        if (!this.directory.isDirectory() && !this.directory.mkdirs()) throw new IOException("无法创建下载目录");
        this.name = safeName(name);
        this.expected = expected;
        temporary = Files.createTempFile(this.directory.toPath(), ".jianbox-", ".part").toFile();
        output = new BufferedOutputStream(Files.newOutputStream(temporary.toPath()), CHUNK_BYTES);
    }

    void write(int sequence, String encoded) throws IOException {
        if (closed || sequence != nextSequence) throw new IOException("下载分块顺序异常");
        if (encoded == null || encoded.length() > CHUNK_BYTES / 3 * 4) throw new IOException("下载分块过大");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) { throw new IOException("下载分块编码异常", error); }
        if (bytes.length == 0 || written + bytes.length > expected) throw new IOException("下载数据长度异常");
        output.write(bytes);
        written += bytes.length;
        nextSequence++;
    }

    File finish() throws IOException {
        if (closed || written != expected) throw new IOException("下载数据不完整，请重新下载");
        output.close();
        closed = true;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 1000; i++) {
            File target = new File(directory, i == 0 ? name : base + " (" + i + ")" + extension);
            if (!target.createNewFile()) continue;
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (IOException error) {
                Files.deleteIfExists(target.toPath());
                throw error;
            }
        }
        throw new IOException("同名文件过多，请整理下载目录");
    }

    void abort() {
        try { if (!closed) output.close(); } catch (IOException ignored) {}
        closed = true;
        try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) {}
    }

    long written() { return written; }
    File temporaryFile() { return temporary; }

    static String safeName(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim().replaceAll("[. ]+$", "");
        if (safe.isEmpty()) safe = "download.bin";
        if (safe.length() > 160) safe = safe.substring(0, 160);
        if (safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) safe = "_" + safe;
        return safe;
    }
}
