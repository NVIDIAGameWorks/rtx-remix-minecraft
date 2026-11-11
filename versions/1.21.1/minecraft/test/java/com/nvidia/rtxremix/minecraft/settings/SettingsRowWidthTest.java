/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Every option row on the settings screens is a vanilla widget of fixed width
 * that draws {@code "<label>: <value>"} once, and text wider than the widget
 * scrolls as a marquee that clips the label at whichever edge it has reached.
 * The English strings are therefore authored to fit at the widest value each
 * row can show, and this test measures them with the default font's real
 * glyph advances, read from the Minecraft jar on the test classpath.
 */
class SettingsRowWidthTest {

    /** {@code OptionListWidget} lays out two option widgets of this width per row. */
    private static final int OPTION_WIDGET_WIDTH = 150;
    /** Sliders and buttons inset their message by this many pixels on each side. */
    private static final int WIDGET_TEXT_MARGIN = 2;
    private static final int OPTION_TEXT_WIDTH = OPTION_WIDGET_WIDTH - 2 * WIDGET_TEXT_MARGIN;
    /** The Block Lights table names each row in a {@code TextWidget} of this width. */
    private static final int BLOCK_LIGHT_NAME_WIDTH = 150;
    /** The landing and category screens pair two footer buttons of this width. */
    private static final int FOOTER_BUTTON_WIDTH = 98;

    /** Plain buttons on the settings screens and the width each is built with. */
    private static final Map<String, Integer> BUTTON_WIDTHS = Map.of(
            "rtxremix.settings.reset", FOOTER_BUTTON_WIDTH,
            "rtxremix.settings.resetAll", FOOTER_BUTTON_WIDTH,
            "rtxremix.settings.configure", OPTION_WIDGET_WIDTH,
            "rtxremix.settings.openRemixMenu", OPTION_WIDGET_WIDTH);

    /**
     * Rows {@code RtxSettingsCategoryScreen.fullWidthRows} lays out one per
     * line through {@code OptionListWidget.addSingleOptionEntry}, which builds
     * the widget at this width.
     */
    private static final int FULL_WIDTH_OPTION_WIDTH = 310;
    private static final int FULL_WIDTH_OPTION_TEXT_WIDTH = FULL_WIDTH_OPTION_WIDTH - 2 * WIDGET_TEXT_MARGIN;
    private static final Set<String> FULL_WIDTH_ROWS = Set.of("rtxremix.setting.framerate.unfocused");

    private static final String LABEL_PREFIX = "rtxremix.setting.";
    private static final String TOOLTIP_SUFFIX = ".tip";
    private static final String BLOCK_LIGHT_NAME_PREFIX = "rtxremix.setting.blockLights.";

    /**
     * The widest value text each option row can display, as
     * {@link RtxOptionFactory} formats it at the ends of the range declared in
     * {@link RtxSettingsFactory}. A row missing here fails the test rather than
     * going unmeasured.
     */
    private static final Map<String, String> WIDEST_VALUE = Map.ofEntries(
            entry("rtxremix.setting.dist.autoFade", "OFF"),
            entry("rtxremix.setting.dist.light", "Auto"),
            entry("rtxremix.setting.dist.entity", "Auto"),
            entry("rtxremix.setting.light.reach", "256"),
            entry("rtxremix.setting.framerate.unfocused", "Unlimited"),
            entry("rtxremix.setting.sky.enabled", "OFF"),
            entry("rtxremix.setting.sky.ambient", "OFF"),
            entry("rtxremix.setting.sky.haloFocus", "0.99"),
            entry("rtxremix.setting.sky.minimumCelestialWeatherVisibility", "1.00"),
            entry("rtxremix.setting.clouds.procedural", "Procedural"),
            entry("rtxremix.setting.clouds.vanillaEdgeSmoothness", "1.99"),
            entry("rtxremix.setting.clouds.size", "2.00"),
            entry("rtxremix.setting.clouds.height", "-128.00"),
            entry("rtxremix.setting.sky.sunIntensity", "100.00"),
            entry("rtxremix.setting.sky.moonIntensity", "10.00"),
            entry("rtxremix.setting.sky.dayAmbient", "5.00"),
            entry("rtxremix.setting.sky.nightAmbient", "10.00"),
            entry("rtxremix.setting.materials.terrainEmission", "50.00×"),
            entry("rtxremix.setting.materials.smoothReliefMaps", "OFF"),
            entry("rtxremix.setting.post.exposure", "2.00"),
            entry("rtxremix.setting.post.shadows", "-5.00"),
            entry("rtxremix.setting.post.highlights", "10.00"),
            entry("rtxremix.setting.post.lowLightAdaptation", "-5.00"),
            entry("rtxremix.setting.post.highLightAdaptation", "10.00"));

    private final DefaultFont font = DefaultFont.load();
    private final Map<String, String> lang = loadLang();

