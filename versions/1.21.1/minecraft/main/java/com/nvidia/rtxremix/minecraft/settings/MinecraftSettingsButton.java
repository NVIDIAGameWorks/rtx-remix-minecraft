/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Adds the shared RTX settings entry to Minecraft's options screen. */
public final class MinecraftSettingsButton {
    private MinecraftSettingsButton() {
    }

    public static void add(
            Minecraft client,
            Screen screen,
            int width,
            int height,
            List<AbstractWidget> widgets,
            RtxSettings settings,
            Runnable save) {
        if (!(screen instanceof OptionsScreen) || settings == null) {
            return;
        }
        Component label = Component.translatable("rtxremix.settings.button");
        widgets.removeIf(widget -> widget.getMessage().equals(label));
        AbstractWidget done = null;
        for (AbstractWidget widget : widgets) {
            if (widget.getMessage().equals(CommonComponents.GUI_DONE)) {
                done = widget;
                break;
            }
        }
        Button button = Button.builder(
                        label,
                        ignored -> client.setScreen(
                                new RtxSettingsScreen(screen, settings, save)))
                .build();
        if (done != null) {
            int lastBodyY = Integer.MIN_VALUE;
            for (AbstractWidget widget : widgets) {
                if (widget != done && widget.getY() < done.getY()) {
                    lastBodyY = Math.max(lastBodyY, widget.getY());
                }
            }
            if (lastBodyY == Integer.MIN_VALUE) {
                lastBodyY = done.getY() - 24;
            }
            int buttonY = lastBodyY + 24;
            int overflow = buttonY + 24 - done.getY();
            if (overflow > 0) {
                int firstBodyY = lastBodyY - 96;
                for (AbstractWidget widget : widgets) {
                    if (widget != done
                            && widget.getY() >= firstBodyY
                            && widget.getY() <= lastBodyY) {
                        widget.setY(widget.getY() - overflow);
                    }
                }
                buttonY -= overflow;
            }
            button.setRectangle(308, 20, width / 2 - 154, buttonY);
        } else {
            button.setRectangle(200, 20, width / 2 - 100, height - 27);
        }
        widgets.add(button);
    }
}
