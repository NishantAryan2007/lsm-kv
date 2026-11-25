package lsmkv;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BytesTest {
    private static int sign(byte[] a, byte[] b) {
        return Integer.signum(Bytes.compare(a, b));
    }

    @Test
    void equalArrays() {
        assertEquals(0, sign(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
    }

    @Test
    void bothEmpty() {
        assertEquals(0, sign(new byte[0], new byte[0]));
    }

    @Test
    void emptyIsSmallest() {
        assertEquals(-1, sign(new byte[0], new byte[]{0}));
        assertEquals(1, sign(new byte[]{0}, new byte[0]));
    }

    @Test
    void prefixIsSmaller() {
        assertEquals(-1, sign(new byte[]{1, 2}, new byte[]{1, 2, 3}));
    }

    @Test
    void differsAtLaterIndex() {
        assertEquals(-1, sign(new byte[]{1, 2, 3}, new byte[]{1, 2, 4}));
    }

    @Test
    void bytesAreCompared_asUnsigned() {
        assertEquals(1, sign(new byte[]{(byte) 0xFF}, new byte[]{(byte) 0x01}));
    }

    @Test
    void matchesJdkOnRandomInputs() {
        Random rnd = new Random(42);
        for (int i = 0; i < 100_000; i++) {
            byte[] a = randomBytes(rnd);
            byte[] b = randomBytes(rnd);
            assertEquals(
                Integer.signum(Arrays.compareUnsigned(a, b)),
                sign(a, b),
                "mismatch for " + Arrays.toString(a) + " vs " + Arrays.toString(b));
        }
    }

    private static byte[] randomBytes(Random rnd) {
        byte[] x = new byte[rnd.nextInt(5)]; // length 0 to 4, so equal and prefix cases happen often
        rnd.nextBytes(x);
        return x;
    }
}