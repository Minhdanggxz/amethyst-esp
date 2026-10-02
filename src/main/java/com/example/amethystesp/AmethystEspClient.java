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
import net.minecraft.util.math.Vec3d;
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

public class AmethystEspClient implements ClientModInitializer {
    // ---- Settings (edit these) ----
    private static final int THRESHOLD = 13;               // an area (group of touching chunks) is marked when it has MORE than this many grown amethyst
    private static final boolean COUNT_LARGE_BUDS = false; // also count large buds as "grown"
    private static final int SCAN_INTERVAL_TICKS = 40;     // rescan every 2 seconds

    private static final float HALF_WIDTH = 0.2f;
    private static final int RED = 200, GREEN = 80, BLUE = 255, ALPHA = 150;

    private static final Predicate<BlockState> GROWN = s ->
        s.isOf(Blocks.AMETHYST_CLUSTER) || (COUNT_LARGE_BUDS && s.isOf(Blocks.LARGE_AMETHYST_BUD));

    private static final class Hit {
        final int count, x, y, z;
        Hit(int count, int x, int y, int z) { this.count = count; this.x = x; this.y = y; this.z = z; }
    }

    private static final class ChunkData {
        final int cx, cz, count, minY;
        final long sumX, sumZ;
        ChunkData(int cx, int cz, int count, long sumX, long sumZ, int minY) {
            this.cx = cx; this.cz = cz; this.count = count; this.sumX = sumX; this.sumZ = sumZ; this.minY = minY;
        }
    }

    private Map<Long, Hit> hits = new HashMap<>();
    private final Set<Long> alerted = new HashSet<>();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.LAST.register(this::onRender);
    }

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            hits = new HashMap<>();
            alerted.clear();
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

        // 1) count grown amethyst in every loaded chunk
        Map<Long, ChunkData> data = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkSection[] sections = chunk.getSectionArray();
                int count = 0;
                long sumX = 0, sumZ = 0;
                int minY = Integer.MAX_VALUE;

                for (int i = 0; i < sections.length; i++) {
                    ChunkSection section = sections[i];
                    if (section == null || section.isEmpty() || !section.hasAny(GROWN)) continue;
                    int baseY = bottomY + i * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (GROWN.test(section.getBlockState(x, y, z))) {
                                    count++;
                                    sumX += cx * 16L + x;
                                    sumZ += cz * 16L + z;
                                    if (baseY + y < minY) minY = baseY + y;
                                }
                            }
                        }
                    }
                }

                if (count > 0) {
                    data.put(ChunkPos.toLong(cx, cz), new ChunkData(cx, cz, count, sumX, sumZ, minY));
                }
            }
        }

        // 2) merge neighbouring chunks (including diagonals) into one group = one geode area
        Map<Long, Hit> found = new HashMap<>();
        Set<Long> visited = new HashSet<>();

        for (Map.Entry<Long, ChunkData> entry : data.entrySet()) {
            if (!visited.add(entry.getKey())) continue;

            ArrayDeque<ChunkData> queue = new ArrayDeque<>();
            List<Long> members = new ArrayList<>();
            queue.add(entry.getValue());

            int total = 0;
            long sx = 0, sz = 0;
            int minY = Integer.MAX_VALUE;

            while (!queue.isEmpty()) {
                ChunkData c = queue.poll();
                members.add(ChunkPos.toLong(c.cx, c.cz));
                total += c.count;
                sx += c.sumX;
                sz += c.sumZ;
                if (c.minY < minY) minY = c.minY;

                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oz == 0) continue;
                        long k = ChunkPos.toLong(c.cx + ox, c.cz + oz);
                        ChunkData n = data.get(k);
                        if (n != null && visited.add(k)) queue.add(n);
                    }
                }
            }

            // 3) one beam per group, only if the whole group has more than THRESHOLD
            if (total > THRESHOLD) {
                Hit h = new Hit(total, (int) (sx / total), minY, (int) (sz / total));
                found.put(entry.getKey(), h);

                boolean isNew = true;
                for (long k : members) if (alerted.contains(k)) { isNew = false; break; }
                if (isNew) {
                    alerted.addAll(members);
                    mc.player.sendMessage(Text.literal("[AmethystESP] " + h.count + " amethyst lon tai X=" + h.x
                        + " Y=" + h.y + " Z=" + h.z), false);
                }
            }
        }
        hits = found;
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
            float x0 = (float) (h.x + 0.5 - HALF_WIDTH - cam.x);
            float x1 = (float) (h.x + 0.5 + HALF_WIDTH - cam.x);
            float z0 = (float) (h.z + 0.5 - HALF_WIDTH - cam.z);
            float z1 = (float) (h.z + 0.5 + HALF_WIDTH - cam.z);
            float y0 = (float) (h.y - cam.y);
            float y1 = (float) (top - cam.y);

            quad(buf, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
            quad(buf, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(buf, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
            quad(buf, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            quad(buf, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        }

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder b, Matrix4f m,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        b.vertex(m, ax, ay, az).color(RED, GREEN, BLUE, ALPHA);
        b.vertex(m, bx, by, bz).color(RED, GREEN, BLUE, ALPHA);
        b.vertex(m, cx, cy, cz).color(RED, GREEN, BLUE, ALPHA);
        b.vertex(m, dx, dy, dz).color(RED, GREEN, BLUE, ALPHA);
    }
}
