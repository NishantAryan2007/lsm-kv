package lsmkv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The database's table of contents. Layout: [crc32c:4][len:4][version:4][nextFileNumber:8]
 * [walNumber:8][lastSeq:8][tableCount:4][tableNumber:8 ...], tables newest first.
 * Replaced atomically; never edited in place.
 */
public final class Manifest {
    private Manifest() {}

    static final String NAME = "MANIFEST";
    static final String TMP = "MANIFEST.tmp";
    private static final int VERSION = 1;
    private static final int MIN_PAYLOAD = 4 + 8 + 8 + 8 + 4;

    public record State(long nextFileNumber, long walNumber, long lastSeq, List<Long> tables) {}

    /** Returns null if the directory has no MANIFEST yet. Throws if it is corrupt. */
    public static State read(Path dir) throws IOException {
        Path p = dir.resolve(NAME);
        if (!Files.exists(p)) {
            return null;
        }
        byte[] data = Files.readAllBytes(p);
        if (data.length < 8) {
            throw new IOException("corrupt manifest: too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        int storedCrc = buf.getInt(0);
        int len = buf.getInt(4);
        if (len < MIN_PAYLOAD || len != data.length - 8) {
            throw new IOException("corrupt manifest: bad length");
        }
        CRC32C crc = new CRC32C();
        crc.update(data, 8, len);
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException("corrupt manifest: checksum mismatch");
        }
        int pos = 8;
        int version = buf.getInt(pos);
        pos += 4;
        if (version != VERSION) {
            throw new IOException("unsupported manifest version " + version);
        }
        long nextFileNumber = buf.getLong(pos);
        pos += 8;
        long walNumber = buf.getLong(pos);
        pos += 8;
        long lastSeq = buf.getLong(pos);
        pos += 8;
        int count = buf.getInt(pos);
        pos += 4;
        if (count < 0 || (long) count * 8 != data.length - pos) {
            throw new IOException("corrupt manifest: bad table count");
        }
        List<Long> tables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tables.add(buf.getLong(pos));
            pos += 8;
        }
        return new State(nextFileNumber, walNumber, lastSeq, tables);
    }

    public static void write(Path dir, State s) throws IOException {
        byte[] bytes = encode(s);
        Path tmp = dir.resolve(TMP);
        Path target = dir.resolve(NAME);
        try (FileChannel ch = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) {
                ch.write(b);
            }
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        fsyncDirectory(dir);
    }

    /** Makes renames/creates in dir durable. Directories cannot be opened for fsync on Windows. */
    static void fsyncDirectory(Path dir) throws IOException {
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    private static byte[] encode(State s) {
        int payload = MIN_PAYLOAD + 8 * s.tables().size();
        ByteBuffer b = ByteBuffer.allocate(8 + payload);
        b.position(8);
        b.putInt(VERSION);
        b.putLong(s.nextFileNumber());
        b.putLong(s.walNumber());
        b.putLong(s.lastSeq());
        b.putInt(s.tables().size());
        for (long t : s.tables()) {
            b.putLong(t);
        }
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 8, payload);
        b.putInt(0, (int) crc.getValue());
        b.putInt(4, payload);
        return b.array();
    }
}