    @Test
    void everyOptionRowFitsItsWidgetAtItsWidestValue() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> label : settingLabels().entrySet()) {
            if (label.getKey().startsWith(BLOCK_LIGHT_NAME_PREFIX)) {
                continue;
            }
            String widestValue = WIDEST_VALUE.get(label.getKey());
            assertNotNull(widestValue, "register the widest value of " + label.getKey()
                    + " in " + getClass().getSimpleName());
            String row = label.getValue() + ": " + widestValue;
            int width = font.width(row);
            int textWidth = FULL_WIDTH_ROWS.contains(label.getKey())
                    ? FULL_WIDTH_OPTION_TEXT_WIDTH : OPTION_TEXT_WIDTH;
            if (width > textWidth) {
                failures.add(String.format("%s is %d px, %d over: \"%s\"",
                        label.getKey(), width, width - textWidth, row));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyBlockLightNameFitsItsColumn() {
        for (Map.Entry<String, String> label : settingLabels().entrySet()) {
            if (!label.getKey().startsWith(BLOCK_LIGHT_NAME_PREFIX)) {
                continue;
            }
            int width = font.width(label.getValue());
            assertTrue(width <= BLOCK_LIGHT_NAME_WIDTH, String.format(
                    "%s is %d px wide: \"%s\"", label.getKey(), width, label.getValue()));
        }
    }

    @Test
    void everyButtonLabelFitsItsWidget() {
        for (Map.Entry<String, Integer> button : BUTTON_WIDTHS.entrySet()) {
            String label = lang.get(button.getKey());
            assertNotNull(label, button.getKey() + " has no label");
            int width = font.width(label);
            int textWidth = button.getValue() - 2 * WIDGET_TEXT_MARGIN;
            assertTrue(width <= textWidth, String.format("%s is %d px, %d over: \"%s\"",
                    button.getKey(), width, width - textWidth, label));
        }
    }

    @Test
    void everyRegisteredWidestValueBelongsToALabel() {
        for (String key : WIDEST_VALUE.keySet()) {
            assertTrue(lang.containsKey(key), key + " is registered here but has no label");
        }
    }

    private Map<String, String> settingLabels() {
        Map<String, String> labels = new HashMap<>();
        for (Map.Entry<String, String> line : lang.entrySet()) {
            String key = line.getKey();
            if (key.startsWith(LABEL_PREFIX) && !key.endsWith(TOOLTIP_SUFFIX)) {
                labels.put(key, line.getValue());
            }
        }
        assertFalse(labels.isEmpty(), "no " + LABEL_PREFIX + "* labels in en_us.json");
        return labels;
    }

    private static Map<String, String> loadLang() {
        JsonObject json = readJson("/assets/rtx-remix-renderer/lang/en_us.json");
        Map<String, String> lang = new HashMap<>();
        for (Map.Entry<String, JsonElement> line : json.entrySet()) {
            lang.put(line.getKey(), line.getValue().getAsString());
        }
        return lang;
    }

    private static JsonObject readJson(String resource) {
        try (InputStream in = open(resource)) {
            return JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = SettingsRowWidthTest.class.getResourceAsStream(resource);
        assertNotNull(in, resource + " is not on the test classpath");
        return in;
    }

    /**
     * Advances of Minecraft's default font, computed the way {@code BitmapFont}
     * computes them: the rightmost opaque column of a glyph cell plus one pixel
     * of spacing, scaled by the provider's height. Providers are consulted in
     * declaration order and the first that supplies a code point wins, as in
     * {@code FontStorage}. Only the bitmap providers are loaded, so a string
     * that needs the Unifont fallback fails loudly instead of measuring short.
     */
    private static final class DefaultFont {

        private final Map<Integer, Integer> advances = new HashMap<>();

        static DefaultFont load() {
            DefaultFont font = new DefaultFont();
            font.loadSpaceProvider(readJson("/assets/minecraft/font/include/space.json"));
            for (JsonElement provider
                    : readJson("/assets/minecraft/font/include/default.json").getAsJsonArray("providers")) {
                font.loadBitmapProvider(provider.getAsJsonObject());
            }
            return font;
        }

        int width(String text) {
            int width = 0;
            for (int codePoint : text.codePoints().toArray()) {
                Integer advance = advances.get(codePoint);
                assertNotNull(advance, String.format(
                        "U+%04X in \"%s\" is not in the default font's bitmap providers",
                        codePoint, text));
                width += advance;
            }
            return width;
        }

        private void loadSpaceProvider(JsonObject spaceFont) {
            JsonObject provider = spaceFont.getAsJsonArray("providers").get(0).getAsJsonObject();
            for (Map.Entry<String, JsonElement> advance
                    : provider.getAsJsonObject("advances").entrySet()) {
                advances.putIfAbsent(advance.getKey().codePointAt(0), advance.getValue().getAsInt());
            }
        }

        private void loadBitmapProvider(JsonObject provider) {
            String file = provider.get("file").getAsString();
            String texture = "/assets/minecraft/textures/" + file.substring(file.indexOf(':') + 1);
            BufferedImage image = readImage(texture);
            JsonArray rows = provider.getAsJsonArray("chars");
            int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
            int cellWidth = image.getWidth() / columns;
            int cellHeight = image.getHeight() / rows.size();
            float scale = (provider.has("height") ? provider.get("height").getAsInt() : 8)
                    / (float) cellHeight;
            for (int row = 0; row < rows.size(); row++) {
                int[] codePoints = rows.get(row).getAsString().codePoints().toArray();
                for (int column = 0; column < codePoints.length; column++) {
                    if (codePoints[column] == 0) {
                        continue;
                    }
                    int glyphWidth = glyphWidth(image, cellWidth, cellHeight, column, row);
                    advances.putIfAbsent(codePoints[column], (int) (0.5 + glyphWidth * scale) + 1);
                }
            }
        }

        private static int glyphWidth(BufferedImage image, int cellWidth, int cellHeight,
                int column, int row) {
            for (int x = cellWidth - 1; x >= 0; x--) {
                for (int y = 0; y < cellHeight; y++) {
                    int alpha = image.getRGB(column * cellWidth + x, row * cellHeight + y) >>> 24;
                    if (alpha != 0) {
                        return x + 1;
                    }
                }
            }
            return 0;
        }

        private static BufferedImage readImage(String resource) {
            try (InputStream in = open(resource)) {
                return ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
