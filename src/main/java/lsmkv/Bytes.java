package lsmkv;

public final class Bytes {
    private Bytes() {}

    public static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        // all shared positions were equal: the shorter array is smaller
        return Integer.compare(a.length, b.length);
    }
}