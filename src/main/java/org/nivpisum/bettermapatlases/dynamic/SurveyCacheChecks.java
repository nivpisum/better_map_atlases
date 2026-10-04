/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/** Detached registered-block checks for content/topology cache separation. No world writes. */
public final class SurveyCacheChecks {
    private SurveyCacheChecks() {}
    public static Map<String, Object> run() {
        var dimension = new ResourceLocation("minecraft", "overworld");
        var biome = new ResourceLocation("minecraft", "plains");
        SurveyLayer page = new SurveyLayer(0, 0, 0, dimension);
        SurveySample stone = new SurveySample(0, 64, 0, 160, 0, Blocks.STONE.defaultBlockState(), biome, 0L,
            (byte) 1, SurveySample.Kind.FLOOR, true, true);
        page.observe(8256, stone);
        var floors = page.projectionFloors(1); long topology = page.topologyRevision();
        page.observe(8256, new SurveySample(0, 32, 0, 160, 0, Blocks.RED_WOOL.defaultBlockState(), biome, 0L,
            (byte) 1, SurveySample.Kind.FLOOR, true, true));
        if (page.topologyRevision() != topology || page.projectionFloors(1) != floors || page.sampleCount() != 2)
            throw new IllegalStateException("Appearance-only observation invalidated topology");
        var client = new SurveyLayer(page.metadata()); client.apply(page.fullSnapshot());
        long content = client.contentVersion(), clientTopology = client.topologyRevision();
        client.apply(page.fullSnapshot());
        if (client.contentVersion() != content || client.topologyRevision() != clientTopology)
            throw new IllegalStateException("Identical reset churned the client graph");
        page.observe(8256, new SurveySample(16, 64, 16, 160, 16, Blocks.STONE.defaultBlockState(), biome, 1L,
            (byte) 1, SurveySample.Kind.FLOOR, true, true));
        if (page.topologyRevision() == topology || page.projectionFloors(1) == floors)
            throw new IllegalStateException("Changed floor geometry retained an obsolete index");
        if (page.lookup(8257, 20) != null || page.lookup(8256, 64) != null)
            throw new IllegalStateException("Cache admitted unobserved knowledge");
        if (!SurveyLayer.load(page.save()).contentHash().equals(page.contentHash()))
            throw new IllegalStateException("Saved history changed during cache roundtrip");
        return Map.of("passed", true, "checks", 5, "samples", page.sampleCount(), "format", SurveyLayer.FORMAT_VERSION);
    }
}
