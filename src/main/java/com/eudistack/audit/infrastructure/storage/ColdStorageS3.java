package com.eudistack.audit.infrastructure.storage;

import com.eudistack.audit.domain.port.out.ColdStoragePort;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cold storage on S3 with Object Lock in compliance mode and SSE-KMS (AD-7, AD-9).
 *
 * <p>Every object is written with {@code x-amz-object-lock-mode: COMPLIANCE} and its retain-until date,
 * and only if it does not exist yet ({@code If-None-Match: *}): a locked object is never rewritten. The
 * application principal has no {@code s3:DeleteObject}; expiry after seven years is the bucket lifecycle
 * rule (IaC). Encryption is the bucket default (SSE-KMS).
 */
public final class ColdStorageS3 implements ColdStoragePort {

    private final S3Client s3;
    private final String bucket;

    public ColdStorageS3(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType, Instant retainUntil) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .ifNoneMatch("*")
                            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                            .objectLockMode(ObjectLockMode.COMPLIANCE)
                            .objectLockRetainUntilDate(retainUntil)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new ColdStorageException("Could not write " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new ColdStorageException("Could not read " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        try {
            return s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                    .contents().stream().map(S3Object::key).sorted().toList();
        } catch (SdkException e) {
            throw new ColdStorageException("Could not list " + prefix, e);
        }
    }

    @Override
    public Optional<Instant> lockedUntil(String key) {
        try {
            ObjectLockRetention retention = s3.getObjectRetention(GetObjectRetentionRequest.builder()
                    .bucket(bucket).key(key).build()).retention();
            if (retention == null || retention.mode() != ObjectLockRetentionMode.COMPLIANCE) {
                return Optional.empty();
            }
            return Optional.ofNullable(retention.retainUntilDate());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new ColdStorageException("Could not read the retention of " + key, e);
        } catch (SdkException e) {
            throw new ColdStorageException("Could not read the retention of " + key, e);
        }
    }

    @Override
    public String reference(String key) {
        return "s3://" + bucket + "/" + key;
    }
}
