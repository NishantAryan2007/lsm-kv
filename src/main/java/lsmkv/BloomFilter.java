package lsmkv;

import java.util.Arrays;

/** Bloom filter over byte[] keys. Serialized form: [bits...][numHashes:1]. */
public final class BloomFilter {
    private static final long M = 0xc6a4a7935bd1e995L;
    private static final int R = 47;

    private final byte[] bits;
    private final long numBits;
    private final int numHashes;

    public BloomFilter(int expectedKeys, int bitsPerKey) {
        if (bitsPerKey <= 0) {
            throw new IllegalArgumentException("bitsPerKey must be > 0");
        }
        long wanted = Math.max(64L, (long) expectedKeys * bitsPerKey);
        int bytes = (int) ((wanted + 7) / 8);
        this.bits = new byte[bytes];
        this.numBits = bytes * 8L;
        this.numHashes = Math.max(1, Math.min(30, (int) Math.round(bitsPerKey * 0.69)));
    }

    private BloomFilter(byte[] bits, int numHashes) {
        this.bits = bits;
        this.numBits = bits.length * 8L;
        this.numHashes = numHashes;
    }

    /** MurmurHash64A (Austin Appleby), little-endian blocks. */
    public static long hash(byte[] key) {
        int len = key.length;
        long h = 0x9747b28cL ^ (len * M);
        int blocks = len / 8;
        for (int i = 0; i < blocks; i++) {
            long k = 0;
            for (int j = 7; j >= 0; j--) {
                k = (k << 8) | (key[i * 8 + j] & 0xFFL);
            }
            k *= M;
            k ^= k >>> R;
            k *= M;
            h ^= k;
            h *= M;
        }
        int tail = blocks * 8;
        int rem = len & 7;
        if (rem != 0) {
            for (int i = rem - 1; i >= 0; i--) {
                h ^= (key[tail + i] & 0xFFL) << (8 * i);
            }
            h *= M;
        }
        h ^= h >>> R;
        h *= M;
        h ^= h >>> R;
        return h;
    }

    public void add(byte[] key) {
        addHash(hash(key));
    }

    public void addHash(long h) {
        long h1 = h & 0xFFFFFFFFL;
        long h2 = (h >>> 32) | 1L;
        for (int i = 0; i < numHashes; i++) {
            long bit = (h1 + i * h2) % numBits;
            bits[(int) (bit >>> 3)] |= (byte) (1 << (int) (bit & 7));
        }
    }

    public boolean mightContain(byte[] key) {
        return mightContainHash(hash(key));
    }

    public boolean mightContainHash(long h) {
        long h1 = h & 0xFFFFFFFFL;
        long h2 = (h >>> 32) | 1L;
        for (int i = 0; i < numHashes; i++) {
            long bit = (h1 + i * h2) % numBits;
            if ((bits[(int) (bit >>> 3)] & (1 << (int) (bit & 7))) == 0) {
                return false;
            }
        }
        return true;
    }

    public byte[] toBytes() {
        byte[] out = Arrays.copyOf(bits, bits.length + 1);
        out[bits.length] = (byte) numHashes;
        return out;
    }

    public static BloomFilter fromBytes(byte[] data) {
        if (data.length < 2) {
            throw new IllegalArgumentException("corrupt filter: too short");
        }
        int k = data[data.length - 1];
        if (k < 1 || k > 30) {
            throw new IllegalArgumentException("corrupt filter: bad hash count " + k);
        }
        return new BloomFilter(Arrays.copyOf(data, data.length - 1), k);
    }
}