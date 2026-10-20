package com.eudistack.audit.support;

import com.eudistack.audit.infrastructure.catalogue.ClasspathCatalogue;

import java.util.List;

/**
 * The published catalogue (2.2.0) plus the 2.0.0 and 2.1.0 test fixtures.
 */
public final class TestCatalogue {

    public static final List<String> LOCATIONS = List.of("classpath*:catalogue/*.yaml",
            "classpath*:catalogue-test/*.yaml");

    private static final ClasspathCatalogue WITH_FIXTURES = new ClasspathCatalogue(LOCATIONS);
    private static final ClasspathCatalogue PUBLISHED = new ClasspathCatalogue(List.of("classpath*:catalogue/*.yaml"));

    private TestCatalogue() {
    }

    public static ClasspathCatalogue withFixtures() {
        return WITH_FIXTURES;
    }

    public static ClasspathCatalogue published() {
        return PUBLISHED;
    }
}
