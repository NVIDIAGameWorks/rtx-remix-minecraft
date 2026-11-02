/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class PresentationTransformerTest {
    @Test public void patchesActualMinecraftPresentationAndFullscreenSitesOnce() throws Exception {
        byte[] original;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("net/minecraft/client/Minecraft.class");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            original = output.toByteArray();
        }
        PresentationTransformer transformer = new PresentationTransformer();
        byte[] transformed = transformer.transform("bib", "net.minecraft.client.Minecraft", original);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        new ClassReader(transformed).accept(type, 0);
        Map<String, Integer> hooks = new HashMap<>();
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.startsWith("com/nvidia/rtxremix/forge/presentation/")) {
                    hooks.put(call.name, hooks.getOrDefault(call.name, 0) + 1);
                }
            }
        }
        assertEquals(Integer.valueOf(1), hooks.get("updateDisplay"));
        assertEquals(Integer.valueOf(2), hooks.get("setDisplayMode"));
        assertEquals(Integer.valueOf(1), hooks.get("setFullscreen"));
        assertEquals(Integer.valueOf(2), hooks.get("setResizable"));
        assertEquals(4, hooks.size());
        assertArrayEquals(transformed, transformer.transform("bib", "net.minecraft.client.Minecraft", transformed));
        assertSame(original, transformer.transform("other", "other", original));
        assertNull(transformer.transform("bib", "net.minecraft.client.Minecraft", null));
    }
}