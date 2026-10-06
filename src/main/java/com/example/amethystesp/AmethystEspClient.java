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
import net.minecraft.world.Heightmap;
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
 * Geode finder based on LIGHT.
 * Grown amethyst glows. From far away the server hides the crystal blocks themselves, so the cells that hold the
 * crystal (light 5) read 0, but the air cells next to them still read light 4. So we find geodes by their shell
 * blocks, then count cells around the geode whose block light is exactly 4 (and not next to anything brighter than 5,
 * like a torch). More such cells = more grown crystals.
 *
 * Beam: BLUE = geode seen from far away, PURPLE = MORE than GLOW_CELL_THRESHOLD light-4 cells (or visible grown crystals).
 */
public class AmethystEspClient implements ClientModInitializer {
    // ---- Settings (edit these) ----
    private static final int GLOW_CELL_THRESHOLD = 36;  // PURPLE beam when MORE than this many light-4 cells (tune with the numbers in the debug line)
    private static final int SCAN_RADIUS = 3;           // how far around the geode centre to count light-4 cells
    private static final int GLOW_LIGHT = 4;            // light of the air next to a fully grown cluster (the cluster itself is 5 but is hidden)
    private static final int NATURAL_MAX_LIGHT = 5;     // anything brighter next to a cell means a torch/lamp, so skip the cell
    private static final int MIN_Y = -58;               // lowest Y to look for the geode shell
    private static final int MAX_Y = 50;                // highest Y to look for the geode shell (geodes can reach above Y=30)
    private static final int GEODE_MIN_BLOCKS = 30;     // amethyst/budding blocks needed to call an area a geode
    private static final int MIN_SHELL_PER_CHUNK = 3;   // chunk needs this many shell blocks before we measure light there

    private static final int THRESHOLD = 13;            // also PURPLE if more than this many grown crystals are visible
    private static final boolean COUNT_LARGE_BUDS = false;

    private static final int SCAN_INTERVAL_TICKS = 40;  // rescan every 2 seconds
    private static final boolean SHOW_PLAIN_GEODES = false;  // blue beam for every geode seen from far away (light data is missing from far)
    private static final boolean ALERT_CHAT = false;    // false = no chat message when a big geode is found (beam only)
    private static final boolean DEBUG = false;         // print what the scan sees in chat every few seconds
    private static final long DEBUG_INTERVAL_MS = 5000;
    private static final float HALF_WIDTH = 0.2f;

    private static final boolean SHOW_BEAM = false;          // false = turn off the vertical beam
    private static final boolean SHOW_STAR = true;          // star in the middle of the chunk plane
    private static final float STAR_OUTER = 5.5f;           // star size (blocks)
    private static final float STAR_INNER = 2.3f;
    private static final boolean STAR_RGB = true;           // true = star cycles through rainbow colours
    private static final long RGB_CYCLE_MS = 3000L;         // time for one full rainbow loop (smaller = faster)
    private static final int[] STAR_COLOR = {255, 230, 0, 230};     // fixed colour used when STAR_RGB = false
    private static final boolean SHOW_CHUNK_PLANE = true;   // flat square over the geode's chunk, at ground/water level
    private static final float PLANE_OFFSET = 1f;           // plane floats this many blocks above the highest ground/water point in the chunk
    private static final int[] PLANE_FILL = {0, 255, 255, 70};      // cyan, translucent
    private static final int[] PLANE_EDGE = {0, 255, 255, 230};     // cyan border

    private static final int[] PURPLE = {200, 80, 255, 150};
    private static final int[] BLUE = {255, 255, 255, 200};   // white beam for plain geodes

