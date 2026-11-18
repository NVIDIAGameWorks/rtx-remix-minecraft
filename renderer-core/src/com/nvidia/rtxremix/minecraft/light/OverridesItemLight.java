/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

/**
 * Marker for blocks that take charge of their own item light.
 *
 * <p>{@link LightManager} normally gives any light-emitting {@code BlockItem} an
 * entity light while it is held or lying on the ground, derived either from a
 * matching {@link BlockLightRuleManager} rule or, failing that, from the block's
 * luminance. That light is a plain omnidirectional sphere. Blocks implementing
 * this interface are skipped by both paths, and are responsible for whatever
 * light they should emit as an item.
 *
 * <p>"Responsible for" includes deciding on none. {@link SpotLightBlock} is the
 * only implementer, and does both: held, it supplies its own aimed beam at
 * a radiance scaled for arm's length; dropped, it deliberately stays dark, an
 * item entity having no aim worth pointing a beam along.
 *
 * <p>The generic path is not merely redundant for such blocks but actively
 * wrong, which is the reason to opt out rather than let it run alongside. It
 * carries no cone: a beacon resolved through it would keep the rule's
 * world-scale radiance and lose the shaping that contains it, turning a
 * lighthouse in your hand into a floodlight in every direction.
 */
public interface OverridesItemLight {
}
