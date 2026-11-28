/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Startup information for initializing Remix.
 */
public class StartupInfo {
    public long hwnd;
    public boolean disableSrgbConversionForOutput;
    public boolean forceNoVkSwapchain;
    public boolean editorModeEnabled;
    /** If true, GUI is composited into FINAL_COLOR output (useful for debugging) */
    public boolean combineGuiInFinalColor;
    public boolean interopImage;
    public boolean useRemixOwnedChildWindow;

    public StartupInfo() {}

    public static class Builder {
        private long hwnd;
        private boolean disableSrgbConversionForOutput;
        private boolean forceNoVkSwapchain;
        private boolean editorModeEnabled;
        private boolean combineGuiInFinalColor;
        private boolean interopImage = false;
        private boolean useRemixOwnedChildWindow;

        public Builder hwnd(long hwnd) {
            this.hwnd = hwnd;
            return this;
        }

        public Builder disableSrgbConversionForOutput(boolean value) {
            this.disableSrgbConversionForOutput = value;
            return this;
        }

        public Builder forceNoVkSwapchain(boolean value) {
            this.forceNoVkSwapchain = value;
            return this;
        }

        public Builder editorModeEnabled(boolean value) {
            this.editorModeEnabled = value;
            return this;
        }

        public Builder combineGuiInFinalColor(boolean value) {
            this.combineGuiInFinalColor = value;
            return this;
        }

        public Builder interopImage(boolean value) {
            this.interopImage = value;
            return this;
        }

        public Builder useRemixOwnedChildWindow(boolean value) {
            this.useRemixOwnedChildWindow = value;
            return this;
        }

        public StartupInfo build() {
            StartupInfo info = new StartupInfo();
            info.hwnd = this.hwnd;
            info.disableSrgbConversionForOutput = this.disableSrgbConversionForOutput;
            info.forceNoVkSwapchain = this.forceNoVkSwapchain;
            info.editorModeEnabled = this.editorModeEnabled;
            info.combineGuiInFinalColor = this.combineGuiInFinalColor;
            info.interopImage = this.interopImage;
            info.useRemixOwnedChildWindow = this.useRemixOwnedChildWindow;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
