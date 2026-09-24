package com.jianbox.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

public final class BlobTransferTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks + " failed"); }
    private interface Operation { void run() throws Exception; }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Invalid transfer accepted"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        File dir = Files.createTempDirectory(root, "blob-").toFile();
        byte[] data = new byte[BlobTransfer.CHUNK_BYTES * 2 + 17];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37);
        BlobTransfer transfer = new BlobTransfer(dir, "wallpaper.jpg", data.length);
        for (int offset = 0, sequence = 0; offset < data.length; offset += BlobTransfer.CHUNK_BYTES) {
            transfer.write(sequence++, Base64.getEncoder().encodeToString(Arrays.copyOfRange(data, offset, Math.min(data.length, offset + BlobTransfer.CHUNK_BYTES))));
        }
        check(transfer.written() == data.length);
        File saved = transfer.finish();
        check(Arrays.equals(data, Files.readAllBytes(saved.toPath())));
        check(!transfer.temporaryFile().exists());
        rejected(() -> transfer.write(3, "YQ=="));

        BlobTransfer duplicate = new BlobTransfer(dir, "wallpaper.jpg", 1);
        duplicate.write(0, "YQ==");
        check(duplicate.finish().getName().equals("wallpaper (1).jpg"));
        check(Arrays.equals(data, Files.readAllBytes(saved.toPath())));

        BlobTransfer empty = new BlobTransfer(dir, "empty.txt", 0);
        check(empty.finish().length() == 0);
        BlobTransfer incomplete = new BlobTransfer(dir, "incomplete.jpg", 10);
        incomplete.write(0, "YQ==");
        rejected(incomplete::finish);
        incomplete.abort();
        check(!incomplete.temporaryFile().exists());
        check(!new File(dir, "incomplete.jpg").exists());

        BlobTransfer invalid = new BlobTransfer(dir, "invalid.bin", 1);
        rejected(() -> invalid.write(1, "YQ=="));
        rejected(() -> invalid.write(0, "!!!"));
        rejected(() -> invalid.write(0, ""));
        rejected(() -> invalid.write(0, "YWE="));
        rejected(() -> invalid.write(0, "A".repeat(BlobTransfer.CHUNK_BYTES / 3 * 4 + 4)));
        invalid.abort();
        check(!invalid.temporaryFile().exists());
        rejected(() -> new BlobTransfer(dir, "huge.bin", BlobTransfer.MAX_BYTES + 1));
        rejected(() -> new BlobTransfer(dir, "negative.bin", -1));

        BlobTransfer named = new BlobTransfer(dir, "../../outside.txt", 0);
        check(named.finish().getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()));
        check(BlobTransfer.safeName("..").equals("download.bin"));
        check(BlobTransfer.safeName("a\u0000b.jpg").equals("a_b.jpg"));
        check(BlobTransfer.safeName("CON.txt").equals("_CON.txt"));
        System.out.println("Blob file transfer checks passed: " + checks);
    }
}
