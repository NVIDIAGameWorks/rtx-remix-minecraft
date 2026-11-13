/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Shared offline validation for loader-specific production mixin classes. */
public final class MixinContractAssertions {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final Set<String> INJECTIONS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

    private MixinContractAssertions() {
    }

    public static void assertRequiredSelectorsResolve(
            Class<?> testClass, String loaderName, String... mixinConfigs) throws Exception {
        ClassLoader loader = testClass.getClassLoader();
        List<String> failures = new ArrayList<>();
        for (String mixinName : configuredMixins(loader, mixinConfigs)) {
            ClassNode mixin = readClass(loader, mixinName.replace('.', '/') + ".class");
            AnnotationNode mixinAnnotation = annotation(mixin.visibleAnnotations, MIXIN);
            if (mixinAnnotation == null) {
                mixinAnnotation = annotation(mixin.invisibleAnnotations, MIXIN);
            }
            assertNotNull(mixinAnnotation, mixinName);
            for (Type targetType : values(mixinAnnotation, "value", Type.class)) {
                ClassNode target = readClass(loader, targetType.getInternalName() + ".class");
                validateFields(mixinName, mixin, target, failures);
                validateMethods(mixinName, mixin, target, failures);
            }
        }
        assertEquals(List.of(), failures,
                loaderName + " required mixin selectors must resolve before a live launch");
    }

    private static void validateFields(
            String mixinName, ClassNode mixin, ClassNode target, List<String> failures) {
        for (FieldNode field : mixin.fields) {
            if ((annotation(field.visibleAnnotations, SHADOW) != null
                    || annotation(field.invisibleAnnotations, SHADOW) != null)
                    && target.fields.stream().noneMatch(candidate ->
                            candidate.name.equals(field.name)
                                    && candidate.desc.equals(field.desc))) {
                failures.add(mixinName + " shadows missing field "
                        + target.name + "." + field.name + ":" + field.desc);
            }
        }
    }

    private static void validateMethods(
            String mixinName, ClassNode mixin, ClassNode target, List<String> failures) {
        for (MethodNode method : mixin.methods) {
            AnnotationNode shadow = firstAnnotation(method, SHADOW);
            if (shadow != null && target.methods.stream().noneMatch(candidate ->
                    candidate.name.equals(method.name)
                            && candidate.desc.equals(method.desc))) {
                failures.add(mixinName + " shadows missing method "
                        + target.name + "." + method.name + method.desc);
            }

            AnnotationNode accessor = firstAnnotation(method, ACCESSOR);
            String fieldName = accessor == null ? null : stringValue(accessor, "value");
            if (fieldName != null && target.fields.stream().noneMatch(candidate ->
                    candidate.name.equals(fieldName))) {
                failures.add(mixinName + " accesses missing field "
                        + target.name + "." + fieldName);
            }

            AnnotationNode invoker = firstAnnotation(method, INVOKER);
            String targetName = invoker == null ? null : stringValue(invoker, "value");
            if (targetName != null && target.methods.stream().noneMatch(candidate ->
                    candidate.name.equals(targetName)
                            && candidate.desc.equals(method.desc))) {
                failures.add(mixinName + " invokes missing method "
                        + target.name + "." + targetName + method.desc);
            }

            for (AnnotationNode injection : annotations(method)) {
                if (!INJECTIONS.contains(injection.desc)
                        || intValue(injection, "require", 1) == 0) {
                    continue;
                }
                List<String> selectors = stringValues(injection, "method");
                List<MethodNode> selected = selectMethods(target, selectors);
                if (selected.isEmpty()) {
                    failures.add(mixinName + " selector " + selectors
                            + " matches no method in " + target.name);
                    continue;
                }
                for (AnnotationNode at : annotationValues(injection, "at")) {
                    String instructionTarget = stringValue(at, "target");
                    if (instructionTarget != null
                            && instructionTarget.startsWith("L")
                            && selected.stream().noneMatch(candidate ->
                                    containsInstruction(candidate, instructionTarget))) {
                        failures.add(mixinName + " @At target " + instructionTarget
                                + " is absent from " + target.name + " " + selectors
                                + "; nearby calls: " + nearbyCalls(selected, instructionTarget));
                    }
                }
            }
        }
    }

