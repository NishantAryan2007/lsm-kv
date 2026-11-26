package lsmkv;

public record WalRecord(long seq, ValueType type, byte[] key, byte[] value) {}