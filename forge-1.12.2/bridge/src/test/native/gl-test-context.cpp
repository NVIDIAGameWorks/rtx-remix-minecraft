// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <gl/GL.h>
#include <jni.h>
#include <vector>
#include <stdexcept>
#include <string>
#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")

namespace {
HWND window = nullptr;
HDC dc = nullptr;
HGLRC context = nullptr;
GLuint framebuffer = 0, texture = 0;
constexpr GLenum FRAMEBUFFER = 0x8D40;
constexpr GLenum FRAMEBUFFER_SRGB = 0x8DB9;
constexpr GLenum COLOR_ATTACHMENT0 = 0x8CE0;
int width = 320, height = 240;
template<typename T> T proc(const char* name) {
    auto value = wglGetProcAddress(name);
    if (!value) throw std::runtime_error(std::string("Missing GL entry point: ") + name);
    return reinterpret_cast<T>(value);
}
void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_createContext(JNIEnv* env, jclass) {
    try {
        WNDCLASSW cls = {};
        cls.style = CS_OWNDC;
        cls.lpfnWndProc = DefWindowProcW;
        cls.hInstance = GetModuleHandleW(nullptr);
        cls.lpszClassName = L"RemixBridgeHiddenGpuSmoke";
        if (!RegisterClassW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
            throw std::runtime_error("Could not register hidden test window");
        window = CreateWindowW(cls.lpszClassName, L"Remix bridge GPU smoke", WS_POPUP,
            -32000, -32000, width, height, nullptr, nullptr, cls.hInstance, nullptr);
        if (!window) throw std::runtime_error("Could not create hidden test window");
        ShowWindow(window, SW_SHOWNOACTIVATE);
        dc = GetDC(window);
        PIXELFORMATDESCRIPTOR pixel = {};
        pixel.nSize = sizeof(pixel);
        pixel.nVersion = 1;
        pixel.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
        pixel.iPixelType = PFD_TYPE_RGBA;
        pixel.cColorBits = 32;
        pixel.cAlphaBits = 8;
        pixel.cDepthBits = 24;
        int format = ChoosePixelFormat(dc, &pixel);
        if (!format || !SetPixelFormat(dc, format, &pixel)) throw std::runtime_error("No OpenGL pixel format");
        context = wglCreateContext(dc);
        if (!context || !wglMakeCurrent(dc, context)) throw std::runtime_error("Could not create WGL context");
        auto genFbo = proc<void (APIENTRY*)(GLsizei, GLuint*)>("glGenFramebuffers");
        auto bindFbo = proc<void (APIENTRY*)(GLenum, GLuint)>("glBindFramebuffer");
        auto attach = proc<void (APIENTRY*)(GLenum, GLenum, GLenum, GLuint, GLint)>("glFramebufferTexture2D");
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        genFbo(1, &framebuffer);
        bindFbo(FRAMEBUFFER, framebuffer);
        attach(FRAMEBUFFER, COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glViewport(0, 0, width, height);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, 1, 1);
        glEnable(FRAMEBUFFER_SRGB);
        if (glGetError() != GL_NO_ERROR) throw std::runtime_error("Test framebuffer initialization failed");
        return static_cast<jint>(framebuffer);
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_setContextCurrent(JNIEnv* env, jclass, jboolean current) {
    if (!wglMakeCurrent(current ? dc : nullptr, current ? context : nullptr))
        fail(env, "Could not change the current WGL context");
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_resizeContext(JNIEnv* env, jclass, jint w, jint h) {
    glFinish();
    if (!SetWindowPos(window, nullptr, 0, 0, w, h, SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER)) {
        fail(env, "Could not resize test window");
        return;
    }
    width = w;
    height = h;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glViewport(0, 0, width, height);
    if (glGetError() != GL_NO_ERROR) fail(env, "Could not resize test framebuffer");
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_readImageAndVerifyState(JNIEnv* env, jclass) {
    GLint read = 0, draw = 0, binding = 0;
    glGetIntegerv(0x8CAA, &read);
    glGetIntegerv(0x8CA6, &draw);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &binding);
    if (read != framebuffer || draw != framebuffer || binding != texture
            || !glIsEnabled(GL_SCISSOR_TEST) || !glIsEnabled(FRAMEBUFFER_SRGB)) {
        fail(env, "Interop did not restore framebuffer, texture, scissor, or sRGB state");
        return nullptr;
    }
    std::vector<jbyte> pixels(width * height * 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    if (glGetError() != GL_NO_ERROR) { fail(env, "Test readback failed"); return nullptr; }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(pixels.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_readDepthTexture(JNIEnv* env, jclass, jint textureName,
    jint width, jint height) {
    if (textureName <= 0 || width <= 0 || height <= 0 || width > 4096 || height > 4096) {
        fail(env, "Invalid depth readback dimensions or texture");
        return nullptr;
    }
    GLint previous = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(textureName));
    GLint actualWidth = 0, actualHeight = 0, internalFormat = 0;
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &actualWidth);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &actualHeight);
    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT, &internalFormat);
    if (actualWidth != width || actualHeight != height || internalFormat != 0x822E) {
        glBindTexture(GL_TEXTURE_2D, previous);
        fail(env, "Imported depth does not match the runtime's R32F image");
        return nullptr;
    }
    std::vector<jfloat> values(static_cast<size_t>(width) * height);
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_FLOAT, values.data());
    glBindTexture(GL_TEXTURE_2D, previous);
    if (glGetError() != GL_NO_ERROR) { fail(env, "Depth texture readback failed"); return nullptr; }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_GlInteropSmokeTest_destroyContext(JNIEnv*, jclass) {
    if (context) {
        wglMakeCurrent(nullptr, nullptr);
        wglDeleteContext(context);
        context = nullptr;
    }
    if (dc && window) ReleaseDC(window, dc);
    if (window) DestroyWindow(window);
    dc = nullptr;
    window = nullptr;
}