    private static AnnotationNode firstAnnotation(MethodNode method, String descriptor) {
        AnnotationNode result = annotation(method.visibleAnnotations, descriptor);
        return result != null ? result : annotation(method.invisibleAnnotations, descriptor);
    }

    private static List<MethodNode> selectMethods(ClassNode target, List<String> selectors) {
        List<MethodNode> selected = new ArrayList<>();
        for (MethodNode method : target.methods) {
            for (String selector : selectors) {
                int descriptor = selector.indexOf('(');
                String name = descriptor < 0 ? selector : selector.substring(0, descriptor);
                if (method.name.equals(name)
                        && (descriptor < 0 || method.desc.equals(selector.substring(descriptor)))) {
                    selected.add(method);
                    break;
                }
            }
        }
        return selected;
    }

    private static boolean containsInstruction(MethodNode method, String target) {
        for (AbstractInsnNode instruction : method.instructions) {
            String selector = switch (instruction) {
                case MethodInsnNode call -> "L" + call.owner + ";" + call.name + call.desc;
                case FieldInsnNode field ->
                        "L" + field.owner + ";" + field.name + ":" + field.desc;
                case TypeInsnNode type -> "L" + type.desc + ";";
                default -> null;
            };
            if (target.equals(selector)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> nearbyCalls(List<MethodNode> methods, String expected) {
        int ownerEnd = expected.indexOf(';');
        String owner = ownerEnd > 1 ? expected.substring(1, ownerEnd) : "";
        List<String> calls = new ArrayList<>();
        for (MethodNode method : methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)) {
                    calls.add("L" + call.owner + ";" + call.name + call.desc);
                }
            }
        }
        return calls.stream().distinct().toList();
    }

    private static Set<String> configuredMixins(
            ClassLoader loader, String... resources) throws IOException {
        Set<String> names = new HashSet<>();
        for (String resource : resources) {
            try (InputStream input = resource(loader, resource);
                 InputStreamReader reader =
                         new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
                String packageName = config.get("package").getAsString();
                for (String listName : List.of("mixins", "client", "server")) {
                    JsonArray mixins = config.getAsJsonArray(listName);
                    if (mixins != null) {
                        mixins.forEach(element ->
                                names.add(packageName + "." + element.getAsString()));
                    }
                }
            }
        }
        return names;
    }

    private static ClassNode readClass(ClassLoader loader, String resource) throws IOException {
        try (InputStream input = resource(loader, resource)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static InputStream resource(ClassLoader loader, String name) {
        InputStream input = loader.getResourceAsStream(name);
        assertNotNull(input, "missing production class/resource " + name);
        return input;
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            result.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            result.addAll(method.invisibleAnnotations);
        }
        return result;
    }

    private static AnnotationNode annotation(
            List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream()
                .filter(candidate -> candidate.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static String stringValue(AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        return value instanceof String string ? string : null;
    }

    private static int intValue(AnnotationNode annotation, String key, int fallback) {
        Object value = value(annotation, key);
        return value instanceof Integer integer ? integer : fallback;
    }

    private static List<String> stringValues(AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        if (value instanceof String string) {
            return List.of(string);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String.class::cast).toList();
        }
        return List.of();
    }

    private static List<AnnotationNode> annotationValues(
            AnnotationNode annotation, String key) {
        Object value = value(annotation, key);
        if (value instanceof AnnotationNode node) {
            return List.of(node);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AnnotationNode.class::cast).toList();
        }
        return List.of();
    }

    private static <T> List<T> values(
            AnnotationNode annotation, String key, Class<T> type) {
        Object value = value(annotation, key);
        if (value instanceof List<?> list) {
            return list.stream().map(type::cast).toList();
        }
        return value == null ? List.of() : List.of(type.cast(value));
    }
}
