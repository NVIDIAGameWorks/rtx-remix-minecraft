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
import org.objectweb.asm.tree.VarInsnNode;

/** Keeps vanilla's frame loop and fullscreen bookkeeping while Remix owns the swapchain. */
public final class PresentationTransformer implements IClassTransformer {
    private static final String PRESENTATION = "com/nvidia/rtxremix/forge/presentation/";

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraft.client.Minecraft".equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassNode type = new ClassNode(Opcodes.ASM5);
        reader.accept(type, 0);
        for (MethodNode method : type.methods) {
            if (!"()V".equals(method.desc)) continue;
            if (named(method.name, "updateDisplay", "func_175601_h")) display(method);
            if (named(method.name, "toggleFullscreen", "func_71352_k")
                    || named(method.name, "updateDisplayMode", "func_110441_Q")) fullscreen(method);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static void display(MethodNode method) {
        int sites = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(PRESENTATION + "PresentationHooks")) return;
            if (call.getOpcode() == Opcodes.INVOKESTATIC && "org/lwjgl/opengl/Display".equals(call.owner)
                    && "update".equals(call.name) && "()V".equals(call.desc)) {
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        PRESENTATION + "PresentationHooks", "updateDisplay", "()V", false));
                sites++;
            }
        }
        if (sites != 1) throw new IllegalStateException("Expected one Display.update call, found " + sites);
    }

    private static void fullscreen(MethodNode method) {
        String owner = PRESENTATION + "FullscreenPresentation";
        int modes = 0, toggles = 0, resize = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner.equals(call.owner)) return;
            if (call.getOpcode() != Opcodes.INVOKESTATIC || !"org/lwjgl/opengl/Display".equals(call.owner)) continue;
            if ("setDisplayMode".equals(call.name) && "(Lorg/lwjgl/opengl/DisplayMode;)V".equals(call.desc)) {
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, call.name,
                        "(Lorg/lwjgl/opengl/DisplayMode;Lnet/minecraft/client/Minecraft;)V", false));
                modes++;
            } else if ("(Z)V".equals(call.desc) && ("setFullscreen".equals(call.name) || "setResizable".equals(call.name))) {
                if ("setFullscreen".equals(call.name)) toggles++; else resize++;
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, owner, call.name, call.desc, false));
            }
        }
        boolean toggle = named(method.name, "toggleFullscreen", "func_71352_k");
        if (modes != 1 || toggles != (toggle ? 1 : 0) || resize != (toggle ? 2 : 0)) {
            throw new IllegalStateException("Unexpected fullscreen display calls in " + method.name);
        }
    }

    private static boolean named(String value, String named, String srg) {
        return named.equals(value) || srg.equals(value);
    }
}