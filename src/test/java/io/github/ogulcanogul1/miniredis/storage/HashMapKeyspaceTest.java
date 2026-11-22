package io.github.ogulcanogul1.miniredis.storage;

class HashMapKeyspaceTest extends KeyspaceContractTest {

    @Override
    protected Keyspace newKeyspace() {
        return new HashMapKeyspace();
    }
}
