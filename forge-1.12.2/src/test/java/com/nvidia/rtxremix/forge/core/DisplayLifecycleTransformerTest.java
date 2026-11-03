/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.*;

public class DisplayLifecycleTransformerTest {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String LIFECYCLE = "com/nvidia/rtxremix/forge/client/RendererLifecycle";
    private final DisplayLifecycleTransformer transformer = new DisplayLifecycleTransformer();

    @Test
    public void bracketsBothActualMinecraftShutdownPathsWithoutChangingFrames() throws IOException {
        // Read the pinned mapped game class as bytes; do not initialize Minecraft or LWJGL.
        byte[] original = minecraftBytes();
        ClassNode before = read(original);
        ClassNode after = read(transformer.transform("bib", MINECRAFT, original));
        assertEquals(before.methods.size(), after.methods.size());
        assertEquals(before.fields.size(), after.fields.size());

        int destroyCalls = 0;
        int hookCalls = 0;
        for (int index = 0; index < before.methods.size(); index++) {
            MethodNode oldMethod = before.methods.get(index);
            MethodNode newMethod = after.methods.get(index);
            assertEquals(oldMethod.name, newMethod.name);
            assertEquals(oldMethod.desc, newMethod.desc);
            assertEquals(oldMethod.maxLocals, newMethod.maxLocals);
            assertEquals(oldMethod.tryCatchBlocks.size(), newMethod.tryCatchBlocks.size());
            assertEquals(frameCount(oldMethod), frameCount(newMethod));

            int methodDestroyCalls = 0;
            for (AbstractInsnNode instruction : newMethod.instructions.toArray()) {
                if (isDestroy(instruction)) {
                    // The normal exit and the catch-all cleanup both live in this method.
                    assertEquals("shutdownMinecraftApplet", newMethod.name);
                    assertEquals("()V", newMethod.desc);
                    assertHook(instruction.getPrevious(), "beforeDisplayDestroy");
                    assertHook(instruction.getNext(), "afterDisplayDestroy");
                    methodDestroyCalls++;
                }
                if (isLifecycleCall(instruction)) {
                    hookCalls++;
                }
            }
            assertEquals(oldMethod.instructions.size() + 2 * methodDestroyCalls,
                    newMethod.instructions.size());
            destroyCalls += methodDestroyCalls;
        }
        assertEquals("Pinned Minecraft must exercise normal and exceptional cleanup", 2, destroyCalls);
        assertEquals(2 * destroyCalls, hookCalls);
    }

    @Test
    public void matchesOnlyExactStaticDisplayDestroyInvocation() {
        ClassNode transformed = read(transformer.transform("bib", MINECRAFT, invocationFixture()));
        int destroyCalls = 0;
        int hookCalls = 0;
        for (AbstractInsnNode instruction : transformed.methods.get(0).instructions.toArray()) {
            if (isDestroy(instruction)) {
                assertHook(instruction.getPrevious(), "beforeDisplayDestroy");
                assertHook(instruction.getNext(), "afterDisplayDestroy");
                destroyCalls++;
            }
            if (isLifecycleCall(instruction)) {
                hookCalls++;
            }
        }
        assertEquals(1, destroyCalls);
        assertEquals("Other owners, names, descriptors and invocation kinds must be untouched", 2, hookCalls);
    }

    @Test
    public void leavesOtherClassesAndMissingBytecodeUntouched() {
        byte[] original = invocationFixture();
        assertSame(original, transformer.transform(MINECRAFT, "another.Class", original));
        assertSame(original, transformer.transform(MINECRAFT, null, original));
        assertNull(transformer.transform("bib", MINECRAFT, null));
    }

    private static byte[] minecraftBytes() throws IOException {
        try (InputStream input = DisplayLifecycleTransformerTest.class.getClassLoader()
                .getResourceAsStream("net/minecraft/client/Minecraft.class")) {
            assertNotNull("Mapped Forge Minecraft dependency must be on the test classpath", input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static int frameCount(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) {
                count++;
            }
        }
        return count;
    }

    private static boolean isDestroy(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && DISPLAY.equals(call.owner)
                && "destroy".equals(call.name) && "()V".equals(call.desc);
    }

    private static boolean isLifecycleCall(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode
                && LIFECYCLE.equals(((MethodInsnNode) instruction).owner);
    }

    private static void assertHook(AbstractInsnNode instruction, String name) {
        assertTrue(instruction instanceof MethodInsnNode);
        MethodInsnNode call = (MethodInsnNode) instruction;
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LIFECYCLE, call.owner);
        assertEquals(name, call.name);
        assertEquals("()V", call.desc);
        assertFalse(call.itf);
    }

    private static byte[] invocationFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "fixture", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, DISPLAY, "destroy", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "another/Display", "destroy", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, DISPLAY, "update", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, DISPLAY, "destroy", "()I", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DISPLAY, "destroy", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
