package com.eudistack.audit.infrastructure.storage;

import com.eudistack.audit.domain.port.out.ColdStorageException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ColdStorageS3} against an S3 emulator with Object Lock (AC-21, AD-7): what the archiver relies on
 * from the real bucket — compliance lock with its retain-until date, no rewrite of an existing key, and no
 * deletion of a locked version — instead of the in-memory double of {@code ArchiveEventsWorkflowIT}.
 */
class ColdStorageS3IT {

    private static final String BUCKET = "audit-archive";
    private static final LocalStackContainer S3 = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3")).withServices(LocalStackContainer.Service.S3);
    private static S3Client client;
    private static ColdStorageS3 storage;

    @BeforeAll
    static void start() {
        S3.start();
        client = S3Client.builder()
                .endpointOverride(S3.getEndpoint())
                .region(Region.of(S3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(S3.getAccessKey(), S3.getSecretKey())))
                .forcePathStyle(true)
                .build();
        client.createBucket(b -> b.bucket(BUCKET).objectLockEnabledForBucket(true));
        storage = new ColdStorageS3(client, BUCKET);
    }

    @AfterAll
    static void stop() {
        client.close();
        S3.stop();
    }

    private static String key() {
        return "red-logistica/2026/01/01/" + UUID.randomUUID() + ".jsonl.gz";
    }

    private static Instant retainUntil() {
        return Instant.now().plus(7 * 366, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void put_storesTheBytes_lockedInComplianceUntilTheRetainDate() {
        String key = key();
        byte[] content = "{\"event\":1}\n".getBytes(StandardCharsets.UTF_8);
        Instant retainUntil = retainUntil();

        storage.put(key, content, "application/gzip", retainUntil);

        assertThat(storage.get(key)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(content));
        assertThat(storage.lockedUntil(key)).contains(retainUntil);
        assertThat(storage.reference(key)).isEqualTo("s3://" + BUCKET + "/" + key);
    }

    @Test
    void ac21_anExistingKeyIsNeverRewritten() {
        String key = key();
        storage.put(key, "first".getBytes(StandardCharsets.UTF_8), "text/plain", retainUntil());

        assertThatThrownBy(() -> storage.put(key, "second".getBytes(StandardCharsets.UTF_8), "text/plain",
                retainUntil())).isInstanceOf(ColdStorageException.class)
                .cause().isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(412));
        assertThat(storage.get(key)).hasValueSatisfying(bytes ->
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("first"));
    }

    @Test
    void ac21_aLockedVersionCannotBeDeleted() {
        String key = key();
        storage.put(key, "locked".getBytes(StandardCharsets.UTF_8), "text/plain", retainUntil());
        String version = client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(BUCKET).prefix(key)
                .build()).versions().getFirst().versionId();

        assertThatThrownBy(() -> client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key)
                .versionId(version).build())).isInstanceOfSatisfying(S3Exception.class,
                        e -> assertThat(e.statusCode()).isEqualTo(403));
        assertThat(storage.get(key)).isPresent();
    }

    @Test
    void list_returnsTheKeysOfThePrefixSorted() {
        String prefix = "sandbox/2026/02/" + UUID.randomUUID() + "/";
        storage.put(prefix + "b.json", new byte[] {1}, "application/json", retainUntil());
        storage.put(prefix + "a.json", new byte[] {2}, "application/json", retainUntil());

        assertThat(storage.list(prefix)).containsExactly(prefix + "a.json", prefix + "b.json");
    }

    @Test
    void aMissingObject_isEmpty_notAnError() {
        String key = key();

        assertThat(storage.get(key)).isEmpty();
        assertThat(storage.lockedUntil(key)).isEmpty();
    }
}
