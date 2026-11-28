package lsmkv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestTest {
    @TempDir
    Path dir;

    @Test
    void missingManifestReadsAsNull() throws IOException {
        assertNull(Manifest.read(dir));
    }

    @Test
    void roundTrip() throws IOException {
        Manifest.State s = new Manifest.State(42, 7, 1234, List.of(5L, 3L, 1L));
        Manifest.write(dir, s);
        assertEquals(s, Manifest.read(dir));
        assertFalse(Files.exists(dir.resolve(Manifest.TMP)), "temp file must be renamed away");
    }

    @Test
    void rewriteReplacesTheOldState() throws IOException {
        Manifest.write(dir, new Manifest.State(2, 1, 0, List.of()));
        Manifest.State newer = new Manifest.State(9, 8, 77, List.of(6L, 4L));
        Manifest.write(dir, newer);
        assertEquals(newer, Manifest.read(dir));
    }

    @Test
    void flippedBitIsDetected() throws IOException {
        Manifest.write(dir, new Manifest.State(42, 7, 1234, List.of(5L)));
        Path p = dir.resolve(Manifest.NAME);
        byte[] data = Files.readAllBytes(p);
        data[12] ^= 1;
        Files.write(p, data);
        assertThrows(IOException.class, () -> Manifest.read(dir));
    }
}