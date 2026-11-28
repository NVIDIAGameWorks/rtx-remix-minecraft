/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;

/**
 * Manages sky rendering for RTX Remix.
 *
 * <p>Uses a physically-based dome light generated from Rayleigh+Mie atmospheric
 * scattering, driven by Minecraft's time-of-day. Weather blends a static HDR
 * overcast dome over the atmospheric sky. Dimensions without a NORMAL sky publish a
 * constant dome instead: dark violet for the End, and black with no sky ambient for a
 * skyless (SkyType.NONE) dimension such as the Nether.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyRenderer.class);

    /** Equirectangular sky texture resolution. */
    private static final int SKY_W = SkyAtmosphereSource.WIDTH;
    private static final int SKY_H = SkyAtmosphereSource.HEIGHT;
    private static final float RAIN_DOME_MIN_INTENSITY = 0.02f;
    private static final float OVERCAST_MEAN_R = 0.2393801f;
    private static final float OVERCAST_MEAN_G = 0.26890025f;
    private static final float OVERCAST_MEAN_B = 0.31064802f;
    private static final float WEATHER_UPDATE_DELTA = 1.0e-4f;
    private static final float CELESTIAL_DISTANCE = 100.0f;
    private static final float SUN_HALF_SIZE = 30.0f;
    private static final float MOON_HALF_SIZE = 20.0f;
    private static final float SUN_RADIANCE = 8.0f;
    private static final float MOON_RADIANCE = 0.2f;
    private static final float STAR_RADIANCE = 1.0f;
    private static final float STAR_DISTANCE_SCALE = 1.1f;
    private static final float CELESTIAL_HORIZON_FADE_DEGREES = 4.0f;
    private static final float CELESTIAL_HORIZON_FADE_MIN_HEIGHT =
            -(float) Math.sin(Math.toRadians(CELESTIAL_HORIZON_FADE_DEGREES));
    private static final float CELESTIAL_OPACITY_UPDATE_DELTA = 1.0f / 255.0f;
    private static final InstanceInfoBlendEXT CELESTIAL_BLEND =
            createCelestialBlend();

    /** Sky ambient rect light: Y position just above the 1.18+ overworld build limit (y=320). */
    private static final float SKY_AMBIENT_RECT_Y = 325.0f;
    /** Sky ambient rect light: half-size in blocks, so it covers 8000 blocks from origin. */
    private static final float SKY_AMBIENT_RECT_HALF_SIZE = 8000.0f;
    /**
     * Constant sky for the End, in the atmospheric dome's radiance units: vanilla end_sky.png's
     * mean chroma under its 0xFF282828 vertex tint, at the luminance of that tint (40/255) times
     * the End's 0.15 fog factor. It feeds both the dome and the ambient rect.
     */
    static final float[] END_SKY_RADIANCE = {0.0275f, 0.0210f, 0.0372f};
    /**
     * Constant sky for a skyless dimension (SkyType.NONE): a black dome and, since the ambient
     * rect carries the same constant, no sky ambient. Nothing above a roofed world reaches its
     * interior, so a rect above the build limit would light only the roof top; interior light
     * comes from emissives and the fog medium. The black dome is still published: without an
     * active dome light Remix composites escaped rays from its own sky matte, which nothing
     * draws here.
     */
    static final float[] SKYLESS_SKY_RADIANCE = {0.0f, 0.0f, 0.0f};
    public static final float DEFAULT_NIGHT_AMBIENT_BOOST =
            SkySettings.DEFAULT_NIGHT_AMBIENT_BOOST;
    public static final float DEFAULT_DAY_AMBIENT_BOOST =
            SkySettings.DEFAULT_DAY_AMBIENT_BOOST;
    public static final boolean DEFAULT_SKY_AMBIENT_RECT_ENABLED =
            SkySettings.DEFAULT_SKY_AMBIENT_RECT_ENABLED;
    public static final float DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY =
            SkySettings.DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY;
    public static final float DEFAULT_MIE_SCATTERING_ANISOTROPY =
            SkySettings.DEFAULT_MIE_SCATTERING_ANISOTROPY;
    public static final float MAX_MIE_SCATTERING_ANISOTROPY =
            SkySettings.MAX_MIE_SCATTERING_ANISOTROPY;

    public static float getNightAmbientBoost() {
        return SkySettings.getNightAmbientBoost();
    }
    public static void setNightAmbientBoost(float v) {
        SkySettings.setNightAmbientBoost(v);
    }
    public static float getDayAmbientBoost() {
        return SkySettings.getDayAmbientBoost();
    }
    public static void setDayAmbientBoost(float v) {
        SkySettings.setDayAmbientBoost(v);
    }
    public static float getMinimumCelestialWeatherVisibility() {
        return SkySettings.getMinimumCelestialWeatherVisibility();
    }
    public static void setMinimumCelestialWeatherVisibility(float v) {
        SkySettings.setMinimumCelestialWeatherVisibility(v);
    }
    public static float getMieScatteringAnisotropy() {
        return SkySettings.getMieScatteringAnisotropy();
    }
    public static void setMieScatteringAnisotropy(float v) {
        SkySettings.setMieScatteringAnisotropy(v);
    }

    /**
     * Sun path tilt in degrees, matching popular shader packs (BSL, Complementary).
     * Tilts the sun's orbital plane around the east-west (X) axis so that even
     * at noon, shadows have an angle instead of pointing straight down.
     */
    public static final float SUN_PATH_ROTATION_DEG = SkyLightState.SUN_PATH_ROTATION_DEG;
    // ---- Instance state ----

    private final RemixAPI api;
    private final SkyResourceSource resources;
    private Object currentWorld;
    private RemixTexture skyTextureAsset;
    private RemixLight skyDomeLightAsset;
    private long overcastTextureHandle;
    private RemixLight overcastDomeLightAsset;
    private RemixLight skyAmbientRectLight;
    private RemixTexture sunTextureAsset;
    private RemixTexture moonTextureAsset;
    private RemixMaterial sunMaterialAsset;
    private RemixMaterial moonMaterialAsset;
    private RemixMaterial starMaterialAsset;
    private RemixMesh sunMeshAsset;
    private final RemixMesh[] moonMeshAssets = new RemixMesh[8];
    private RemixMesh starMeshAsset;
    private long celestialBlendPNext;
    private final SkyAtmosphereSource atmosphericGenerator;
    private final TexturePatch atmosphericTexturePatch = new TexturePatch();
    private final TextureBatchEntry atmosphericTextureEntry = new TextureBatchEntry();
    private final TexturePatchInfo atmosphericTextureUpdate = new TexturePatchInfo();
    private final float[] atmosphericMean = new float[3];
    private final Transform sunTransform = new Transform();
    private final Transform moonTransform = new Transform();
    private final Transform starTransform = new Transform();
    private final float[] celestialMoonDirection = new float[3];
    private boolean celestialInitializationFailed;
    private float lastWeatherOpacity = -1f;
    private float lastWeatherIntensity = -1f;
    private float lastSkyDomeMultiplier = Float.NaN;
    private float atmosphericMeanR;
    private float atmosphericMeanG;
    private float atmosphericMeanB;
    private float lastSunOpacity = -1.0f;
    private float lastMoonOpacity = -1.0f;
    private float lastStarOpacity = -1.0f;
    private boolean overcastInitializationFailed;

    public SkyRenderer(
            RemixAPI api,
            SkyAtmosphereSource atmosphericGenerator,
            SkyResourceSource resources) {
        this.api = api;
        this.atmosphericGenerator = atmosphericGenerator;
        this.resources = resources;
        atmosphericTexturePatch.width = SKY_W;
        atmosphericTexturePatch.height = SKY_H;
        atmosphericTexturePatch.rowPitch = SKY_W * 4 * Short.BYTES;
        atmosphericTexturePatch.format = TextureFormat.RGBA16_SFLOAT;
        atmosphericTextureEntry.patches = new TexturePatch[]{atmosphericTexturePatch};
        atmosphericTextureUpdate.entries = new TextureBatchEntry[]{atmosphericTextureEntry};
        LOGGER.info("Sky renderer initialized (Rayleigh+Mie dome light)");
    }

    public static boolean isEnabled() { return SkySettings.isEnabled(); }
    public static void setEnabled(boolean v) { SkySettings.setEnabled(v); }
    public static boolean isSkyAmbientRectEnabled() {
        return SkySettings.isAmbientRectEnabled();
    }
    public static void setSkyAmbientRectEnabled(boolean v) {
        SkySettings.setAmbientRectEnabled(v);
    }
    public static float getSkyDomeMultiplier() {
        return SkySettings.getDomeMultiplier();
    }
    public static void setSkyDomeMultiplier(float v) {
        SkySettings.setDomeMultiplier(v);
    }

    /** Invalidates cached sky parameters so they are applied on the next frame. */
    public void forceRefresh() {
        lastWeatherOpacity = -1f;
        lastWeatherIntensity = -1f;
        lastSunOpacity = -1.0f;
        lastMoonOpacity = -1.0f;
        lastStarOpacity = -1.0f;
    }

    // ====================================================================
    //  Main render entry
    // ====================================================================

    public void render(SkyFrame frame) {
        if (frame == null || !isEnabled()) return;

        // A portal replaces the world without a disconnect, and each sky type publishes a
        // different set of lights, so nothing published for the previous world may carry over.
        if (frame.worldIdentity != currentWorld) {
            releaseSkyLights();
            currentWorld = frame.worldIdentity;
        }

        float[] fixedRadiance = fixedSkyRadiance(frame.type);
        if (fixedRadiance == null) {
            renderAtmosphericSky(frame);
        } else {
            renderFixedSky(api, fixedRadiance);
        }
    }

    /**
     * Maps a sky type to the constant radiance its dome and ambient rect publish.
     *
     * @return the constant, or null for a NORMAL sky, whose dome is the time-driven atmosphere
     */
    static float[] fixedSkyRadiance(SkyFrame.Type skyType) {
        switch (skyType) {
            case NORMAL:
                return null;
            case END:
                return END_SKY_RADIANCE;
            case NONE:
                return SKYLESS_SKY_RADIANCE;
            default:
                throw new IllegalStateException("Unknown sky type");
        }
    }

    private void renderAtmosphericSky(SkyFrame frame) {
        float skyAngle;
        SkyLightState lightState;
        float[] sunDirection;
        WeatherDomeState weather;
        try (PerformanceTimer.ProfileScopeHandle ignored =
                     PerformanceTimer.beginProfileScope("Sky.Prepare")) {
            skyAngle = frame.skyAngle;
            lightState = frame.lightState;
            sunDirection = SkyLightState.computeSunDirection(skyAngle);
            float celestialIntensity =
                    lightState.weatherDomeRadiance();
            weather = weatherDomeState(
                    frame.rain,
                    frame.thunder,
                    Math.max(
                            celestialIntensity,
                            RAIN_DOME_MIN_INTENSITY),
                    celestialIntensity
                            * SkyLightState.THUNDER_SKY_INTENSITY);
        }

        try (PerformanceTimer.ProfileScopeHandle ignored =
                     PerformanceTimer.beginProfileScope("Sky.Atmosphere")) {
            updateAtmosphericDomeLight(api, lightState);
        }
        try (PerformanceTimer.ProfileScopeHandle ignored =
                     PerformanceTimer.beginProfileScope("Sky.Celestial")) {
            renderCelestialMeshes(
                    api,
                    frame,
                    sunDirection,
                    weather.opacity());
        }
        boolean weatherRendered;
        try (PerformanceTimer.ProfileScopeHandle ignored =
                     PerformanceTimer.beginProfileScope("Sky.Weather")) {
            weatherRendered = updateWeatherDomeLight(api, weather);
        }
        try (PerformanceTimer.ProfileScopeHandle ignored =
                     PerformanceTimer.beginProfileScope("Sky.Ambient")) {
            publishCombinedSkyAmbient(
                    api,
                    sunDirection[1],
                    weatherRendered
                            ? weather
                            : WeatherDomeState.CLEAR);
        }
    }

    /**
     * Publishes a dome of one constant radiance through the atmospheric dome's texture and
     * light, and an ambient rect of the same radiance, so a black sky publishes no ambient. The
     * texture is filled once per world and never dispatches the atmosphere compute.
     */
    private void renderFixedSky(RemixAPI api, float[] radiance) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            destroyAsset(skyTextureAsset);
            ByteBuffer pixels = DirectBufferPool.acquire(SkyAtmosphereSource.OUTPUT_BYTES);
            fillFixedDome(pixels, radiance);
            skyTextureAsset = createSkyTexture(pixels);
        }
        publishSkyDome(api);
        publishSkyAmbient(api, radiance[0], radiance[1], radiance[2]);
    }

    /**
     * Fills an RGBA16F equirect of the dome's resolution with one opaque radiance, leaving the
     * buffer positioned at zero with its limit on the last dome byte.
     *
     * @throws IllegalArgumentException when the buffer cannot hold the whole dome
     */
    static void fillFixedDome(ByteBuffer pixels, float[] radiance) {
        if (pixels.capacity() < SkyAtmosphereSource.OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Dome buffer is smaller than " + SkyAtmosphereSource.OUTPUT_BYTES);
        }
        short r = Float.floatToFloat16(radiance[0]);
        short g = Float.floatToFloat16(radiance[1]);
        short b = Float.floatToFloat16(radiance[2]);
        short a = Float.floatToFloat16(1.0f);
        pixels.clear();
        pixels.order(ByteOrder.nativeOrder());
        pixels.limit(SkyAtmosphereSource.OUTPUT_BYTES);
        for (int texel = 0; texel < SKY_W * SKY_H; texel++) {
            pixels.putShort(r).putShort(g).putShort(b).putShort(a);
        }
        pixels.flip();
    }

    // ====================================================================
    //  Dome lights
    // ====================================================================

    private void updateAtmosphericDomeLight(
            RemixAPI api,
            SkyLightState lightState) {
        regenerateSkyTexture(api, lightState);
        publishSkyDome(api);
    }

    /**
     * Creates the dome light over the live sky texture when it is missing, refreshes its
     * multiplier when that changed, and draws it. Does nothing until the texture exists.
     */
    private void publishSkyDome(RemixAPI api) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            return;
        }
        boolean domeAlive = skyDomeLightAsset != null && skyDomeLightAsset.isAlive();
        float domeMultiplier = getSkyDomeMultiplier();
        if (!domeAlive || Float.compare(lastSkyDomeMultiplier, domeMultiplier) != 0) {
            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(skyTextureAsset.getHandle());
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(domeMultiplier, domeMultiplier, domeMultiplier)
                    .domeExt(domeExt)
                    .build();
            if (domeAlive) {
                RemixLightStore.get().update(skyDomeLightAsset, lightInfo);
            } else {
                destroyAsset(skyDomeLightAsset);
                skyDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(skyDomeLightAsset);
            }
            lastSkyDomeMultiplier = domeMultiplier;
        }
        if (skyDomeLightAsset.isAlive()) {
            try (PerformanceTimer.ProfileScopeHandle ignored =
                    PerformanceTimer.beginProfileScope("Sky.Atmosphere.DomeDraw")) {
                skyDomeLightAsset.draw(api);
            }
        }
    }

    private boolean updateWeatherDomeLight(RemixAPI api, WeatherDomeState weather) {
        if (weather.opacity() <= 0.0f) {
            return false;
        }

        float domeIntensity = weather.intensity() * getSkyDomeMultiplier();
        boolean needsUpdate = overcastDomeLightAsset == null
                || Math.abs(weather.opacity() - lastWeatherOpacity) > WEATHER_UPDATE_DELTA
                || Math.abs(domeIntensity - lastWeatherIntensity) > WEATHER_UPDATE_DELTA;

        if (needsUpdate) {
            if (!ensureOvercastTexture(api)) {
                return false;
            }

            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(overcastTextureHandle);
            domeExt.setOpacity(weather.opacity());
            domeExt.setBlendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(domeIntensity, domeIntensity, domeIntensity)
                    .domeExt(domeExt)
                    .build();

            if (overcastDomeLightAsset != null && overcastDomeLightAsset.isAlive()) {
                RemixLightStore.get().update(overcastDomeLightAsset, lightInfo);
            } else {
                if (overcastDomeLightAsset != null && !overcastDomeLightAsset.isDeleted()) {
                    overcastDomeLightAsset.destroy();
                }
                overcastDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(overcastDomeLightAsset);
            }

            lastWeatherOpacity = weather.opacity();
            lastWeatherIntensity = domeIntensity;
        }

        if (overcastDomeLightAsset == null || !overcastDomeLightAsset.isAlive()) {
            return false;
        }
        overcastDomeLightAsset.draw(api);
        return true;
    }

    private boolean ensureOvercastTexture(RemixAPI api) {
        if (overcastTextureHandle != 0) {
            return true;
        }
        if (overcastInitializationFailed) {
            return false;
        }

        String texturePath = resources.overcastTexturePath();
        try {
            overcastTextureHandle = api.createTextureFromFile(texturePath);
            if (overcastTextureHandle == 0) {
                throw new IllegalStateException("Remix returned a null texture handle");
            }
            LOGGER.info("Loaded overcast dome texture {}", texturePath);
            return true;
        } catch (RuntimeException e) {
            overcastInitializationFailed = true;
            LOGGER.error("Failed to load overcast dome texture {}; using atmospheric sky", texturePath, e);
            return false;
        }
    }

    private void regenerateSkyTexture(
            RemixAPI api,
            SkyLightState lightState) {
        // Both assets must be live for a skip to be safe. They are released whenever the world
        // changes, and a world whose time never advances keeps the parameters constant forever,
        // so gating on the parameters alone would never rebuild them and the dome would stay black.
        boolean publishedOutputValid = skyTextureAsset != null
                && skyTextureAsset.isAlive()
                && skyDomeLightAsset != null
                && skyDomeLightAsset.isAlive();
        ByteBuffer pixelBuf = atmosphericGenerator.generate(
                lightState,
                atmosphericMean,
                getMieScatteringAnisotropy(),
                publishedOutputValid);
        if (pixelBuf == null) {
            return;
        }
        atmosphericMeanR = atmosphericMean[0];
        atmosphericMeanG = atmosphericMean[1];
        atmosphericMeanB = atmosphericMean[2];

        try (PerformanceTimer.ProfileScopeHandle ignored =
                PerformanceTimer.beginProfileScope("Sky.Atmosphere.Upload")
                        .bytes(SkyAtmosphereSource.OUTPUT_BYTES)) {
            if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
                destroyAsset(skyTextureAsset);
                ByteBuffer ownedPixels =
                        DirectBufferPool.acquire(
                                SkyAtmosphereSource.OUTPUT_BYTES);
                ownedPixels.put(pixelBuf);
                ownedPixels.flip();
                pixelBuf.position(0);
                skyTextureAsset = createSkyTexture(ownedPixels);
            } else {
                updateAtmosphericTexture(api, pixelBuf);
            }
        }
    }

    /** Creates the dome's equirect texture over a pool buffer the texture then owns. */
    private static RemixTexture createSkyTexture(ByteBuffer ownedPixels) {
        RemixTexture texture = new RemixTexture(
                SKY_W,
                SKY_H,
                TextureFormat.RGBA16_SFLOAT,
                0,
                ownedPixels);
        return RemixTextureStore.get().create(texture);
    }

    private void updateAtmosphericTexture(RemixAPI api, ByteBuffer pixelData) {
        atmosphericTexturePatch.pixelData = pixelData;
        atmosphericTextureEntry.handle = skyTextureAsset.getHandle();
        api.updateTextureFromMemory(atmosphericTextureUpdate);
    }

    private void publishCombinedSkyAmbient(
            RemixAPI api,
            float sunHeight,
            WeatherDomeState weather) {
        float multiplier = skyAmbientMultiplier(sunHeight);
        publishSkyAmbient(
                api,
                lerp(atmosphericMeanR, OVERCAST_MEAN_R * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanG, OVERCAST_MEAN_G * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanB, OVERCAST_MEAN_B * weather.intensity(), weather.opacity())
                        * multiplier);
    }

    /** Updates the sky ambient rect to this radiance and draws it; a black or disabled rect stays undrawn. */
    private void publishSkyAmbient(RemixAPI api, float radianceR, float radianceG, float radianceB) {
        if (!isSkyAmbientRectEnabled()
                || (radianceR == 0.0f && radianceG == 0.0f && radianceB == 0.0f)) {
            return;
        }

        LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                .position(0.0f, SKY_AMBIENT_RECT_Y, 0.0f)
                .xAxis(1, 0, 0)
                .xSize(SKY_AMBIENT_RECT_HALF_SIZE)
                .yAxis(0, 0, 1)
                .ySize(SKY_AMBIENT_RECT_HALF_SIZE)
                .direction(0, -1, 0)
                .volumetricRadianceScale(1.0f)
                .build();

        LightInfo ambientInfo = LightInfo.builder()
                .radiance(radianceR, radianceG, radianceB)
                .rectExt(rectExt)
                .build();

        if (skyAmbientRectLight != null && skyAmbientRectLight.isAlive()) {
            RemixLightStore.get().update(skyAmbientRectLight, ambientInfo);
        } else {
            destroyAsset(skyAmbientRectLight);
            skyAmbientRectLight = new RemixLight(ambientInfo, null);
            RemixLightStore.get().create(skyAmbientRectLight);
        }
        if (skyAmbientRectLight.isAlive()) {
            skyAmbientRectLight.draw(api);
        }
    }

    static float skyAmbientMultiplier(float sunHeight) {
        float nightFactor = 1.0f - SkyLightState.celestialVisibility(sunHeight);
        return getDayAmbientBoost() * (1.0f - nightFactor)
                + getNightAmbientBoost() * nightFactor;
    }

    private void renderCelestialMeshes(
            RemixAPI api,
            SkyFrame frame,
            float[] sunDirection,
            float weatherOpacity) {
        if (!ensureCelestialAssets()) {
            return;
        }

        float weatherVisibility = Math.max(
                getMinimumCelestialWeatherVisibility(), 1.0f - clamp01(weatherOpacity));
        float starsOpacity = starOpacity(frame.starBrightness) * weatherVisibility;
        if (starsOpacity > 0.0f) {
            updateStarOpacity(starsOpacity);
            updateStarTransform(
                    starTransform,
                    frame.cameraX,
                    frame.cameraY,
                    frame.cameraZ,
                    frame.skyAngle);
            drawCelestialMesh(api, starMeshAsset, starTransform);
        }

        celestialMoonDirection[0] = -sunDirection[0];
        celestialMoonDirection[1] = -sunDirection[1];
        celestialMoonDirection[2] = -sunDirection[2];
        float sunOpacity = celestialBodyOpacity(sunDirection[1]) * weatherVisibility;
        float moonOpacity = celestialBodyOpacity(celestialMoonDirection[1]) * weatherVisibility;
        updateCelestialOpacity(sunOpacity, moonOpacity);

        if (sunOpacity > 0.0f) {
            updateCelestialTransform(
                    sunTransform,
                    frame.cameraX,
                    frame.cameraY,
                    frame.cameraZ,
                    sunDirection,
                    SUN_HALF_SIZE);
            drawCelestialMesh(api, sunMeshAsset, sunTransform);
        }

        if (moonOpacity > 0.0f) {
            int moonPhase = frame.moonPhase & 7;
            updateCelestialTransform(
                    moonTransform,
                    frame.cameraX,
                    frame.cameraY,
                    frame.cameraZ,
                    celestialMoonDirection,
                    MOON_HALF_SIZE);
            drawCelestialMesh(api, moonMeshAssets[moonPhase], moonTransform);
        }
    }

    private boolean ensureCelestialAssets() {
        if (starMeshAsset != null && sunMeshAsset != null
                && moonMeshAssets[0] != null && celestialBlendPNext != 0) {
            return true;
        }
        if (celestialInitializationFailed) {
            return false;
        }

        destroyCelestialAssets();
        try {
            sunTextureAsset = loadCelestialTexture(resources.loadSunTexture());
            moonTextureAsset = loadCelestialTexture(resources.loadMoonTexture());
            sunMaterialAsset = createCelestialMaterial(sunTextureAsset, SUN_RADIANCE);
            moonMaterialAsset = createCelestialMaterial(moonTextureAsset, MOON_RADIANCE);
            starMaterialAsset = createStarMaterial();

            sunMeshAsset = createCelestialMesh(sunMaterialAsset, 0.0f, 0.0f, 1.0f, 1.0f);

            for (int phase = 0; phase < moonMeshAssets.length; phase++) {
                int column = phase % 4;
                int row = phase / 4;
                float minU = column / 4.0f;
                float minV = row / 2.0f;
                float maxU = (column + 1) / 4.0f;
                float maxV = (row + 1) / 2.0f;
                moonMeshAssets[phase] = createCelestialMesh(
                        moonMaterialAsset,
                        minU,
                        minV,
                        maxU,
                        maxV);
            }

            starMeshAsset = createStarMesh(starMaterialAsset, resources.copyStarVertices());
            celestialBlendPNext = CELESTIAL_BLEND.allocNative(0);
            lastSunOpacity = 1.0f;
            lastMoonOpacity = 1.0f;
            lastStarOpacity = 1.0f;
            return true;
        } catch (RuntimeException | IOException e) {
            LOGGER.error("Failed to create celestial SKY meshes", e);
            destroyCelestialAssets();
            celestialInitializationFailed = true;
            return false;
        }
    }

    private RemixTexture loadCelestialTexture(SkyResourceSource.Image image) {
        ByteBuffer pixelBuffer = DirectBufferPool.acquire(image.rgba.length);
        pixelBuffer.put(image.rgba).flip();
        RemixTexture texture = new RemixTexture(
                image.width,
                image.height,
                TextureFormat.RGBA8_UNORM,
                TexturePurpose.COLOR,
                pixelBuffer);
        return RemixTextureStore.get().create(texture);
    }

    private static RemixMaterial createCelestialMaterial(RemixTexture texture, float radiance) {
        MaterialInfo info = createCelestialMaterialInfo(texture, radiance, 1.0f);
        RemixMaterial material =
                new RemixMaterial(info, Collections.singletonList(texture), null);
        return RemixMaterialStore.get().create(material);
    }

    private static RemixMaterial createStarMaterial() {
        RemixMaterial material =
                new RemixMaterial(createStarMaterialInfo(1.0f), Collections.emptyList(), null);
        return RemixMaterialStore.get().create(material);
    }

    private static MaterialInfo createCelestialMaterialInfo(
            RemixTexture texture,
            float radiance,
            float opacity) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(opacity, opacity, opacity)
                .opacityConstant(opacity)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .useDrawCallAlphaState(true)
                .build();
        return MaterialInfo.builder()
                .albedoTexture(texture.getHandle())
                .emissiveTexture(texture.getHandle())
                .emissiveIntensity(radiance * opacity)
                .filterMode(0)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(opaque)
                .build();
    }

    private void updateCelestialOpacity(float sunOpacity, float moonOpacity) {
        RemixMaterialStore materials = RemixMaterialStore.get();
        if (Math.abs(sunOpacity - lastSunOpacity) > CELESTIAL_OPACITY_UPDATE_DELTA) {
            materials.update(
                    sunMaterialAsset,
                    createCelestialMaterialInfo(sunTextureAsset, SUN_RADIANCE, sunOpacity));
            lastSunOpacity = sunOpacity;
        }
        if (Math.abs(moonOpacity - lastMoonOpacity) > CELESTIAL_OPACITY_UPDATE_DELTA) {
            materials.update(
                    moonMaterialAsset,
                    createCelestialMaterialInfo(moonTextureAsset, MOON_RADIANCE, moonOpacity));
            lastMoonOpacity = moonOpacity;
        }
    }

    private void updateStarOpacity(float opacity) {
        if (Math.abs(opacity - lastStarOpacity) <= CELESTIAL_OPACITY_UPDATE_DELTA) {
            return;
        }
        RemixMaterialStore.get().update(starMaterialAsset, createStarMaterialInfo(opacity));
        lastStarOpacity = opacity;
    }

    private static MaterialInfo createStarMaterialInfo(float opacity) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(opacity, opacity, opacity)
                .opacityConstant(opacity)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .useDrawCallAlphaState(true)
                .build();
        return MaterialInfo.builder()
                .emissiveColorConstant(1.0f, 1.0f, 1.0f)
                .emissiveIntensity(STAR_RADIANCE * opacity)
                .opaqueExt(opaque)
                .build();
    }

    private static RemixMesh createCelestialMesh(
            RemixMaterial material,
            float minU,
            float minV,
            float maxU,
            float maxV) {
        HardcodedVertex[] vertices = {
                HardcodedVertex.create(-1, -1, 0, 0, 0, -1, minU, maxV, 0xFFFFFFFF),
                HardcodedVertex.create(1, -1, 0, 0, 0, -1, maxU, maxV, 0xFFFFFFFF),
                HardcodedVertex.create(1, 1, 0, 0, 0, -1, maxU, minV, 0xFFFFFFFF),
                HardcodedVertex.create(-1, 1, 0, 0, 0, -1, minU, minV, 0xFFFFFFFF)
        };
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertices(vertices)
                .setIndices(new int[]{0, 1, 2, 0, 2, 3});
        RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
        return RemixMeshStore.get().create(mesh);
    }

    private static RemixMesh createStarMesh(RemixMaterial material, float[] positions) {
        int vertexCount = positions.length / 3;
        if (vertexCount == 0 || vertexCount % 4 != 0) {
            throw new IllegalStateException("Minecraft star buffer must contain position-only quads");
        }

        HardcodedVertex[] vertices = new HardcodedVertex[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int position = vertex * 3;
            vertices[vertex] = HardcodedVertex.create(
                    positions[position],
                    positions[position + 1],
                    positions[position + 2]);
        }

        int[] indices = new int[vertexCount / 4 * 6];
        for (int quad = 0; quad < vertexCount / 4; quad++) {
            int vertex = quad * 4;
            int index = quad * 6;
            indices[index] = vertex;
            indices[index + 1] = vertex + 1;
            indices[index + 2] = vertex + 2;
            indices[index + 3] = vertex;
            indices[index + 4] = vertex + 2;
            indices[index + 5] = vertex + 3;
        }

        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertices(vertices)
                .setIndices(indices);
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[]{surface}));
    }

    private void drawCelestialMesh(RemixAPI api, RemixMesh mesh, Transform transform) {
        api.drawInstance(
                mesh.getHandle(),
                transform.matrix,
                InstanceCategoryBit.SKY,
                1,
                celestialBlendPNext);
    }

    private static InstanceInfoBlendEXT createCelestialBlend() {
        InstanceInfoBlendEXT blend = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(1);
        blend.alphaBlendEnabled = 1;
        blend.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        blend.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        blend.srcAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;
        blend.dstAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        return blend;
    }

    private static void updateCelestialTransform(
            Transform transform,
            double cameraX,
            double cameraY,
            double cameraZ,
            float[] direction,
            float halfSize) {
        float dx = direction[0];
        float dy = direction[1];
        float dz = direction[2];
        float inverseDirectionLength = inverseLength(dx, dy, dz);
        dx *= inverseDirectionLength;
        dy *= inverseDirectionLength;
        dz *= inverseDirectionLength;

        float rx = dz;
        float ry = 0.0f;
        float rz = -dx;
        float inverseRightLength = inverseLength(rx, ry, rz);
        rx *= inverseRightLength;
        rz *= inverseRightLength;

        float ux = dy * rz - dz * ry;
        float uy = dz * rx - dx * rz;
        float uz = dx * ry - dy * rx;

        transform.matrix[0] = rx * halfSize;
        transform.matrix[1] = ux * halfSize;
        transform.matrix[2] = dx;
        transform.matrix[3] = (float) cameraX + dx * CELESTIAL_DISTANCE;
        transform.matrix[4] = ry * halfSize;
        transform.matrix[5] = uy * halfSize;
        transform.matrix[6] = dy;
        transform.matrix[7] = (float) cameraY + dy * CELESTIAL_DISTANCE;
        transform.matrix[8] = rz * halfSize;
        transform.matrix[9] = uz * halfSize;
        transform.matrix[10] = dz;
        transform.matrix[11] = (float) cameraZ + dz * CELESTIAL_DISTANCE;
    }

    private static void updateStarTransform(
            Transform transform,
            double cameraX,
            double cameraY,
            double cameraZ,
            float skyAngle) {
        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.toRadians(SUN_PATH_ROTATION_DEG))
                .rotateY(-(float) Math.PI / 2.0f)
                .rotateX(skyAngle * (float) (Math.PI * 2.0));
        Transform rotated = Transform.fromTRS(
                (float) cameraX,
                (float) cameraY,
                (float) cameraZ,
                rotation,
                STAR_DISTANCE_SCALE);
        System.arraycopy(rotated.matrix, 0, transform.matrix, 0, transform.matrix.length);
    }

    private static float inverseLength(float x, float y, float z) {
        return 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
    }

    public void reloadResources() {
        destroyCelestialAssets();
        celestialInitializationFailed = false;
    }

    private void destroyCelestialAssets() {
        CELESTIAL_BLEND.freeNative();
        celestialBlendPNext = 0;
        destroyMeshes();
        destroyAsset(sunMaterialAsset);
        destroyAsset(moonMaterialAsset);
        destroyAsset(starMaterialAsset);
        destroyAsset(sunTextureAsset);
        destroyAsset(moonTextureAsset);
        sunMaterialAsset = null;
        moonMaterialAsset = null;
        starMaterialAsset = null;
        sunTextureAsset = null;
        moonTextureAsset = null;
        lastSunOpacity = -1.0f;
        lastMoonOpacity = -1.0f;
        lastStarOpacity = -1.0f;
    }

    private void destroyMeshes() {
        destroyAsset(sunMeshAsset);
        sunMeshAsset = null;
        for (int i = 0; i < moonMeshAssets.length; i++) {
            destroyAsset(moonMeshAssets[i]);
            moonMeshAssets[i] = null;
        }
        destroyAsset(starMeshAsset);
        starMeshAsset = null;
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) {
            asset.destroy();
        }
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    static final class WeatherDomeState {
        private static final WeatherDomeState CLEAR = new WeatherDomeState(0.0f, 0.0f);

        private final float opacity;
        private final float intensity;

        WeatherDomeState(float opacity, float intensity) {
            this.opacity = opacity;
            this.intensity = intensity;
        }

        float opacity() { return opacity; }
        float intensity() { return intensity; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WeatherDomeState)) return false;
            WeatherDomeState state = (WeatherDomeState) other;
            return Float.compare(opacity, state.opacity) == 0
                    && Float.compare(intensity, state.intensity) == 0;
        }

        @Override
        public int hashCode() {
            int result = Float.floatToIntBits(opacity);
            return 31 * result + Float.floatToIntBits(intensity);
        }
    }

    static WeatherDomeState weatherDomeState(
            float rainStrength,
            float thunderStrength,
            float rainIntensity,
            float thunderIntensity) {
        float rain = clamp01(rainStrength);
        float thunder = clamp01(thunderStrength);
        float opacity = lerp(rain, 1.0f, thunder);
        float premultipliedIntensity = lerp(
                rain * rainIntensity,
                thunderIntensity,
                thunder);
        float intensity = opacity > 0.0f ? premultipliedIntensity / opacity : 0.0f;
        return new WeatherDomeState(opacity, intensity);
    }

    static float celestialBodyOpacity(float verticalDirection) {
        return smoothstep(CELESTIAL_HORIZON_FADE_MIN_HEIGHT, 0.0f, verticalDirection);
    }

    static float starOpacity(float vanillaStarBrightness) {
        return clamp01(vanillaStarBrightness);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * clamp01(amount);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    // ====================================================================
    //  Sun direction (shared with LightManager)
    // ====================================================================

    /**
     * Computes the sun's position direction from Minecraft's {@code skyAngle},
     * with the standard shader-pack tilt applied.
     *
     * @param skyAngle {@code world.getSkyAngle(tickDelta)}: 0.0 at noon, 0.25 at sunset, etc.
     * @return float[3] {x, y, z} normalised sun position direction
     */
    public static float[] computeSunDirection(float skyAngle) {
        return SkyLightState.computeSunDirection(skyAngle);
    }

    // ====================================================================
    //  Cleanup
    // ====================================================================

    /** Releases world-scoped Remix assets while preserving reusable atmospheric GL state. */
    public void cleanup() {
        destroyCelestialAssets();
        releaseSkyLights();
        currentWorld = null;

        if (overcastTextureHandle != 0) {
            try {
                api.destroyTexture(overcastTextureHandle);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to destroy overcast dome texture: {}", e.getMessage());
            }
        }
        overcastTextureHandle = 0;
        overcastInitializationFailed = false;
        celestialInitializationFailed = false;
    }

    /**
     * Releases the dome, overcast, and ambient lights with the sky texture they read, so the
     * next frame publishes them from scratch for whatever sky the current world has.
     */
    private void releaseSkyLights() {
        destroyAsset(skyDomeLightAsset);
        skyDomeLightAsset = null;
        destroyAsset(skyTextureAsset);
        skyTextureAsset = null;
        atmosphericTexturePatch.pixelData = null;
        destroyAsset(overcastDomeLightAsset);
        overcastDomeLightAsset = null;
        destroyAsset(skyAmbientRectLight);
        skyAmbientRectLight = null;
        lastWeatherOpacity = -1f;
        lastWeatherIntensity = -1f;
        lastSkyDomeMultiplier = Float.NaN;
        atmosphericMeanR = 0.0f;
        atmosphericMeanG = 0.0f;
        atmosphericMeanB = 0.0f;
    }

    /** Releases world assets and process-lifetime atmospheric GL resources. */
    public void shutdown() {
        try {
            cleanup();
        } finally {
            atmosphericGenerator.destroy();
        }
    }
}
