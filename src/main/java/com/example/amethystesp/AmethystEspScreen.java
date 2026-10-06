package com.example.amethystesp;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AmethystEspScreen extends Screen {
    private static final int COL_W = 150;
    private static final int GAP = 10;
    private static final int ROW = 24;

    private final Screen parent;

    public AmethystEspScreen(Screen parent) {
        super(Text.literal("AmethystESP"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int leftX = this.width / 2 - COL_W - GAP / 2;
        int rightX = this.width / 2 + GAP / 2;
        int top = 30;

        toggle(leftX, top, "Beam", () -> AmethystEspClient.SHOW_BEAM, v -> AmethystEspClient.SHOW_BEAM = v);
        toggle(leftX, top + ROW, "Chunk plane", () -> AmethystEspClient.SHOW_CHUNK_PLANE, v -> AmethystEspClient.SHOW_CHUNK_PLANE = v);
        toggle(leftX, top + ROW * 2, "Star", () -> AmethystEspClient.SHOW_STAR, v -> AmethystEspClient.SHOW_STAR = v);
        toggle(leftX, top + ROW * 3, "Star RGB", () -> AmethystEspClient.STAR_RGB, v -> AmethystEspClient.STAR_RGB = v);
        toggle(leftX, top + ROW * 4, "Chat alert", () -> AmethystEspClient.ALERT_CHAT, v -> AmethystEspClient.ALERT_CHAT = v);
        toggle(leftX, top + ROW * 5, "White beam (plain)", () -> AmethystEspClient.SHOW_PLAIN_GEODES, v -> AmethystEspClient.SHOW_PLAIN_GEODES = v);
        toggle(leftX, top + ROW * 6, "Debug", () -> AmethystEspClient.DEBUG, v -> AmethystEspClient.DEBUG = v);

        stepper(rightX, top, "Threshold", () -> String.valueOf(AmethystEspClient.GLOW_CELL_THRESHOLD),
            -4, 4, d -> AmethystEspClient.GLOW_CELL_THRESHOLD = Math.max(1, AmethystEspClient.GLOW_CELL_THRESHOLD + d.intValue()));
        stepper(rightX, top + ROW, "Star size", () -> String.format("%.1f", AmethystEspClient.STAR_OUTER),
            -0.5, 0.5, d -> AmethystEspClient.STAR_OUTER = Math.max(1f, AmethystEspClient.STAR_OUTER + d.floatValue()));
        stepper(rightX, top + ROW * 2, "Plane height", () -> String.format("%.1f", AmethystEspClient.PLANE_OFFSET),
            -1, 1, d -> AmethystEspClient.PLANE_OFFSET = AmethystEspClient.PLANE_OFFSET + d.floatValue());

        ButtonWidget colour = ButtonWidget.builder(colourLabel(), btn -> {
            AmethystEspClient.planeColorIndex = (AmethystEspClient.planeColorIndex + 1) % AmethystEspClient.PLANE_COLOR_NAMES.length;
            AmethystEspClient.applyPlaneColor();
            AmethystEspClient.save();
            btn.setMessage(colourLabel());
        }).dimensions(rightX, top + ROW * 3, COL_W, 20).build();
        this.addDrawableChild(colour);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> this.close())
            .dimensions(this.width / 2 - 50, top + ROW * 7 + 6, 100, 20).build());
    }

    private static Text colourLabel() {
        int i = Math.floorMod(AmethystEspClient.planeColorIndex, AmethystEspClient.PLANE_COLOR_NAMES.length);
        return Text.literal("Plane colour: " + AmethystEspClient.PLANE_COLOR_NAMES[i]);
    }

    private void toggle(int x, int y, String name, BooleanSupplier get, Consumer<Boolean> set) {
        ButtonWidget button = ButtonWidget.builder(label(name, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            AmethystEspClient.save();
            btn.setMessage(label(name, get.getAsBoolean()));
        }).dimensions(x, y, COL_W, 20).build();
        this.addDrawableChild(button);
    }

    private static Text label(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private void stepper(int x, int y, String name, Supplier<String> value, double minus, double plus, Consumer<Double> apply) {
        ButtonWidget middle = ButtonWidget.builder(Text.literal(name + ": " + value.get()), btn -> { })
            .dimensions(x + 22, y, COL_W - 44, 20).build();
        middle.active = false;

        ButtonWidget less = ButtonWidget.builder(Text.literal("-"), btn -> {
            apply.accept(minus);
            AmethystEspClient.save();
            middle.setMessage(Text.literal(name + ": " + value.get()));
        }).dimensions(x, y, 20, 20).build();

        ButtonWidget more = ButtonWidget.builder(Text.literal("+"), btn -> {
            apply.accept(plus);
            AmethystEspClient.save();
            middle.setMessage(Text.literal(name + ": " + value.get()));
        }).dimensions(x + COL_W - 20, y, 20, 20).build();

        this.addDrawableChild(middle);
        this.addDrawableChild(less);
        this.addDrawableChild(more);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void close() {
        AmethystEspClient.save();
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
