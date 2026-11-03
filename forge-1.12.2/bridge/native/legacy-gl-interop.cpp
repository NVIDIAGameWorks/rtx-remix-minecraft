// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <gl/GL.h>
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include "com_nvidia_rtxremix_forge_interop_LegacyGlInterop.h"


#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "user32.lib")

namespace {
constexpr GLenum READ_FRAMEBUFFER = 0x8CA8;
constexpr GLenum DRAW_FRAMEBUFFER = 0x8CA9;
constexpr GLenum READ_FRAMEBUFFER_BINDING = 0x8CAA;
constexpr GLenum DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
constexpr GLenum COLOR_ATTACHMENT0 = 0x8CE0;
constexpr GLenum FRAMEBUFFER_COMPLETE = 0x8CD5;
constexpr GLenum FRAMEBUFFER_SRGB = 0x8DB9;
constexpr GLenum HANDLE_OPAQUE_WIN32 = 0x9587;
constexpr GLenum LAYOUT_GENERAL = 0x958D;
constexpr GLenum SEMAPHORE_TYPE = 0x95B3;
constexpr GLint SEMAPHORE_TIMELINE = 0x95B5;
constexpr GLenum TIMELINE_VALUE = 0x9595;
constexpr GLenum SRGB8_ALPHA8 = 0x8C43;
constexpr GLenum R32F = 0x822E;
constexpr GLint CLAMP_TO_EDGE = 0x812F;
using UInt64 = std::uint64_t;

template<typename T> T loadProc(const char* name) {
    PROC value = wglGetProcAddress(name);
    const uintptr_t address = reinterpret_cast<uintptr_t>(value);
    if (!value || address <= 3 || address == static_cast<uintptr_t>(-1)) {
        throw std::runtime_error(std::string("Required OpenGL function unavailable: ") + name);
    }
    return reinterpret_cast<T>(value);
}

void checkGl(const char* operation) {
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        char message[192];
        sprintf_s(message, "OpenGL error 0x%04x during %s", error, operation);
        throw std::runtime_error(message);
    }
}

struct GlFunctions {
    void (APIENTRY* createMemory)(GLsizei, GLuint*) = loadProc<decltype(createMemory)>("glCreateMemoryObjectsEXT");
    void (APIENTRY* deleteMemory)(GLsizei, const GLuint*) = loadProc<decltype(deleteMemory)>("glDeleteMemoryObjectsEXT");
    void (APIENTRY* importMemory)(GLuint, UInt64, GLenum, void*) = loadProc<decltype(importMemory)>("glImportMemoryWin32HandleEXT");
    void (APIENTRY* textureStorage)(GLenum, GLsizei, GLenum, GLsizei, GLsizei, GLuint, UInt64) = loadProc<decltype(textureStorage)>("glTexStorageMem2DEXT");
    void (APIENTRY* createSemaphores)(GLsizei, GLuint*) = loadProc<decltype(createSemaphores)>("glCreateSemaphoresNV");
    void (APIENTRY* deleteSemaphores)(GLsizei, const GLuint*) = loadProc<decltype(deleteSemaphores)>("glDeleteSemaphoresEXT");
    void (APIENTRY* semaphoreType)(GLuint, GLenum, const GLint*) = loadProc<decltype(semaphoreType)>("glSemaphoreParameterivNV");
    void (APIENTRY* semaphoreValue)(GLuint, GLenum, const UInt64*) = loadProc<decltype(semaphoreValue)>("glSemaphoreParameterui64vEXT");
    void (APIENTRY* importSemaphore)(GLuint, GLenum, void*) = loadProc<decltype(importSemaphore)>("glImportSemaphoreWin32HandleEXT");
    void (APIENTRY* waitSemaphore)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = loadProc<decltype(waitSemaphore)>("glWaitSemaphoreEXT");
    void (APIENTRY* signalSemaphore)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*) = loadProc<decltype(signalSemaphore)>("glSignalSemaphoreEXT");
    void (APIENTRY* genFramebuffers)(GLsizei, GLuint*) = loadProc<decltype(genFramebuffers)>("glGenFramebuffers");
    void (APIENTRY* deleteFramebuffers)(GLsizei, const GLuint*) = loadProc<decltype(deleteFramebuffers)>("glDeleteFramebuffers");
    void (APIENTRY* bindFramebuffer)(GLenum, GLuint) = loadProc<decltype(bindFramebuffer)>("glBindFramebuffer");
    void (APIENTRY* framebufferTexture)(GLenum, GLenum, GLenum, GLuint, GLint) = loadProc<decltype(framebufferTexture)>("glFramebufferTexture2D");
    GLenum (APIENTRY* checkFramebuffer)(GLenum) = loadProc<decltype(checkFramebuffer)>("glCheckFramebufferStatus");
    void (APIENTRY* blitFramebuffer)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum) = loadProc<decltype(blitFramebuffer)>("glBlitFramebuffer");
};

