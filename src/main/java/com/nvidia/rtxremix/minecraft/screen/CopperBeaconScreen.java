/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.screen;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconAnimationMode;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconAxisState;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Minecraft-style per-block controls for the Copper Beacon. */
public final class CopperBeaconScreen extends HandledScreen<CopperBeaconScreenHandler> {

    private static final int BACKGROUND_HEIGHT = 230;
    private static final int OUTER_MARGIN = 8;
    private static final int PANEL_GAP = 8;
    private static final int PANEL_WIDTH =
            (CopperBeaconScreenHandler.WIDTH - OUTER_MARGIN * 2 - PANEL_GAP) / 2;
    private static final int CONTROL_HEIGHT = 16;
    private static final int RANGE_SLIDER_GAP = 6;
    private static final int RANGE_SLIDER_WIDTH =
            (PANEL_WIDTH - RANGE_SLIDER_GAP) / 2;
    private static final Identifier SLOT_TEXTURE = Identifier.ofVanilla("container/slot");

    private final Map<CopperBeaconUpdatePayload.Field, Float> pending =
            new EnumMap<>(CopperBeaconUpdatePayload.Field.class);

    public CopperBeaconScreen(
            CopperBeaconScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = CopperBeaconScreenHandler.WIDTH;
        backgroundHeight = BACKGROUND_HEIGHT;
        playerInventoryTitleX = CopperBeaconScreenHandler.PLAYER_INVENTORY_X;
        playerInventoryTitleY = CopperBeaconScreenHandler.PLAYER_INVENTORY_Y - 12;
        titleX = 8;
        titleY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // A side-mounted panel leaves the crosshair area unobstructed so the
        // articulated beacon remains visible while its controls are adjusted.
        if (width >= backgroundWidth + 16) {
            x = 8;
        }
        buildControls();
    }

    private void buildControls() {
        CopperBeaconBlockEntity beacon = handler.getBeacon();
        if (beacon == null) {
            return;
        }

        addSlider(
                x + 56,
                y + 17,
                94,
                "container.rtx-remix-renderer.copper_beacon.saturation",
                beacon.getSaturation(),
                value -> value / CopperBeaconBlockEntity.MAX_SATURATION,
                value -> value * CopperBeaconBlockEntity.MAX_SATURATION,
                value -> Text.literal(String.format(Locale.ROOT, "%.0f%%", value * 100.0)),
                CopperBeaconUpdatePayload.Field.SATURATION);

        addDrawableChild(CheckboxWidget.builder(
                        Text.translatable(
                                "container.rtx-remix-renderer.copper_beacon.redstone"),
                        textRenderer)
                .pos(x + 158, y + 17)
                .checked(beacon.isRedstoneControlled())
                .tooltip(Tooltip.of(Text.translatable(
                        "container.rtx-remix-renderer.copper_beacon.redstone.tip")))
                .callback((checkbox, checked) -> queue(
                        CopperBeaconUpdatePayload.Field.REDSTONE_CONTROLLED,
                        checked ? 1.0f : 0.0f))
                .build());

        addSlider(
                x + OUTER_MARGIN,
                y + 36,
                118,
                "container.rtx-remix-renderer.copper_beacon.intensity",
                beacon.getIntensityMultiplier(),
                CopperBeaconScreen::intensityToSlider,
                CopperBeaconScreen::sliderToIntensity,
                value -> value == 0.0
                        ? Text.translatable("options.off")
                        : Text.literal(String.format(Locale.ROOT, "%.2f\u00d7", value)),
                CopperBeaconUpdatePayload.Field.INTENSITY);
        addSlider(
                x + 134,
                y + 36,
                118,
                "container.rtx-remix-renderer.copper_beacon.cone",
                beacon.getConeAngle(),
                value -> value / CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> value * CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> Text.literal(String.format(Locale.ROOT, "%.1f\u00b0", value)),
                CopperBeaconUpdatePayload.Field.CONE_ANGLE);

        addAxisControls(
                x + OUTER_MARGIN,
                y + 54,
                beacon.getYaw(),
                true);
        addAxisControls(
                x + OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                y + 54,
                beacon.getPitch(),
                false);
    }

