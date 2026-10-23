package com.eudistack.audit.infrastructure.storage;

import com.eudistack.audit.domain.port.out.ColdStorageException;
import com.eudistack.audit.domain.port.out.ColdStoragePort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Cold storage on a local directory, for local development only ({@code audit.archive.storage=filesystem}).
 *
 * <p>It mimics the write-once contract (an existing object is never overwritten) and records the
 * retain-until date in a side file, but it offers no compliance lock: it must never be used outside a
 * developer machine. Production uses {@link ColdStorageS3}.
 */
public final class FileSystemColdStorage implements ColdStoragePort {

    private static final String RETENTION_SUFFIX = ".retain-until";

    private final Path root;

    public FileSystemColdStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content, String contentType, Instant retainUntil) {
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(resolve(key + RETENTION_SUFFIX), retainUntil.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (!file.toFile().setReadOnly()) {
                throw new ColdStorageException("Could not make the object read-only: " + key, null);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ColdStorageException("Object already exists: " + key, e);
        } catch (IOException e) {
            throw new ColdStorageException("Could not write " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path file = resolve(key);
        try {
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new ColdStorageException("Could not read " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path directory = resolve(prefix);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(k -> !k.endsWith(RETENTION_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ColdStorageException("Could not list " + prefix, e);
        }
    }

    @Override
    public Optional<Instant> lockedUntil(String key) {
        Path file = resolve(key + RETENTION_SUFFIX);
        try {
            return Files.exists(file) ? Optional.of(Instant.parse(Files.readString(file).trim())) : Optional.empty();
        } catch (IOException e) {
            throw new ColdStorageException("Could not read the retention of " + key, e);
        }
    }

    @Override
    public String reference(String key) {
        return root.resolve(key).toUri().toString();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Key escapes the storage root: " + key);
        }
        return path;
    }
}