// Restore bindings so the caller's GL-state cache remains valid.
struct SavedGlState {
    const GlFunctions& gl;
    GLint readFbo = 0, drawFbo = 0, texture = 0;
    GLboolean scissor, srgb;
    explicit SavedGlState(const GlFunctions& functions) : gl(functions) {
        glGetIntegerv(READ_FRAMEBUFFER_BINDING, &readFbo);
        glGetIntegerv(DRAW_FRAMEBUFFER_BINDING, &drawFbo);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
        srgb = glIsEnabled(FRAMEBUFFER_SRGB);
    }
    ~SavedGlState() {
        gl.bindFramebuffer(READ_FRAMEBUFFER, readFbo);
        gl.bindFramebuffer(DRAW_FRAMEBUFFER, drawFbo);
        glBindTexture(GL_TEXTURE_2D, texture);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (srgb) glEnable(FRAMEBUFFER_SRGB); else glDisable(FRAMEBUFFER_SRGB);
    }
};

struct InteropState {
    GlFunctions gl;
    HGLRC ownerContext = wglGetCurrentContext();
    DWORD ownerThread = GetCurrentThreadId();
    GLuint runtimeDone = 0, clientDone = 0;
    GLuint memory = 0, texture = 0, framebuffer = 0;
    GLuint depthMemory = 0, depthTexture = 0;
    UInt64 imageHandle = 0, allocationSize = 0, clientValue = 0;
    UInt64 depthHandle = 0, depthAllocationSize = 0;
    int width = 0, height = 0, format = -1;
    int depthWidth = 0, depthHeight = 0;
    bool acquired = false;

