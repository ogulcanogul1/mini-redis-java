package io.github.ogulcanogul1.miniredis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void versionMatchesPom() {
        // Surefire passes the pom's version in as a system property (see pom.xml).
        assertThat(Main.version()).isEqualTo(System.getProperty("expected.version"));
    }

    @Test
    void versionIsFilteredNotLiteral() {
        assertThat(Main.version()).isNotBlank().doesNotContain("${");
    }
}
