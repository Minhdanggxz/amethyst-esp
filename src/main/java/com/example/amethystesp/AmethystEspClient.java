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
 * Geode finder based on LIGHT.
 * A fully grown amethyst cluster gives block light 5. Even when the server hides the crystals from the client,
 * the light data is still sent. So we find geodes by their shell blocks, then count the cells around the geode
 * whose block light is exactly 5 (and not next to something brighter, like a torch). That is ~ the number of
 * grown clusters.
 *
 * Beam: PURPLE only, for geodes with MORE than THRESHOLD light-5 cells (or visible grown crystals).
 */
public class AmethystEspClient implements ClientModInitializer {
    // ---- Settings (edit these) ----
    private static final int THRESHOLD = 13;            // PURPLE beam when MORE than this many light-5 cells (grown crystals)
    private static final int SCAN_RADIUS = 8;           // how far around the geode centre to count light-5 cells
    private static final int CLUSTER_LIGHT = 5;         // block light of a fully grown amethyst cluster
    private static final int MIN_Y = -58;               // lowest Y to look for the geode shell
    private static final int MAX_Y = 50;                // highest Y to look for the geode shell (geodes can reach above Y=30)
    private static final int GEODE_MIN_BLOCKS = 30;     // amethyst/budding blocks needed to call an area a geode
    private static final int MIN_SHELL_PER_CHUNK = 3;   // chunk needs this many shell blocks before we measure light there

    private static final boolean COUNT_LARGE_BUDS = false;

    private static final int SCAN_INTERVAL_TICKS = 40;  // rescan every 2 seconds
    private static final boolean DEBUG = true;          // print what the scan sees in chat every few seconds
    private static final long DEBUG_INTERVAL_MS = 5000;
    private static final float HALF_WIDTH = 0.2f;

    private static final int[] PURPLE = {200, 80, 255, 150};

    private static final Predicate<BlockState> GROWN = s ->
        s.isOf(Blocks.AMETHYST_CLUSTER) || (COUNT_LARGE_BUDS && s.isOf(Blocks.LARGE_AMETHYST_BUD));
    private static final Predicate<BlockState> SHELL = s ->
        s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST);
    private static final Predicate<BlockState> ANY = GROWN.or(SHELL);

    private static final class Hit {
        final int lit, grown, x, y, z;
        final boolean strong;
        Hit(int lit, int grown, int x, int y, int z, boolean strong) {
            this.lit = lit; this.grown = grown; this.x = x; this.y = y; this.z = z; this.strong = strong;
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
                int lit = litCells(world, centre);
                data.put(ChunkPos.toLong(cx, cz), new ChunkData(cx, cz, shell, grown, lit, centre));
            }
        }

        // 2) merge touching chunks (including diagonals) into one group = one geode
        Map<Long, Hit> found = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        int geodeGroups = 0, bestLit = 0;

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

            boolean strong = litMax > THRESHOLD || grown > THRESHOLD;
            if (!strong) continue; // only keep geodes with lots of grown amethyst

            Hit h = new Hit(litMax, grown, (int) (sx / n), (int) (sy / n), (int) (sz / n), true);
            found.put(entry.getKey(), h);

            if (strong) {
                boolean newStrong = true;
                for (long k : members) if (alertedStrong.contains(k)) { newStrong = false; break; }
                if (newStrong) {
                    alertedStrong.addAll(members);
                    mc.player.sendMessage(Text.literal("[AmethystESP] Nhieu amethyst lon tai X=" + h.x + " Y=" + h.y
                        + " Z=" + h.z + " (light 5: " + h.lit + ", thay " + h.grown + " cum)"), false);
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
                mc.player.sendMessage(Text.literal("[AmethystESP] debug: vo=" + totalShell + " hang=" + geodeGroups
                    + " light5 cao nhat=" + bestLit + " (nguong " + THRESHOLD + ")"), false);
            }
        }
    }

    /**
     * Counts cells around the geode centre whose block light is exactly CLUSTER_LIGHT (5),
     * skipping cells next to anything brighter (torches, lamps). Block type does not matter,
     * so crystals hidden by the server are still counted through their light.
     */
    private static int litCells(ClientWorld world, BlockPos centre) {
        int count = 0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dy = -SCAN_RADIUS; dy <= SCAN_RADIUS; dy++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);

                    if (world.getLightLevel(LightType.BLOCK, cursor) != CLUSTER_LIGHT) continue;

                    boolean artificial = false;
                    for (Direction d : Direction.values()) {
                        neighbour.set(cursor.getX() + d.getOffsetX(), cursor.getY() + d.getOffsetY(), cursor.getZ() + d.getOffsetZ());
                        if (world.getLightLevel(LightType.BLOCK, neighbour) > CLUSTER_LIGHT) { artificial = true; break; }
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
            int[] c = PURPLE;
            float x0 = (float) (h.x + 0.5 - HALF_WIDTH - cam.x);
            float x1 = (float) (h.x + 0.5 + HALF_WIDTH - cam.x);
            float z0 = (float) (h.z + 0.5 - HALF_WIDTH - cam.z);
            float z1 = (float) (h.z + 0.5 + HALF_WIDTH - cam.z);
            float y0 = (float) (h.y - cam.y);
            float y1 = (float) (top - cam.y);

            quad(buf, m, c, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
            quad(buf, m, c, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(buf, m, c, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
            quad(buf, m, c, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            quad(buf, m, c, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder b, Matrix4f m, int[] c,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        b.vertex(m, ax, ay, az).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, bx, by, bz).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, cx, cy, cz).color(c[0], c[1], c[2], c[3]);
        b.vertex(m, dx, dy, dz).color(c[0], c[1], c[2], c[3]);
    }
    }