    private void addAxisControls(
            int panelX,
            int panelY,
            CopperBeaconAxisState state,
            boolean yaw) {
        addSlider(
                panelX,
                panelY + 11,
                PANEL_WIDTH,
                "container.rtx-remix-renderer.copper_beacon.position",
                state.position(),
                value -> (value - CopperBeaconAxisState.MIN_ANGLE)
                        / (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE),
                value -> CopperBeaconAxisState.MIN_ANGLE
                        + value * (CopperBeaconAxisState.MAX_ANGLE
                                - CopperBeaconAxisState.MIN_ANGLE),
                value -> Text.literal(String.format(Locale.ROOT, "%.1f\u00b0", value)),
                yaw
                        ? CopperBeaconUpdatePayload.Field.YAW_POSITION
                        : CopperBeaconUpdatePayload.Field.PITCH_POSITION);

        addDrawableChild(CyclingButtonWidget
                .<CopperBeaconAnimationMode>builder(CopperBeaconScreen::modeText)
                .values(CopperBeaconAnimationMode.values())
                .initially(state.mode())
                .build(
                        panelX,
                        panelY + 29,
                        PANEL_WIDTH,
                        CONTROL_HEIGHT,
                        Text.translatable(
                                "container.rtx-remix-renderer.copper_beacon.animation"),
                        (button, mode) -> {
                            queue(
                                    yaw
                                            ? CopperBeaconUpdatePayload.Field.YAW_MODE
                                            : CopperBeaconUpdatePayload.Field.PITCH_MODE,
                                    mode.ordinal());
                            rebuildControls();
                        }));

        switch (state.mode()) {
            case STATIC -> {
            }
            case ROTATE -> addSlider(
                    panelX,
                    panelY + 47,
                    PANEL_WIDTH,
                    "container.rtx-remix-renderer.copper_beacon.rotationSpeed",
                    state.rotationSpeed(),
                    CopperBeaconScreen::signedSpeedToSlider,
                    CopperBeaconScreen::sliderToSignedSpeed,
                    value -> Text.literal(String.format(Locale.ROOT, "%.1f\u00b0/s", value)),
                    yaw
                            ? CopperBeaconUpdatePayload.Field.YAW_ROTATION_SPEED
                            : CopperBeaconUpdatePayload.Field.PITCH_ROTATION_SPEED);
            case OSCILLATE, PATROL -> {
                addSlider(
                        panelX,
                        panelY + 47,
                        RANGE_SLIDER_WIDTH,
                        "container.rtx-remix-renderer.copper_beacon.minimum",
                        state.minimumOffset(),
                        CopperBeaconScreen::angleToSlider,
                        CopperBeaconScreen::sliderToAngle,
                        value -> Text.literal(String.format(Locale.ROOT, "%.0f\u00b0", value)),
                        yaw
                                ? CopperBeaconUpdatePayload.Field.YAW_MINIMUM_OFFSET
                                : CopperBeaconUpdatePayload.Field.PITCH_MINIMUM_OFFSET);
                addSlider(
                        panelX + RANGE_SLIDER_WIDTH + RANGE_SLIDER_GAP,
                        panelY + 47,
                        RANGE_SLIDER_WIDTH,
                        "container.rtx-remix-renderer.copper_beacon.maximum",
                        state.maximumOffset(),
                        CopperBeaconScreen::angleToSlider,
                        CopperBeaconScreen::sliderToAngle,
                        value -> Text.literal(String.format(Locale.ROOT, "%.0f\u00b0", value)),
                        yaw
                                ? CopperBeaconUpdatePayload.Field.YAW_MAXIMUM_OFFSET
                                : CopperBeaconUpdatePayload.Field.PITCH_MAXIMUM_OFFSET);
                boolean oscillate = state.mode() == CopperBeaconAnimationMode.OSCILLATE;
                addSlider(
                        panelX,
                        panelY + 65,
                        PANEL_WIDTH,
                        oscillate
                                ? "container.rtx-remix-renderer.copper_beacon.cycle"
                                : "container.rtx-remix-renderer.copper_beacon.patrol",
                        oscillate ? state.cycleSeconds() : state.patrolSeconds(),
                        CopperBeaconScreen::durationToSlider,
                        CopperBeaconScreen::sliderToDuration,
                        value -> Text.literal(String.format(Locale.ROOT, "%.2fs", value)),
                        oscillate
                                ? (yaw
                                        ? CopperBeaconUpdatePayload.Field.YAW_CYCLE_SECONDS
                                        : CopperBeaconUpdatePayload.Field.PITCH_CYCLE_SECONDS)
                                : (yaw
                                        ? CopperBeaconUpdatePayload.Field.YAW_PATROL_SECONDS
                                        : CopperBeaconUpdatePayload.Field.PITCH_PATROL_SECONDS));
            }
        }
    }

    private void rebuildControls() {
        clearChildren();
        buildControls();
    }

    private void addSlider(
            int x,
            int y,
            int width,
            String labelKey,
            double current,
            DoubleUnaryOperator toSlider,
            DoubleUnaryOperator fromSlider,
            Function<Double, Text> valueText,
            CopperBeaconUpdatePayload.Field field) {
        addDrawableChild(new BeaconSlider(
                x,
                y,
                width,
                Text.translatable(labelKey),
                Text.translatable(labelKey + ".tip"),
                Math.clamp(toSlider.applyAsDouble(current), 0.0, 1.0),
                fromSlider,
                valueText,
                value -> queue(field, value.floatValue()),
                this::flushPending));
    }

    private void queue(CopperBeaconUpdatePayload.Field field, float value) {
        handler.applyUpdate(field, value);
        pending.put(field, value);
    }