    private static final Predicate<BlockState> GROWN = s ->
        s.isOf(Blocks.AMETHYST_CLUSTER) || (COUNT_LARGE_BUDS && s.isOf(Blocks.LARGE_AMETHYST_BUD));
    private static final Predicate<BlockState> SHELL = s ->
        s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST);
    private static final Predicate<BlockState> ANY = GROWN.or(SHELL);

    private static final class Hit {
        final int lit, grown, x, y, z;
        final float planeY;
        final boolean strong;
        Hit(int lit, int grown, int x, int y, int z, boolean strong, float planeY) {
            this.lit = lit; this.grown = grown; this.x = x; this.y = y; this.z = z; this.strong = strong;
            this.planeY = planeY;
        }
    }

    private static final class ChunkData {
        final int cx, cz, shell, grown, lit, centreX, centreY, centreZ;
        ChunkData(int cx, int cz, int shell, int grown, int lit, BlockPos centre) {
            this.cx = cx; this.cz = cz; this.shell = shell; this.grown = grown; this.lit = lit;
            this.centreX = centre.getX(); this.centreY = centre.getY(); this.centreZ = centre.getZ();
        }
    }

    private Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> alertedStrong = new HashSet<>();
    private int tickCounter = 0;
    private long lastDebug = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.LAST.register(this::onRender);
    }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            hits = new HashMap<>();
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

        // 1) per chunk: find the geode shell, then count amethyst-lit air cells around its centre
        Map<Long, ChunkData> data = new HashMap<>();
        int[] hist = new int[16]; // debug: how many cells have each block-light level

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

                if (shell < MIN_SHELL_PER_CHUNK) continue;

                BlockPos centre = new BlockPos((int) (sumX / shell), (int) (sumY / shell), (int) (sumZ / shell));
                int lit = litCells(world, centre, hist);
                data.put(ChunkPos.toLong(cx, cz), new ChunkData(cx, cz, shell, grown, lit, centre));
            }
        }

        // 2) merge touching chunks (including diagonals) into one group = one geode
        Map<Long, Hit> found = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        int geodeGroups = 0, bestLit = 0;
        List<String> debugGroups = new ArrayList<>();

        for (Map.Entry<Long, ChunkData> entry : data.entrySet()) {
            if (!visited.add(entry.getKey())) continue;

            ArrayDeque<ChunkData> queue = new ArrayDeque<>();
            List<Long> members = new ArrayList<>();
            queue.add(entry.getValue());

            int shell = 0, grown = 0, litMax = 0, n = 0;
            long sx = 0, sy = 0, sz = 0;

            while (!queue.isEmpty()) {
                ChunkData c = queue.poll();
                members.add(ChunkPos.toLong(c.cx, c.cz));
                shell += c.shell;
                grown += c.grown;
                if (c.lit > litMax) litMax = c.lit;   // max, not sum: neighbouring chunks measure the same geode
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

            // 3) one beam per geode
            if (shell < GEODE_MIN_BLOCKS) continue;
            geodeGroups++;
            if (litMax > bestLit) bestLit = litMax;
            debugGroups.add("[X=" + (int) (sx / n) + " Z=" + (int) (sz / n) + " l4=" + litMax + " cum=" + grown + "]");

            boolean strong = litMax > GLOW_CELL_THRESHOLD || grown > THRESHOLD;
            if (!strong && !SHOW_PLAIN_GEODES) continue;

            int hx = (int) (sx / n), hy = (int) (sy / n), hz = (int) (sz / n);
            Hit h = new Hit(litMax, grown, hx, hy, hz, strong, surfaceY(world, hx, hz) + PLANE_OFFSET);
            found.put(entry.getKey(), h);

            if (strong) {
                boolean newStrong = true;
                for (long k : members) if (alertedStrong.contains(k)) { newStrong = false; break; }
                if (newStrong) {
                    alertedStrong.addAll(members);
                    if (ALERT_CHAT) mc.player.sendMessage(Text.literal("[AmethystESP] Nhieu amethyst lon tai X=" + h.x + " Y=" + h.y
                        + " Z=" + h.z + " (light 4: " + h.lit + ", thay " + h.grown + " cum)"), false);
                }
            }
        }
        hits = found;

        if (DEBUG) {
            long now = System.currentTimeMillis();
            int totalShell = 0;
            for (ChunkData c : data.values()) totalShell += c.shell;
            if (totalShell > 0 && now - lastDebug > DEBUG_INTERVAL_MS) {
                lastDebug = now;
                int high = 0;
                for (int i = 6; i < 16; i++) high += hist[i];
                StringBuilder groups = new StringBuilder();
                for (int i = 0; i < debugGroups.size() && i < 4; i++) groups.append(' ').append(debugGroups.get(i));
                mc.player.sendMessage(Text.literal("[AmethystESP] debug: vo=" + totalShell + " hang=" + geodeGroups
                    + " light4 cao nhat=" + bestLit + " (nguong " + GLOW_CELL_THRESHOLD + ") |" + groups
                    + " | o theo muc sang: 0=" + hist[0] + " 1=" + hist[1] + " 2=" + hist[2] + " 3=" + hist[3]
                    + " 4=" + hist[4] + " 5=" + hist[5]), false);
            }
        }
    }

    /** Highest ground/water surface (ignoring leaves) found in the chunk that contains (x, z). */
    private static int surfaceY(ClientWorld world, int x, int z) {
        int baseX = Math.floorDiv(x, 16) * 16;
        int baseZ = Math.floorDiv(z, 16) * 16;
        int best = world.getBottomY();
        for (int ox = 0; ox < 16; ox += 3) {
            for (int oz = 0; oz < 16; oz += 3) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, baseX + ox, baseZ + oz);
                if (y > best) best = y;
            }
        }
        return best;
    }

    /**
     * Counts cells around the geode centre whose block light is exactly GLOW_LIGHT (4),
     * skipping cells next to anything brighter (torches, lamps). Block type does not matter,
     * so crystals hidden by the server are still counted through the light around them.
     */
    private static int litCells(ClientWorld world, BlockPos centre, int[] hist) {
        int count = 0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);

                    int light = world.getLightLevel(LightType.BLOCK, cursor);
                    if (DEBUG) hist[Math.max(0, Math.min(light, 15))]++;
                    if (light != GLOW_LIGHT) continue;

                    boolean artificial = false;
                    for (Direction d : Direction.values()) {
                        neighbour.set(cursor.getX() + d.getOffsetX(), cursor.getY() + d.getOffsetY(), cursor.getZ() + d.getOffsetZ());
                        if (world.getLightLevel(LightType.BLOCK, neighbour) > NATURAL_MAX_LIGHT) { artificial = true; break; }
                    }
                    if (artificial) continue;

                    count++;
                }
            }
        }
        return count;
    }

    private void onRender(WorldRenderContext ctx) {
        Map<Long, Hit> current = hits;
        if (current.isEmpty()) return;

        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;

        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        float top = ctx.world().getBottomY() + ctx.world().getHeight();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (Hit h : new ArrayList<>(current.values())) {
            int[] c = h.strong ? PURPLE : BLUE;
            float x0 = (float) (h.x + 0.5 - HALF_WIDTH - cam.x);
            float x1 = (float) (h.x + 0.5 + HALF_WIDTH - cam.x);
            float z0 = (float) (h.z + 0.5 - HALF_WIDTH - cam.z);
            float z1 = (float) (h.z + 0.5 + HALF_WIDTH - cam.z);
            float y0 = (float) (h.y - cam.y);
            float y1 = (float) (top - cam.y);

            if (SHOW_BEAM) {
                quad(buf, m, c, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
                quad(buf, m, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                quad(buf, m, c, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                quad(buf, m, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                quad(buf, m, c, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
            }

            if (SHOW_CHUNK_PLANE) {
                int chX = Math.floorDiv(h.x, 16) * 16;
                int chZ = Math.floorDiv(h.z, 16) * 16;
                float px0 = (float) (chX - cam.x);
                float px1 = (float) (chX + 16 - cam.x);
                float pz0 = (float) (chZ - cam.z);
                float pz1 = (float) (chZ + 16 - cam.z);
                float py = (float) (h.planeY - cam.y);
                float t = 0.25f; // border thickness

                // filled square
                quad(buf, m, PLANE_FILL, px0, py, pz0, px1, py, pz0, px1, py, pz1, px0, py, pz1);
                // 4 border strips
                quad(buf, m, PLANE_EDGE, px0, py, pz0, px1, py, pz0, px1, py, pz0 + t, px0, py, pz0 + t);
                quad(buf, m, PLANE_EDGE, px0, py, pz1 - t, px1, py, pz1 - t, px1, py, pz1, px0, py, pz1);
                quad(buf, m, PLANE_EDGE, px0, py, pz0, px0 + t, py, pz0, px0 + t, py, pz1, px0, py, pz1);
                quad(buf, m, PLANE_EDGE, px1 - t, py, pz0, px1, py, pz0, px1, py, pz1, px1 - t, py, pz1);

                if (SHOW_STAR) {
                    float cx = (px0 + px1) / 2f;
                    float cz = (pz0 + pz1) / 2f;
                    float sy = py + 0.05f; // just above the plane so it doesn't flicker
                    float[] sxs = new float[10];
                    float[] szs = new float[10];
                    for (int i = 0; i < 10; i++) {
                        double ang = -Math.PI / 2 + i * Math.PI / 5;
                        float r = (i % 2 == 0) ? STAR_OUTER : STAR_INNER;
                        sxs[i] = cx + (float) Math.cos(ang) * r;
                        szs[i] = cz + (float) Math.sin(ang) * r;
                    }
                    int[] starCol = STAR_RGB ? rainbow() : STAR_COLOR;
                    for (int i = 0; i < 10; i++) {
                        int j = (i + 1) % 10;
                        quad(buf, m, starCol, cx, sy, cz, sxs[i], sy, szs[i], sxs[j], sy, szs[j], sxs[j], sy, szs[j]);
                    }
                }
            }
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Rainbow colour that changes over time (hue cycles through the full wheel). */
    private static int[] rainbow() {
        float hue = (System.currentTimeMillis() % RGB_CYCLE_MS) / (float) RGB_CYCLE_MS;
        float h6 = hue * 6f;
        int i = (int) h6;
        float f = h6 - i;
        int up = (int) (255 * f);
        int down = (int) (255 * (1f - f));
        switch (i % 6) {
            case 0:  return new int[]{255, up, 0, 235};
            case 1:  return new int[]{down, 255, 0, 235};
            case 2:  return new int[]{0, 255, up, 235};
            case 3:  return new int[]{0, down, 255, 235};
            case 4:  return new int[]{up, 0, 255, 235};
            default: return new int[]{255, 0, down, 2
