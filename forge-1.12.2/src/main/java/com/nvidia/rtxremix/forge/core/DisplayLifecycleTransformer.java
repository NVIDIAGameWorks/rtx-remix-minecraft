/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Releases GL imports before the window, then shuts Remix down after it. */
public final class DisplayLifecycleTransformer implements IClassTransformer {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String LIFECYCLE = "com/nvidia/rtxremix/forge/client/RendererLifecycle";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !MINECRAFT.equals(transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassNode minecraft = new ClassNode(Opcodes.ASM5);
        reader.accept(minecraft, 0);
        boolean changed = false;
        for (MethodNode method : minecraft.methods) {
            // Minecraft 1.12.2 destroys Display on both its normal and exceptional exit paths.
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/lwjgl/opengl/Display".equals(call.owner)
                        && "destroy".equals(call.name) && "()V".equals(call.desc)) {
                    method.instructions.insertBefore(call, hook("beforeDisplayDestroy"));
                    method.instructions.insert(call, hook("afterDisplayDestroy"));
                    changed = true;
                }
            }
        }
        if (!changed) {
            return basicClass;
        }

        // The void static hooks leave the stack unchanged. Retain the existing frames;
        // computing new ones would resolve game classes while LaunchWrapper loads them.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        minecraft.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode hook(String method) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, LIFECYCLE, method, "()V", false);
    }
}
