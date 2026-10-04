/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DependencyPolicyTest {
    private static UnmodifiableConfig metadata() throws Exception {
        return new TomlParser().parse(new StringReader(Files.readString(
            Path.of("src/main/resources/META-INF/mods.toml"))));
    }

    @Test void installationRequiresOnlyForgeAndMapAtlasesWithoutVersionBounds() throws Exception {
        List<UnmodifiableConfig> dependencies = metadata().get("dependencies.better_map_atlases");
        assertEquals(Set.of("forge", "map_atlases"), dependencies.stream()
            .map(d -> d.<String>get("modId")).collect(Collectors.toSet()));
        for (UnmodifiableConfig dependency : dependencies) {
            assertEquals(Boolean.TRUE, dependency.get("mandatory"));
            VersionRange range = VersionRange.createFromVersionSpec(dependency.get("versionRange"));
            for (String version : List.of("47.4.22", "47.4.23", "48.0.0", "1.20-6.0.19", "1.20-6.0.21", "0.0.0-beta", "future"))
                assertTrue(range.containsVersion(new DefaultArtifactVersion(version)), version);
            assertEquals("BOTH", dependency.get("side"));
        }
    }

    @Test void loaderRangeAllowsOlderCurrentAndFutureForgeMajors() throws Exception {
        VersionRange loader = VersionRange.createFromVersionSpec(metadata().get("loaderVersion"));
        for (String version : List.of("0", "46", "47", "48", "100", "0.0.0-beta", "future"))
            assertTrue(loader.containsVersion(new DefaultArtifactVersion(version)), version);
    }
}
