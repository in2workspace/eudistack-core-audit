package com.eudistack.audit.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration of the audit service ({@code audit.*}). Every value can be injected as an environment
 * variable through relaxed binding ({@code AUDIT_SECURITY_JWKS_URI}, …).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "audit")
public class AuditProperties {

    private AuditRole role = AuditRole.ALL;
    private final Web web = new Web();
    private final Security security = new Security();
    private final Datasources datasource = new Datasources();
    private final DbRoles dbRoles = new DbRoles();
    private final Query query = new Query();
    private final Catalogue catalogue = new Catalogue();
    private final Archive archive = new Archive();
    private final Provisioner provisioner = new Provisioner();
    private final Recover recover = new Recover();

    @Getter
    @Setter
    public static class Web {
        /** Regular expressions of the hosts of the internal (write) endpoint. */
        private List<String> internalHosts = new ArrayList<>(List.of("audit\\.internal\\..+"));
        /** Header a trusted ingress may use to carry the tenant; empty to ignore it. */
        private String tenantHeader = "X-Tenant-Id";
        /** Environment suffixes stripped from the first host label ({@code sandbox-stg} → {@code sandbox}). */
        private List<String> environmentSuffixes = new ArrayList<>(List.of("-stg", "-dev", "-pre"));
        /** Largest accepted request body. */
        private int maxBodyBytes = 5 * 1024 * 1024;
    }

    @Getter
    @Setter
    public static class Security {
        /** JWKS of the Verifier, ideally its internal URL. */
        private String jwksUri;
        /** Inline JWKS document, for tests and local development only; takes precedence over jwksUri. */
        private String jwks;
        /**
         * Accepted {@code iss} values. {@code {tenant}} matches one host label, e.g.
         * {@code https://{tenant}.stg.eudistack.net/verifier}.
         */
        private List<String> trustedIssuers = new ArrayList<>();
        /**
         * Required {@code aud}. Empty: not enforced, until the Verifier emits an audience for this
         * service (PO-12). Set it as soon as it does.
         */
        private String audience;
        private Duration clockSkew = Duration.ofSeconds(60);
        private Duration jwksTimeout = Duration.ofSeconds(2);
    }

    @Getter
    @Setter
    public static class Datasources {
        private final Pool writer = new Pool("audit-writer", 20);
        private final Pool reader = new Pool("audit-reader", 20);
        private final Pool archiver = new Pool("audit-archiver", 4);
        private final Pool migrator = new Pool("audit-migrator", 2);
    }

    @Getter
    @Setter
    public static class Pool {
        private String url;
        private String username;
        private String password;
        private String poolName;
        private int maximumPoolSize;
        private Duration connectionTimeout = Duration.ofMillis(2000);

        public Pool() {
        }

        Pool(String poolName, int maximumPoolSize) {
            this.poolName = poolName;
            this.maximumPoolSize = maximumPoolSize;
        }
    }

    @Getter
    @Setter
    public static class DbRoles {
        private String writer = "audit_writer";
        private String reader = "audit_reader";
        private String archiver = "audit_archiver";
    }

    @Getter
    @Setter
    public static class Query {
        /** HMAC key of the listing cursor; the same on every reader replica. At least 32 bytes. */
        private String cursorSecret;
        private final RateLimits rateLimit = new RateLimits();
        /** Larger limits agreed with support for an organization, by organizationIdentifier. */
        private Map<String, RateLimits> rateLimitOverrides = new HashMap<>();
    }

    @Getter
    @Setter
    public static class RateLimits {
        private int system = 60;
        private int person = 30;
        private int systemSearch = 20;
        private int personSearch = 10;
    }

    @Getter
    @Setter
    public static class Catalogue {
        /** Where the catalogue snapshots are, one YAML per catalog_version. */
        private List<String> locations = new ArrayList<>(List.of("classpath*:catalogue/*.yaml"));
    }

    @Getter
    @Setter
    public static class Archive {
        private String cron = "0 0 2 * * *";
        /** Run once at start-up and exit (scheduled task) instead of the daily cron. */
        private boolean runOnce;
        /** {@code s3} or {@code filesystem} (local development only: no compliance lock). */
        private String storage = "s3";
        private String bucket;
        private String region = "eu-west-1";
        /** Endpoint override (LocalStack, MinIO); empty for AWS. */
        private String endpoint;
        private boolean pathStyle;
        private String filesystemRoot = "./build/cold-storage";
    }

    @Getter
    @Setter
    public static class Provisioner {
        /** Tenants to provision, in addition to {@code platform}. */
        private List<String> tenants = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Recover {
        /** Run a recovery (AC-22) and exit. Archiver role only. */
        private boolean enabled;
        /** Access token of the platform administrator that requests the recovery. */
        private String token;
        private String tenant;
        private String from;
        private String to;
        private String output = "./recovered-events.json";
    }
}
