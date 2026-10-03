package com.example.amethystesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Geode finder, same idea as the Glazed AmethystESP:
 *  1. find the geode "shell" (amethyst block / budding amethyst) in each chunk and take its centre,
 *  2. count unlit empty air spots around that centre (a geode that nobody lit up),
 *  3. chunks with enough spots are marked; touching chunks are merged into ONE beam.
 * Beam is BLUE for a geode, PURPLE when it also has more than THRESHOLD fully grown crystals in sight.
 */
public class AmethystEspClient implements ClientModInitializer {
    // ---- Settings (edit these) ----
    private static final int SPOTS_THRESHOLD = 12;   // dark spots a chunk needs to be marked as a geode
    private static final int SCAN_RADIUS = 8;        // how far around the geode centre to look for dark spots
    private static final int MIN_Y = -58;            // lowest Y to scan for the geode shell
    private static final int MAX_Y = 30;             // highest Y to scan for the geode shell
    private static final int NEIGHBOUR_LIGHT = 4;    // a spot next to light above this is ignored
    private static final int MAX_SPOTS_PER_CHUNK = 9000;

    private static final int THRESHOLD = 13;         // purple beam: MORE than this many grown crystals in the area
    private static final boolean COUNT_LARGE_BUDS = false;

    private static final int SCAN_INTERVAL_TICKS = 40;  // rescan every 2 seconds
    private static final float HALF_WIDTH = 0.2f;

    private static final int[] PURPLE = {200, 80, 255, 150};
    private static final int[] BLUE = {60, 200, 255, 110};

    private static final Predicate<BlockState> GROWN = s ->
        s.isOf(Blocks.AMETHYST_CLUSTER) || (COUNT_LARGE_BUDS && s.isOf(Blocks.LARGE_AMETHYST_BUD));
    private static final Predicate<BlockState> SHELL = s ->
        s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST);
    private static final Predicate<BlockState> ANY = GROWN.or(SHELL);

    private static final class Hit {
        final int spots, grown, x, y, z;
        final boolean strong;
        Hit(int spots, int grown, int x, int y, int z, boolean strong) {
            this.spots = spots; this.grown = grown; this.x = x; this.y = y; this.z = z; this.strong = strong;
        }
    }

    private static final class ChunkData {
        final int cx, cz, spots, grown, centreX, centreY, centreZ;
        ChunkData(int cx, int cz, int spots, int grown, BlockPos centre) {
            this.cx = cx; this.cz = cz; this.spots = spots; this.grown = grown;
            this.centreX = centre.getX(); this.centreY = centre.getY(); this.centreZ = centre.getZ();
        }
    }

    private Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> alertedGeode = new HashSet<>();
    private final Set<Long> alertedStrong = new HashSet<>();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.LAST.register(this::onRender);
    }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            hits = new HashMap<>();
            alertedGeode.clear();
            alertedStrong.clear();
            tickCounter = 0;
            return;
        }
        if (tickCounter-- > 0) return;
        tickCounter = SCAN_INTERVAL_TICKS;
        scan(mc);
    }

    private void scan(MinecraftClient mc) {
        ClientWorld world = mc.world;
        int radius = mc.options.getClampedViewDistance();
        ChunkPos center = mc.player.getChunkPos();
        int bottomY = world.getBottomY();

        // 1) per chunk: find the geode centre, count dark spots around it
        Map<Long, ChunkData> data = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkSection[] sections = chunk.getSectionArray();
                int grown = 0, shell = 0;
                long sumX = 0, sumY = 0, sumZ = 0;

                for (int i = 0; i < sections.length; i++) {
                    ChunkSection section = sections[i];
                    int baseY = bottomY + i * 16;
                    if (baseY + 15 < MIN_Y || baseY > MAX_Y) continue;
                    if (section == null || section.isEmpty() || !section.hasAny(ANY)) continue;

                    for (int y = 0; y < 16; y++) {
                        int worldY = baseY + y;
                        if (worldY < MIN_Y || worldY > MAX_Y) continue;
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (GROWN.test(state)) {
                                    grown++;
                                } else if (SHELL.test(state)) {
                                    shell++;
                                    sumX += cx * 16L + x;
                                    sumY += worldY;
                                    sumZ += cz * 16L + z;
                                }
                            }
                        }
                    }
                }

                if (shell == 0) continue;

                BlockPos centre = new BlockPos((int) (sumX / shell), (int) (sumY / shell), (int) (sumZ / shell));
                int spots = darkSpots(world, centre);
                if (spots >= SPOTS_THRESHOLD) {
                    data.put(ChunkPos.toLong(cx, cz), new ChunkData(cx, cz, spots, grown, centre));
                }
            }
        }

        // 2) merge touching marked chunks (including diagonals) into one group = one geode area
        Map<Long, Hit> found = new HashMap<>();
        Set<Long> visited = new HashSet<>();

        for (Map.Entry<Long, ChunkData> entry : data.entrySet()) {
            if (!visited.add(entry.getKey())) continue;

            ArrayDeque<ChunkData> queue = new ArrayDeque<>();
            List<Long> members = new ArrayList<>();
            queue.add(entry.getValue());

            int spots = 0, grown = 0, n = 0;
            long sx = 0, sy = 0, sz = 0;

            while (!queue.isEmpty()) {
                ChunkData c = queue.poll();
                members.add(ChunkPos.toLong(c.cx, c.cz));
                spots += c.spots;
                grown += c.grown;
                sx += c.centreX;
                sy += c.centreY;
                sz += c.centreZ;
                n++;

                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oz == 0) continue;
                        long k = ChunkPos.toLong(c.cx + ox, c.cz + oz);
                        ChunkData neighbour = data.get(k);
                        if (neighbour != null && visited.add(k)) queue.add(neighbour);
                    }
                }
            }

            // 3) one beam per group
            boolean strong = grown > THRESHOLD;
            Hit h = new Hit(spots, grown, (int) (sx / n), (int) (sy / n), (int) (sz / n), strong);
            found.put(entry.getKey(), h);

            boolean newGeode = true;
            for (long k : members) if (alertedGeode.contains(k)) { newGeode = false; break; }
            if (newGeode) {
                alertedGeode.addAll(members);
                mc.player.sendMessage(Text.literal("[AmethystESP] Hang geode: " + h.spots + " cho toi tai X=" + h.x
                    + " Y=" + h.y + " Z=" + h.z), false);
            }

            if (strong) {
                boolean newStrong = true;
                for (long k : members) if (alertedStrong.contains(k)) { newStrong = false; break; }
                if (newStrong) {
                    
