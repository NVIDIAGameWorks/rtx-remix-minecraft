/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.joml.Quaternionf;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Executable Java 8 checks for layouts shared with the native API. */
public final class BridgeContractTest {
    public static void main(String[] args) throws Exception {
        String header = new String(Files.readAllBytes(
                Paths.get(args[0], "native", "include", "remix_c.h")), StandardCharsets.UTF_8);
        verifyHeaderConstants(header);
        verifySharedSources(Paths.get(args[0]), Paths.get(args[1]));
        verifyVertexLayout();
        verifyTransforms();
        System.out.println("PASS: shared-source boundary, header/version agreement, vertex/transform layouts");
    }

    private static void verifySharedSources(Path shared, Path adapter) throws Exception {
        Path sources = shared.resolve("src");
        Path adapterSources = adapter.resolve("src/main/java");
        check(Files.isRegularFile(sources.resolve("com/nvidia/rtxremix/RemixAPI.java")), "Shared Java facade is missing");
        check(!Files.exists(adapter.resolve("native/remixbridge.cpp")), "Forge must compile the shared JNI implementation");
        check(!Files.exists(adapter.resolve("native/include/remix_c.h")), "Forge must compile against the shared C API header");
        try (Stream<Path> paths = Files.walk(adapterSources)) {
            Iterator<Path> files = paths.filter(path -> path.toString().endsWith(".java")).iterator();
            while (files.hasNext()) {
                Path relative = adapterSources.relativize(files.next());
                check(!Files.exists(sources.resolve(relative)), "Duplicated Java facade: " + relative);
            }
        }
    }

    private static void verifyHeaderConstants(String header) throws Exception {
        for (Field field : StructType.class.getFields()) {
            Matcher value = Pattern.compile("REMIXAPI_STRUCT_TYPE_" + field.getName()
                    + "\\s*=\\s*(\\d+)").matcher(header);
            check(value.find(), "Missing native struct constant " + field.getName());
            check(field.getInt(null) == Integer.parseInt(value.group(1)),
                    "Mismatched native struct constant " + field.getName());
        }
        String[] versionFields = {"MAJOR", "MINOR", "PATCH"};
        for (String part : versionFields) {
            Matcher value = Pattern.compile("#define REMIXAPI_VERSION_" + part + "\\s+(\\d+)").matcher(header);
            check(value.find(), "Missing native API version " + part);
            check(RemixAPI.class.getField("VERSION_" + part).getInt(null)
                    == Integer.parseInt(value.group(1)), "Java/native API version mismatch: " + part);
        }
    }

    private static void verifyVertexLayout() {
        HardcodedVertex vertex = HardcodedVertex.create(1, 2, 3, 0, 1, 0, 0.25f, 0.75f, 0xFF123456);
        vertex.setTexcoordBounds(0, 0, 1, 1);
        ByteBuffer buffer = ByteBuffer.allocateDirect(HardcodedVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        vertex.writeTo(buffer);
        check(buffer.position() == 64, "Vertex size must be 64 bytes");
        check(buffer.getFloat(0) == 1 && buffer.getFloat(8) == 3, "Vertex position offset");
        check(buffer.getFloat(16) == 1, "Vertex normal offset");
        check(buffer.getFloat(24) == 0.25f && buffer.getFloat(28) == 0.75f, "Vertex UV offset");
        check(buffer.getInt(32) == 0xFF123456, "Vertex color offset");
        check(buffer.getInt(36) == 0 && buffer.getInt(40) == 0x3C003C00, "Packed half-float UV bounds");
    }

    private static void verifyTransforms() {
        Transform transform = Transform.fromTRS(3, 4, 5, new Quaternionf(0, 0, 0, 1), 2);
        check(transform.get(0, 0) == 2 && transform.get(1, 1) == 2 && transform.get(2, 2) == 2,
                "Identity quaternion and uniform scale");
        check(transform.get(0, 3) == 3 && transform.get(1, 3) == 4 && transform.get(2, 3) == 5,
                "Translation must occupy fourth column");
        Transform halfTurn = Transform.fromTRS(0, 0, 0, new Quaternionf(0, 0, 1, 0), 1);
        check(halfTurn.get(0, 0) == -1 && halfTurn.get(1, 1) == -1 && halfTurn.get(2, 2) == 1,
                "Quaternion half-turn about Z");
        InstanceInfoBoneTransformsEXT bones = new InstanceInfoBoneTransformsEXT();
        bones.setBoneTransforms(new Transform[] {transform, halfTurn});
        check(bones.transformData.isDirect() && bones.transformData.order() == ByteOrder.LITTLE_ENDIAN,
                "Native bone transforms require direct little-endian data");
        check(bones.transformData.position() == 0 && bones.transformData.limit() == 96, "Bone buffer span");
        check(bones.transformData.getFloat(12) == 3 && bones.transformData.getFloat(48) == -1,
                "Bone buffer row-major order and 48-byte stride");
    }

    static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
