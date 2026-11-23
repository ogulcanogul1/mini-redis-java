package io.github.ogulcanogul1.miniredis.storage;

class DictKeyspaceTest extends KeyspaceContractTest {

    @Override
    protected Keyspace newKeyspace() {
        return new DictKeyspace();
    }
}