    private void flushPending() {
        if (!ClientPlayNetworking.canSend(CopperBeaconUpdatePayload.ID)) {
            pending.clear();
            return;
        }
        pending.forEach((field, value) -> ClientPlayNetworking.send(
                new CopperBeaconUpdatePayload(handler.syncId, field, value)));
        pending.clear();
    }

    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        flushPending();
    }

    @Override
    public void removed() {
        flushPending();
        super.removed();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(
            DrawContext context, float delta, int mouseX, int mouseY) {
        context.drawBorder(x, y, backgroundWidth, backgroundHeight, 0xFF8B8B8B);
        for (var slot : handler.slots) {
            context.drawGuiTexture(
                    RenderLayer::getGuiTextured, SLOT_TEXTURE,
                    x + slot.x - 1,
                    y + slot.y - 1,
                    18,
                    18);
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(
                textRenderer,
                title,
                titleX,
                titleY,
                0xFFE0E0E0,
                false);
        context.drawText(
                textRenderer,
                playerInventoryTitle,
                playerInventoryTitleX,
                playerInventoryTitleY,
                0xFFE0E0E0,
                false);
        context.drawText(
                textRenderer,
                Text.translatable("container.rtx-remix-renderer.copper_beacon.yaw"),
                OUTER_MARGIN,
                55,
                0xFFE0E0E0,
                false);
        context.drawText(
                textRenderer,
                Text.translatable("container.rtx-remix-renderer.copper_beacon.pitch"),
                OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                55,
                0xFFE0E0E0,
                false);
        context.drawText(
                textRenderer,
                Text.translatable("container.rtx-remix-renderer.copper_beacon.dye"),
                OUTER_MARGIN,
                22,
                0xFFE0E0E0,
                false);
    }

    private static Text modeText(CopperBeaconAnimationMode mode) {
        return Text.translatable(
                "container.rtx-remix-renderer.copper_beacon.mode."
                        + mode.name().toLowerCase(Locale.ROOT));
    }

    static double intensityToSlider(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        return value <= 1.0
                ? (Math.log10(value) + 2.0) / 4.0
                : (Math.log10(value) + 3.0) / 6.0;
    }

    static double sliderToIntensity(double slider) {
        if (slider == 0.0) {
            return 0.0;
        }
        return slider <= 0.5
                ? Math.pow(10.0, slider * 4.0 - 2.0)
                : Math.pow(10.0, slider * 6.0 - 3.0);
    }

    static double angleToSlider(double value) {
        return (value - CopperBeaconAxisState.MIN_ANGLE)
                / (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE);
    }

    static double sliderToAngle(double slider) {
        return CopperBeaconAxisState.MIN_ANGLE
                + slider * (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE);
    }

    static double signedSpeedToSlider(double value) {
        if (value == 0.0) {
            return 0.5;
        }
        double magnitude = (Math.log10(Math.abs(value)) + 1.0)
                / (Math.log10(CopperBeaconAxisState.MAX_ROTATION_SPEED) + 1.0);
        return 0.5 + Math.copySign(0.5 * magnitude, value);
    }

    static double sliderToSignedSpeed(double slider) {
        double centered = slider * 2.0 - 1.0;
        if (Math.abs(centered) < 0.01) {
            return 0.0;
        }
        double exponent = Math.abs(centered)
                * (Math.log10(CopperBeaconAxisState.MAX_ROTATION_SPEED) + 1.0)
                - 1.0;
        return Math.copySign(Math.pow(10.0, exponent), centered);
    }

    static double durationToSlider(double value) {
        return Math.log(value / CopperBeaconAxisState.MIN_DURATION_SECONDS)
                / Math.log(CopperBeaconAxisState.MAX_DURATION_SECONDS
                        / CopperBeaconAxisState.MIN_DURATION_SECONDS);
    }

    static double sliderToDuration(double slider) {
        return CopperBeaconAxisState.MIN_DURATION_SECONDS * Math.pow(
                CopperBeaconAxisState.MAX_DURATION_SECONDS
                        / CopperBeaconAxisState.MIN_DURATION_SECONDS,
                slider);
    }

    @FunctionalInterface
    private interface ValueConsumer {
        void accept(Double value);
    }

    private static final class BeaconSlider extends SliderWidget {
        private final Text label;
        private final DoubleUnaryOperator fromSlider;
        private final Function<Double, Text> valueText;
        private final ValueConsumer consumer;
        private final Runnable release;

        private BeaconSlider(
                int x,
                int y,
                int width,
                Text label,
                Text tooltip,
                double value,
                DoubleUnaryOperator fromSlider,
                Function<Double, Text> valueText,
                ValueConsumer consumer,
                Runnable release) {
            super(x, y, width, CONTROL_HEIGHT, ScreenTexts.EMPTY, value);
            this.label = label;
            this.fromSlider = fromSlider;
            this.valueText = valueText;
            this.consumer = consumer;
            this.release = release;
            setTooltip(Tooltip.of(tooltip));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double actual = fromSlider.applyAsDouble(value);
            setMessage(ScreenTexts.composeGenericOptionText(label, valueText.apply(actual)));
        }

        @Override
        protected void applyValue() {
            consumer.accept(fromSlider.applyAsDouble(value));
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean released = super.mouseReleased(mouseX, mouseY, button);
            release.run();
            return released;
        }
    }
}