    void requireOwner() const {
        if (wglGetCurrentContext() != ownerContext || GetCurrentThreadId() != ownerThread) {
            throw std::runtime_error("Interop requires its creating OpenGL context and render thread");
        }
    }
    void deleteImage() {
        if (framebuffer) gl.deleteFramebuffers(1, &framebuffer);
        if (texture) glDeleteTextures(1, &texture);
        if (memory) gl.deleteMemory(1, &memory);
        framebuffer = texture = memory = 0;
        imageHandle = 0;
    }
    void deleteDepth() {
        if (depthTexture) glDeleteTextures(1, &depthTexture);
        if (depthMemory) gl.deleteMemory(1, &depthMemory);
        depthTexture = depthMemory = 0;
        depthHandle = depthAllocationSize = 0;
        depthWidth = depthHeight = 0;
    }
    ~InteropState() {
        deleteImage();
        deleteDepth();
        if (runtimeDone) gl.deleteSemaphores(1, &runtimeDone);
        if (clientDone) gl.deleteSemaphores(1, &clientDone);
    }
    void importSemaphore(GLuint& semaphore, UInt64 handle) {
        gl.createSemaphores(1, &semaphore);
        gl.semaphoreType(semaphore, SEMAPHORE_TYPE, &SEMAPHORE_TIMELINE);
        gl.importSemaphore(semaphore, HANDLE_OPAQUE_WIN32, reinterpret_cast<void*>(handle));
        checkGl("timeline semaphore import");
    }
    void ensureImage(UInt64 handle, UInt64 size, int w, int h, int imageFormat) {
        if (imageHandle == handle && allocationSize == size && width == w && height == h && format == imageFormat) return;
        GLenum internalFormat;
        switch (imageFormat) {
            case 0: case 2: internalFormat = GL_RGBA8; break;
            case 1: case 3: internalFormat = SRGB8_ALPHA8; break;
            default: throw std::runtime_error("Unsupported Remix interop color format");
        }
        // Resize may retire an image read by previously queued GL work.
        if (imageHandle) glFinish();
        deleteImage();
        gl.createMemory(1, &memory);
        gl.importMemory(memory, size, HANDLE_OPAQUE_WIN32, reinterpret_cast<void*>(handle));
        checkGl("color memory import");
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        gl.textureStorage(GL_TEXTURE_2D, 1, internalFormat, w, h, memory, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        checkGl("color texture storage");
        gl.genFramebuffers(1, &framebuffer);
        gl.bindFramebuffer(READ_FRAMEBUFFER, framebuffer);
        gl.framebufferTexture(READ_FRAMEBUFFER, COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glReadBuffer(COLOR_ATTACHMENT0);
        if (gl.checkFramebuffer(READ_FRAMEBUFFER) != FRAMEBUFFER_COMPLETE) {
            throw std::runtime_error("Imported Remix color framebuffer is incomplete");
        }
        checkGl("color framebuffer creation");
        imageHandle = handle;
        allocationSize = size;
        width = w;
        height = h;
        format = imageFormat;
    }
    void ensureDepth(UInt64 handle, UInt64 size, int w, int h, int imageFormat) {
        if (!handle) {
            if (depthTexture) { glFinish(); deleteDepth(); }
            return;
        }
        if (imageFormat != 8 || !size || w <= 0 || h <= 0)
            throw std::runtime_error("Unsupported Remix interop primary depth image");
        if (depthHandle == handle && depthAllocationSize == size && depthWidth == w && depthHeight == h) return;
        if (depthTexture) glFinish();
        deleteDepth();
        gl.createMemory(1, &depthMemory);
        gl.importMemory(depthMemory, size, HANDLE_OPAQUE_WIN32, reinterpret_cast<void*>(handle));
        checkGl("depth memory import");
        glGenTextures(1, &depthTexture);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        gl.textureStorage(GL_TEXTURE_2D, 1, R32F, w, h, depthMemory, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
        checkGl("depth texture storage");
        depthHandle = handle; depthAllocationSize = size; depthWidth = w; depthHeight = h;
    }
    void acquire(UInt64 handle, UInt64 size, int w, int h, int imageFormat,
                 UInt64 depth, UInt64 depthSize, int depthW, int depthH, int depthFormat,
                 UInt64 runtimeValue, GLuint target, int targetW, int targetH, bool flipY, bool copyColor) {
        requireOwner();
        if (acquired) throw std::runtime_error("Previous OpenGL interop frame is still acquired");
        // Attribute only new GL errors to this operation; the caller may leave stale flags.
        while (glGetError() != GL_NO_ERROR) {}
        SavedGlState saved(gl);
        ensureImage(handle, size, w, h, imageFormat);
        ensureDepth(depth, depthSize, depthW, depthH, depthFormat);
        const GLuint textures[] = {texture, depthTexture};
        const GLenum layouts[] = {LAYOUT_GENERAL, LAYOUT_GENERAL};
        gl.semaphoreValue(runtimeDone, TIMELINE_VALUE, &runtimeValue);
        gl.waitSemaphore(runtimeDone, 0, nullptr, depthTexture ? 2 : 1, textures, layouts);
        checkGl("waiting for Remix images");
        acquired = true;
        if (!copyColor) return;
        gl.bindFramebuffer(READ_FRAMEBUFFER, framebuffer);
        gl.bindFramebuffer(DRAW_FRAMEBUFFER, target);
        glDisable(GL_SCISSOR_TEST);
        glDisable(FRAMEBUFFER_SRGB);
        gl.blitFramebuffer(0, flipY ? h : 0, w, flipY ? 0 : h,
                           0, 0, targetW, targetH, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        const GLenum blitError = glGetError();
        if (blitError != GL_NO_ERROR) {
            release();
            throw std::runtime_error("OpenGL native-to-client color blit failed");
        }
    }
    UInt64 release() {
        requireOwner();
        if (!acquired) return clientValue;
        ++clientValue;
        const GLuint textures[] = {texture, depthTexture};
        const GLenum layouts[] = {LAYOUT_GENERAL, LAYOUT_GENERAL};
        gl.semaphoreValue(clientDone, TIMELINE_VALUE, &clientValue);
        gl.signalSemaphore(clientDone, 0, nullptr, depthTexture ? 2 : 1, textures, layouts);
        glFlush();
        acquired = false;
        checkGl("signaling GL completion");
        return clientValue;
    }
    UInt64 finish(GLuint source, int sourceW, int sourceH, bool flipY) {
        requireOwner();
        if (!acquired) throw std::runtime_error("No acquired OpenGL interop frame");
        while (glGetError() != GL_NO_ERROR) {}
        SavedGlState saved(gl);
        gl.bindFramebuffer(READ_FRAMEBUFFER, source);
        gl.bindFramebuffer(DRAW_FRAMEBUFFER, framebuffer);
        glDisable(GL_SCISSOR_TEST);
        glDisable(FRAMEBUFFER_SRGB);
        gl.blitFramebuffer(0, flipY ? sourceH : 0, sourceW, flipY ? 0 : sourceH,
                           0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        const GLenum blitError = glGetError();
        // Never leave Vulkan waiting after a rejected framebuffer copy.
        UInt64 value = release();
        if (blitError != GL_NO_ERROR) {
            char message[128];
            sprintf_s(message, "OpenGL client-to-native color blit failed: 0x%04x", blitError);
            throw std::runtime_error(message);
        }
        return value;
    }
};

void throwJava(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception) env->ThrowNew(exception, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_currentWindowHandle(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(WindowFromDC(wglGetCurrentDC()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_validateContext(JNIEnv* env, jclass, jlong state) {
    try {
        if (!state) throw std::runtime_error("OpenGL interop is closed");
        reinterpret_cast<InteropState*>(state)->requireOwner();
    } catch (const std::exception& error) { throwJava(env, error.what()); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_create(JNIEnv* env, jclass, jlong runtimeDone, jlong clientDone) {
    try {
        if (!wglGetCurrentContext()) throw std::runtime_error("No current Windows OpenGL context");
        while (glGetError() != GL_NO_ERROR) {}
        std::unique_ptr<InteropState> state(new InteropState());
        state->importSemaphore(state->runtimeDone, runtimeDone);
        state->importSemaphore(state->clientDone, clientDone);
        return reinterpret_cast<jlong>(state.release());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_acquire(JNIEnv* env, jclass, jlong handle,
    jlong imageHandle, jlong size, jint width, jint height, jint format,
    jlong depthHandle, jlong depthSize, jint depthWidth, jint depthHeight, jint depthFormat,
    jlong runtimeValue, jint target, jint targetWidth, jint targetHeight, jboolean flipY, jboolean copyColor) {
    try {
        auto* state = reinterpret_cast<InteropState*>(handle);
        state->acquire(imageHandle, size, width, height, format,
                       depthHandle, depthSize, depthWidth, depthHeight, depthFormat, runtimeValue,
                       target, targetWidth, targetHeight, flipY != JNI_FALSE, copyColor != JNI_FALSE);
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_depthTexture(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* state = reinterpret_cast<InteropState*>(handle);
        state->requireOwner();
        if (!state->acquired) throw std::runtime_error("No acquired OpenGL interop frame");
        return static_cast<jint>(state->depthTexture);
    } catch (const std::exception& error) { throwJava(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_finish(JNIEnv* env, jclass, jlong handle,
    jint source, jint width, jint height, jboolean flipY) {
    try { return reinterpret_cast<InteropState*>(handle)->finish(source, width, height, flipY != JNI_FALSE); }
    catch (const std::exception& error) { throwJava(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_release(JNIEnv* env, jclass, jlong handle) {
    try { return reinterpret_cast<InteropState*>(handle)->release(); }
    catch (const std::exception& error) { throwJava(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_forge_interop_LegacyGlInterop_destroy(JNIEnv* env, jclass, jlong handle) {
    try {
        auto* state = reinterpret_cast<InteropState*>(handle);
        state->requireOwner();
        state->release();
        glFinish();
        delete state;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    }
}
