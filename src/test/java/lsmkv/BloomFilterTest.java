package lsmkv;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static BloomFilter filterWithKeys(int n) {
        BloomFilter f = new BloomFilter(n, 10);
        for (int i = 0; i < n; i++) {
            f.add(b("in-" + i));
        }
        return f;
    }

    @Test
    void neverSaysNoForAKeyThatWasAdded() {
        BloomFilter f = filterWithKeys(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(f.mightContain(b("in-" + i)), "false negative for in-" + i);
        }
    }

    @Test
    void falsePositiveRateIsAboutOnePercent() {
        BloomFilter f = filterWithKeys(10_000);
        int falsePositives = 0;
        for (int i = 0; i < 100_000; i++) {
            if (f.mightContain(b("out-" + i))) {
                falsePositives++;
            }
        }
        double rate = falsePositives / 100_000.0;
        assertTrue(rate < 0.02, "false positive rate too high: " + rate);
    }

    @Test
    void survivesSerialization() {
        BloomFilter f = filterWithKeys(5_000);
        BloomFilter g = BloomFilter.fromBytes(f.toBytes());
        for (int i = 0; i < 10_000; i++) {
            assertEquals(f.mightContain(b("in-" + i)), g.mightContain(b("in-" + i)));
            assertEquals(f.mightContain(b("out-" + i)), g.mightContain(b("out-" + i)));
        }
    }

    @Test
    void hashSpreadsAndHandlesEveryTailLength() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(BloomFilter.hash(b("key-" + i)));
        }
        assertEquals(10_000, seen.size());

        Set<Long> byLength = new HashSet<>();
        for (int len = 0; len <= 20; len++) {
            byLength.add(BloomFilter.hash(new byte[len]));
        }
        assertEquals(21, byLength.size());
    }
}