package com.eudistack.audit.infrastructure.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.eudistack.audit.domain.catalogue.CatalogueVersion;
import com.eudistack.audit.domain.catalogue.EventTypeSpec;
import com.eudistack.audit.domain.port.out.CatalogueResolverPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Catalogue snapshots shipped in the image (FR-19, AC-18): one YAML per published {@code catalog_version}
 * under {@code classpath:catalogue/}. A snapshot is immutable once published; a new version is a new file,
 * never an edit, so every event keeps being validated and read with the version it carries.
 */
public final class ClasspathCatalogue implements CatalogueResolverPort {

    private static final Comparator<String> SEMVER = Comparator.comparing(
            v -> Arrays.stream(v.split("\\.")).mapToInt(Integer::parseInt).toArray(), Arrays::compare);

    private final Map<String, CatalogueVersion> versions;
    private final CatalogueVersion latest;

    public ClasspathCatalogue(List<String> locations) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, CatalogueVersion> loaded = new TreeMap<>(SEMVER);
        for (String location : locations) {
            for (Resource resource : resources(resolver, location)) {
                CatalogueVersion version = parse(yaml, resource);
                CatalogueVersion previous = loaded.put(version.version(), version);
                if (previous != null) {
                    throw new IllegalStateException("catalog_version " + version.version() + " is published twice");
                }
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No audit event catalogue found in " + locations);
        }
        this.versions = Map.copyOf(loaded);
        this.latest = ((TreeMap<String, CatalogueVersion>) loaded).lastEntry().getValue();
    }

    @Override
    public Optional<CatalogueVersion> version(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    @Override
    public Collection<CatalogueVersion> all() {
        return versions.values();
    }

    @Override
    public CatalogueVersion latest() {
        return latest;
    }

    @Override
    public Optional<EventTypeSpec> latestSpecOf(String eventType) {
        return versions.values().stream()
                .sorted(Comparator.comparing(CatalogueVersion::version, SEMVER).reversed())
                .map(v -> v.types().get(eventType))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static Resource[] resources(PathMatchingResourcePatternResolver resolver, String location) {
        try {
            return resolver.getResources(location);
        } catch (IOException e) {
            throw new UncheckedIOException("Catalogue location not readable: " + location, e);
        }
    }

    private static CatalogueVersion parse(ObjectMapper yaml, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = yaml.readTree(in);
            return new CatalogueYamlParser().parse(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Catalogue not readable: " + resource, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Catalogue not valid: " + resource + ": " + e.getMessage(), e);
        }
    }
}
