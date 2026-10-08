package com.example.amethystesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
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
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

public class AmethystEspClient implements ClientModInitializer {

    static int GLOW_CELL_THRESHOLD = 36;
    private static final int SCAN_RADIUS = 8;
    private static final int GLOW_LIGHT = 4;
    private static final int NATURAL_MAX_LIGHT = 5;
    private static final int MIN_Y = -58;
    private static final int MAX_Y = 50;
    private static final int GEODE_MIN_BLOCKS = 30;
    private static final int MIN_SHELL_PER_CHUNK = 3;

    private static final int THRESHOLD = 13;
    private static final boolean COUNT_LARGE_BUDS = false;

    private static final int SCAN_INTERVAL_TICKS = 40;
    static boolean SHOW_PLAIN_GEODES = false;
    static boolean ALERT_CHAT = false;
    static boolean DEBUG = false;
    private static final long DEBUG_INTERVAL_MS = 5000;
    private static final float HALF_WIDTH = 0.2f;

    static boolean SHOW_BEAM = true;
    static boolean SHOW_STAR = true;
    static float STAR_OUTER = 5.5f;
    static boolean STAR_RGB = true;
    private static final long RGB_CYCLE_MS = 3000L;
    private static final int[] STAR_COLOR = {255, 230, 0, 230};
    static boolean SHOW_CHUNK_PLANE = true;
    static float PLANE_OFFSET = 1f;
    static int[] PLANE_FILL = {0, 255, 255, 70};
    static int[] PLANE_EDGE = {0, 255, 255, 230};

    static int planeColorIndex = 0;
    static final String[] PLANE_COLOR_NAMES = {"Cyan", "Red", "Green", "Purple", "White", "Orange"};
    private static final int[][] PLANE_COLOR_RGB = {
        {0, 255, 255}, {255, 60, 60}, {60, 255, 60}, {190, 80, 255}, {255, 255, 255}, {255, 150, 0}
    };

    static void applyPlaneColor() {
        int[] c = PLANE_COLOR_RGB[Math.floorMod(planeColorIndex, PLANE_COLOR_RGB.length)];
        PLANE_FILL = new int[]{c[0], c[1], c[2], 70};
        PLANE_EDGE = new int[]{c[0], c[1], c[2], 230};
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("amethystesp.properties");
    }

