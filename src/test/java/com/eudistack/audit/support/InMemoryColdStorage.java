package com.eudistack.audit.support;

import com.eudistack.audit.domain.port.out.ColdStoragePort;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Cold storage with the guarantees of S3 Object Lock in compliance mode: an object is written once,
 * and neither overwritten nor deleted before its retain-until date (AC-21). Can be made unavailable
 * to simulate a failed copy (AC-20).
 */
public final class InMemoryColdStorage implements ColdStoragePort {

    private record StoredObject(byte[] content, Instant retainUntil) {
    }

    private final Map<String, StoredObject> objects = new TreeMap<>();
    private final Clock clock;
    private boolean unavailable;
    private boolean corruptReads;
    private String failNextPutWithSuffix;

    public InMemoryColdStorage(Clock clock) {
        this.clock = clock;
    }

    public void unavailable(boolean value) {
        this.unavailable = value;
    }

    public void corruptReads(boolean value) {
        this.corruptReads = value;
    }

    /**
     * The next write of a key ending with {@code suffix} fails, as when the process dies or the storage
     * drops in the middle of an archiving.
     */
    public void failNextPutEndingWith(String suffix) {
        this.failNextPutWithSuffix = suffix;
    }

    @Override
    public synchronized void put(String key, byte[] content, String contentType, Instant retainUntil) {
        check();
        if (failNextPutWithSuffix != null && key.endsWith(failNextPutWithSuffix)) {
            failNextPutWithSuffix = null;
            throw new ColdStorageException("Write interrupted: " + key, null);
        }
        if (objects.containsKey(key)) {
            throw new ColdStorageException("Object exists and is locked: " + key, null);
        }
        objects.put(key, new StoredObject(content.clone(), retainUntil));
    }

    @Override
    public synchronized Optional<byte[]> get(String key) {
        check();
        StoredObject object = objects.get(key);
        if (object == null) {
            return Optional.empty();
        }
        byte[] content = object.content().clone();
        if (corruptReads && content.length > 0) {
            content[content.length - 1] ^= 0x01;
        }
        return Optional.of(content);
    }

    @Override
    public synchronized List<String> list(String prefix) {
        check();
        return objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
    }

    @Override
    public synchronized Optional<Instant> lockedUntil(String key) {
        check();
        return Optional.ofNullable(objects.get(key)).map(StoredObject::retainUntil);
    }

    @Override
    public String reference(String key) {
        return "s3://test-bucket/" + key;
    }

    /**
     * What an administrator with full privileges can try: refused while the lock holds (AC-21).
     */
    public synchronized void delete(String key) {
        StoredObject object = objects.get(key);
        if (object != null && clock.instant().isBefore(object.retainUntil())) {
            throw new ColdStorageException("Object is locked in compliance mode until " + object.retainUntil(), null);
        }
        objects.remove(key);
    }

    /**
     * Bucket lifecycle rule: removes the objects whose retention has expired.
     */
    public synchronized void expire() {
        objects.entrySet().removeIf(e -> !clock.instant().isBefore(e.getValue().retainUntil()));
    }

    public synchronized List<String> keys() {
        return List.copyOf(objects.keySet());
    }

    private void check() {
        if (unavailable) {
            throw new ColdStorageException("Cold storage unavailable", null);
        }
    }
}
