/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Two-column editor for built-in block-light intensity multipliers. */
final class RtxBlockLightsScreen extends OptionsSubScreen {

    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final List<BlockLightMultiplierSlider> sliders = new ArrayList<>();

    RtxBlockLightsScreen(Screen parent, RtxSettings settings, Runnable saveSettings) {
        super(
                parent,
                Minecraft.getInstance().options,
                Component.translatable("rtxremix.settings.blockLights.title"));
        this.settings = settings;
        this.saveSettings = saveSettings;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void addOptions() {
        sliders.clear();
        this.list.addSmall(
                new StringWidget(
                        150,
                        20,
                        Component.translatable("rtxremix.settings.blockLights.block")
                                .withStyle(ChatFormatting.BOLD),
                        this.font),
                new StringWidget(
                        150,
                        20,
                        Component.translatable("rtxremix.settings.blockLights.intensity")
                                .withStyle(ChatFormatting.BOLD),
                        this.font));

        for (RtxSetting<?> raw : settings.group(SettingGroup.BLOCK_LIGHTS)) {
            RtxSetting<Double> setting = (RtxSetting<Double>) raw;
            StringWidget name =
                    new StringWidget(150, 20, Component.translatable(setting.labelKey()), this.font);
            name.setTooltip(Tooltip.create(Component.translatable(setting.tooltipKey())));
            BlockLightMultiplierSlider slider = new BlockLightMultiplierSlider(setting);
            sliders.add(slider);
            this.list.addSmall(name, slider);
        }
    }

    @Override
    protected void addFooter() {
        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        footer.addChild(Button.builder(
                        Component.translatable("rtxremix.settings.reset"), button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.reset.tip")))
                .width(98)
                .build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(98).build());
        this.layout.addToFooter(footer);
    }

    private boolean commitPendingValues() {
        boolean changed = false;
        for (BlockLightMultiplierSlider slider : sliders) {
            changed |= slider.commit();
        }
        return changed;
    }

    private void confirmReset() {
        boolean pendingChanged = commitPendingValues();
        this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            boolean rebuild = pendingChanged;
            if (confirmed) {
                rebuild |= settings.hasOverrides(SettingGroup.BLOCK_LIGHTS);
                settings.resetGroup(SettingGroup.BLOCK_LIGHTS);
            }
            if (saveSettings != null) {
                saveSettings.run();
            }
            if (rebuild) {
                RtxRemixMod.rebuildLoadedTerrain();
            }
            this.minecraft.gui.setScreen(new RtxBlockLightsScreen(this.lastScreen, settings, saveSettings));
        }, Component.translatable(
                        "rtxremix.settings.reset.confirm.title",
                        Component.translatable("rtxremix.settings.blockLights.title")),
                Component.translatable(
                        "rtxremix.settings.reset.confirm.message",
                        Component.translatable("rtxremix.settings.blockLights.title"))));
    }

    @Override
    public void onClose() {
        boolean changed = commitPendingValues();
        if (saveSettings != null) {
            saveSettings.run();
        }
        if (changed) {
            RtxRemixMod.rebuildLoadedTerrain();
        }
        this.minecraft.gui.setScreen(this.lastScreen);
    }
}