    static void save() {
        Properties p = new Properties();
        p.setProperty("threshold", String.valueOf(GLOW_CELL_THRESHOLD));
        p.setProperty("plainGeodes", String.valueOf(SHOW_PLAIN_GEODES));
        p.setProperty("alertChat", String.valueOf(ALERT_CHAT));
        p.setProperty("debug", String.valueOf(DEBUG));
        p.setProperty("beam", String.valueOf(SHOW_BEAM));
        p.setProperty("star", String.valueOf(SHOW_STAR));
        p.setProperty("starSize", String.valueOf(STAR_OUTER));
        p.setProperty("starRgb", String.valueOf(STAR_RGB));
        p.setProperty("plane", String.valueOf(SHOW_CHUNK_PLANE));
        p.setProperty("planeOffset", String.valueOf(PLANE_OFFSET));
        p.setProperty("planeColor", String.valueOf(planeColorIndex));
        try (OutputStream out = Files.newOutputStream(configPath())) {
            p.store(out, "AmethystESP");
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        try {
            GLOW_CELL_THRESHOLD = Integer.parseInt(p.getProperty("threshold", String.valueOf(GLOW_CELL_THRESHOLD)));
            SHOW_PLAIN_GEODES = Boolean.parseBoolean(p.getProperty("plainGeodes", String.valueOf(SHOW_PLAIN_GEODES)));
            ALERT_CHAT = Boolean.parseBoolean(p.getProperty("alertChat", String.valueOf(ALERT_CHAT)));
            DEBUG = Boolean.parseBoolean(p.getProperty("debug", String.valueOf(DEBUG)));
            SHOW_BEAM = Boolean.parseBoolean(p.getProperty("beam", String.valueOf(SHOW_BEAM)));
            SHOW_STAR = Boolean.parseBoolean(p.getProperty("star", String.valueOf(SHOW_STAR)));
            STAR_OUTER = Float.parseFloat(p.getProperty("starSize", String.valueOf(STAR_OUTER)));
            STAR_RGB = Boolean.parseBoolean(p.getProperty("starRgb", String.valueOf(STAR_RGB)));
            SHOW_CHUNK_PLANE = Boolean.parseBoolean(p.getProperty("plane", String.valueOf(SHOW_CHUNK_PLANE)));
            PLANE_OFFSET = Float.parseFloat(p.getProperty("planeOffset", String.valueOf(PLANE_OFFSET)));
            planeColorIndex = Integer.parseInt(p.getProperty("planeColor", String.valueOf(planeColorIndex)));
        } catch (NumberFormatException ignored) {
        }
        applyPlaneColor();
    }

    private static KeyBinding menuKey;

    private static final int[] PURPLE = {200, 80, 255, 150};
    private static final int[] BLUE = {255, 255, 255, 200};

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
        load();
        menuKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.amethystesp.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.amethystesp"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("esp").executes(ctx -> {
                MinecraftClient client = ctx.getSource().getClient();
                client.send(() -> client.setScreen(new AmethystEspScreen(null)));
                return 1;
            })));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        WorldRenderEvents.LAST.register(this::onRender);
    }

    private void onTick(MinecraftClient mc) {
        while (menuKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new AmethystEspScreen(null));
        }
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

        Map<Long, ChunkData> data = new HashMap<>();
        int[] hist = new int[16];

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
                if (c.lit > litMax) litMax = c.lit;
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
        if (!SHOW_BEAM && !SHOW_CHUNK_PLANE && !SHOW_STAR) return;

        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;

        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        float top = ctx.world().getBottomY() + ctx.world().getHeight();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

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

            if (SHOW_CHUNK_PLANE || SHOW_STAR) {
                int chX = Math.floorDiv(h.x, 16) * 16;
                int chZ = Math.floorDiv(h.z, 16) * 16;
                float px0 = (float) (chX - cam.x);
                float px1 = (float) (chX + 16 - cam.x);
                float pz0 = (float) (chZ - cam.z);
                float pz1 = (float) (chZ + 16 - cam.z);
                float py = (float) (h.planeY - cam.y);
                float t = 0.25f;

                if (SHOW_CHUNK_PLANE) {
                    quad(buf, m, PLANE_FILL, px0, py, pz0, px1, py, pz0, px1, py, pz1, px0, py, pz1);
                    quad(buf, m, PLANE_EDGE, px0, py, pz0, px1, py, pz0, px1, py, pz0 + t, px0, py, pz0 + t);
                    quad(buf, m, PLANE_EDGE, px0, py, pz1 - t, px1, py, pz1 - t, px1, py, pz1, px0, py, pz1);
                    quad(buf, m, PLANE_EDGE, px0, py, pz0, px0 + t, py, pz0, px0 + t, py, pz1, px0, py, pz1);
                    quad(buf, m, PLANE_EDGE, px1 - t, py, pz0, px1, py, pz0, px1, py, pz1, px1 - t, py, pz1);
                }

                if (SHOW_STAR) {
                    float cx = (px0 + px1) / 2f;
                    float cz = (pz0 + pz1) / 2f;
                    float sy = py + 0.05f;
                    float[] sxs = new float[10];
                    float[] szs = new float[10];
                    for (int i = 0; i < 10; i++) {
                        double ang = -Math.PI / 2 + i * Math.PI / 5;
                        float r = (i % 2 == 0) ? STAR_OUTER : STAR_OUTER * 0.42f;
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
            default: return new int[]{255, 0, down, 235};
        }
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
