/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * RTX Remix JNI Bridge: remixbridge.dll
 *
 * Single-file JNI shim: loads d3d9.dll, calls remixapi_InitializeLibrary to
 * obtain the remixapi_Interface vtable, and exposes every Remix API method as
 * a JNI native function consumed by com.nvidia.rtxremix.RemixBridge.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winternl.h>
#include <unknwn.h>
#include <dbghelp.h>
#include <tlhelp32.h>
#include <malloc.h>
#include <jni.h>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <set>
#include <string>
#include <vector>

// HACKHACK: link to OpenGL to expose 'glSemaphoreParameterui64vEXT',
//           reason: LWJGL does not expose that function
#include <gl/GL.h>
#pragma comment(lib, "opengl32.lib")

#include "remix_c.h"

// ════════════════════════════════════════════════════════════════════════════
// Crash dump: VEH that writes a minidump and a human-readable crash report.
//
// The minidump is for deep debugging when PDBs are available.
// The text report is immediately readable: exception info, registers,
// faulting module, loaded module list, and native stack walk for every
// thread, so you can triage a crash without loading the dump in VS.
//
// Both land in ./crash/ relative to the working directory (typically run/).
// ════════════════════════════════════════════════════════════════════════════

static const char* exceptionCodeName(DWORD code) {
    switch (code) {
        case 0xC0000005: return "EXCEPTION_ACCESS_VIOLATION";
        case 0xC00000FD: return "EXCEPTION_STACK_OVERFLOW";
        case 0xC000008C: return "EXCEPTION_ARRAY_BOUNDS_EXCEEDED";
        case 0xC000001D: return "EXCEPTION_ILLEGAL_INSTRUCTION";
        case 0xC0000094: return "EXCEPTION_INT_DIVIDE_BY_ZERO";
        case 0xC0000096: return "EXCEPTION_PRIV_INSTRUCTION";
        case 0xC0000006: return "EXCEPTION_IN_PAGE_ERROR";
        case 0x80000001: return "EXCEPTION_GUARD_PAGE";
        case 0xC0000374: return "STATUS_HEAP_CORRUPTION";
        case 0xC0000409: return "STATUS_STACK_BUFFER_OVERRUN (__fastfail)";
        default:         return "UNKNOWN";
    }
}

// Resolve an address to "module.dll+0xOFFSET" or "??? (JIT/heap)" if unmapped
static void formatAddress(FILE* f, uintptr_t addr) {
    HMODULE mod = nullptr;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCSTR)addr, &mod) && mod) {
        char name[MAX_PATH];
        if (GetModuleFileNameA(mod, name, sizeof(name))) {
            const char* base = strrchr(name, '\\');
            base = base ? base + 1 : name;
            fprintf(f, "%s+0x%llX", base, (unsigned long long)(addr - (uintptr_t)mod));
            return;
        }
    }
    fprintf(f, "0x%016llX (JIT/heap)", (unsigned long long)addr);
}

static void writeModuleList(FILE* f) {
    fprintf(f, "\n=== Loaded Modules ===\n");
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId());
    if (snap == INVALID_HANDLE_VALUE) return;
    MODULEENTRY32 me;
    me.dwSize = sizeof(me);
    if (Module32First(snap, &me)) {
        do {
            fprintf(f, "  %016llX - %016llX  %s\n",
                    (unsigned long long)(uintptr_t)me.modBaseAddr,
                    (unsigned long long)((uintptr_t)me.modBaseAddr + me.modBaseSize),
                    me.szModule);
        } while (Module32Next(snap, &me));
    }
    CloseHandle(snap);
}

static void writeRegisters(FILE* f, const CONTEXT* ctx) {
    fprintf(f, "\n=== Registers ===\n");
#if defined(_M_X64)
    fprintf(f, "  RAX=%016llX  RBX=%016llX  RCX=%016llX  RDX=%016llX\n",
            ctx->Rax, ctx->Rbx, ctx->Rcx, ctx->Rdx);
    fprintf(f, "  RSI=%016llX  RDI=%016llX  RSP=%016llX  RBP=%016llX\n",
            ctx->Rsi, ctx->Rdi, ctx->Rsp, ctx->Rbp);
    fprintf(f, "  R8 =%016llX  R9 =%016llX  R10=%016llX  R11=%016llX\n",
            ctx->R8, ctx->R9, ctx->R10, ctx->R11);
    fprintf(f, "  R12=%016llX  R13=%016llX  R14=%016llX  R15=%016llX\n",
            ctx->R12, ctx->R13, ctx->R14, ctx->R15);
    fprintf(f, "  RIP=%016llX  EFLAGS=%08lX\n", ctx->Rip, ctx->EFlags);
#elif defined(_M_ARM64)
    for (int i = 0; i < 28; i += 4)
        fprintf(f, "  X%-2d=%016llX  X%-2d=%016llX  X%-2d=%016llX  X%-2d=%016llX\n",
                i, ctx->X[i], i+1, ctx->X[i+1], i+2, ctx->X[i+2], i+3, ctx->X[i+3]);
    fprintf(f, "  X28=%016llX\n", ctx->X[28]);
    fprintf(f, "  FP =%016llX  LR =%016llX  SP =%016llX  PC =%016llX\n",
            ctx->Fp, ctx->Lr, ctx->Sp, ctx->Pc);
    fprintf(f, "  CPSR=%08lX\n", ctx->Cpsr);
#endif
}

static void walkStack(FILE* f, HANDLE process, HANDLE thread, CONTEXT ctx) {
    STACKFRAME64 frame = {};
#if defined(_M_X64)
    DWORD machineType     = IMAGE_FILE_MACHINE_AMD64;
    frame.AddrPC.Offset    = ctx.Rip;
    frame.AddrFrame.Offset = ctx.Rbp;
    frame.AddrStack.Offset = ctx.Rsp;
#elif defined(_M_ARM64)
    DWORD machineType     = IMAGE_FILE_MACHINE_ARM64;
    frame.AddrPC.Offset    = ctx.Pc;
    frame.AddrFrame.Offset = ctx.Fp;
    frame.AddrStack.Offset = ctx.Sp;
#endif
    frame.AddrPC.Mode      = AddrModeFlat;
    frame.AddrFrame.Mode   = AddrModeFlat;
    frame.AddrStack.Mode   = AddrModeFlat;

    for (int i = 0; i < 64; i++) {
        if (!StackWalk64(machineType, process, thread,
                         &frame, &ctx, nullptr,
                         SymFunctionTableAccess64, SymGetModuleBase64, nullptr))
            break;
        if (frame.AddrPC.Offset == 0) break;

        fprintf(f, "    [%2d] ", i);
        formatAddress(f, (uintptr_t)frame.AddrPC.Offset);
        fprintf(f, "\n");
    }
}

static void writeAllThreadStacks(FILE* f, DWORD faultingTid, EXCEPTION_POINTERS* ep) {
    HANDLE process = GetCurrentProcess();
    SymInitialize(process, nullptr, TRUE);

    // Faulting thread first: use the exception context directly
    fprintf(f, "\n=== Faulting Thread (TID %lu) ===\n", faultingTid);
    walkStack(f, process, GetCurrentThread(), *ep->ContextRecord);

    fprintf(f, "\n=== Other Threads ===\n");
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (snap != INVALID_HANDLE_VALUE) {
        THREADENTRY32 te;
        te.dwSize = sizeof(te);
        DWORD pid = GetCurrentProcessId();
        if (Thread32First(snap, &te)) {
            do {
                if (te.th32OwnerProcessID != pid) continue;
                if (te.th32ThreadID == faultingTid) continue;

                HANDLE th = OpenThread(THREAD_GET_CONTEXT | THREAD_SUSPEND_RESUME |
                                       THREAD_QUERY_INFORMATION, FALSE, te.th32ThreadID);
                if (!th) continue;

                SuspendThread(th);
                CONTEXT ctx = {};
                ctx.ContextFlags = CONTEXT_FULL;
                if (GetThreadContext(th, &ctx)) {
                    fprintf(f, "\n  Thread %lu:\n", te.th32ThreadID);
                    walkStack(f, process, th, ctx);
                }
                ResumeThread(th);
                CloseHandle(th);
            } while (Thread32Next(snap, &te));
        }
        CloseHandle(snap);
    }

    SymCleanup(process);
}

// Writes both a .dmp and a .txt crash report for the given exception.
static void writeCrashFiles(EXCEPTION_POINTERS* ep) {
    static volatile LONG dumping = 0;
    if (InterlockedCompareExchange(&dumping, 1, 0) != 0)
        return;

    CreateDirectoryA("crash", nullptr);

    char tag[32];
    time_t now = time(nullptr);
    struct tm tm;
    localtime_s(&tm, &now);
    snprintf(tag, sizeof(tag), "%04d%02d%02d_%02d%02d%02d",
             tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
             tm.tm_hour, tm.tm_min, tm.tm_sec);

    // ── Minidump ──
    char dmpPath[MAX_PATH];
    snprintf(dmpPath, sizeof(dmpPath), "crash\\minidump_%s.dmp", tag);

    HANDLE dmpFile = CreateFileA(dmpPath, GENERIC_WRITE, 0, nullptr,
                                 CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (dmpFile != INVALID_HANDLE_VALUE) {
        MINIDUMP_EXCEPTION_INFORMATION mei;
        mei.ThreadId          = GetCurrentThreadId();
        mei.ExceptionPointers = ep;
        mei.ClientPointers    = FALSE;

        // No MiniDumpWithIndirectlyReferencedMemory: it copies the memory
        // that stack values point at, and the game's main thread points at
        // the launch arguments for the whole session, the launcher's access
        // token among them. Players attach these files to public issues.
        DWORD flags = MiniDumpWithDataSegs
                    | MiniDumpWithHandleData
                    | MiniDumpWithThreadInfo
                    | MiniDumpWithUnloadedModules
                    | MiniDumpWithFullMemoryInfo;

        MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(),
                          dmpFile, (MINIDUMP_TYPE)flags, &mei, nullptr, nullptr);
        CloseHandle(dmpFile);
    }

    // ── Text crash report ──
    char txtPath[MAX_PATH];
    snprintf(txtPath, sizeof(txtPath), "crash\\crash_report_%s.txt", tag);

    DWORD code = ep->ExceptionRecord->ExceptionCode;

    FILE* f = fopen(txtPath, "w");
    if (f) {
        fprintf(f, "=== RTX Remix for Minecraft: Crash Report ===\n");
        fprintf(f, "Minidump: %s\n\n", dmpPath);

        fprintf(f, "Exception: 0x%08lX (%s)\n",
                code, exceptionCodeName(code));
        fprintf(f, "Address:   ");
        formatAddress(f, (uintptr_t)ep->ExceptionRecord->ExceptionAddress);
        fprintf(f, "\n");

        if (code == EXCEPTION_ACCESS_VIOLATION && ep->ExceptionRecord->NumberParameters >= 2) {
            const char* op = ep->ExceptionRecord->ExceptionInformation[0] == 0 ? "reading"
                           : ep->ExceptionRecord->ExceptionInformation[0] == 1 ? "writing"
                           : "executing";
            fprintf(f, "Fault:     %s 0x%016llX\n",
                    op, (unsigned long long)ep->ExceptionRecord->ExceptionInformation[1]);
        }

        fprintf(f, "Thread:    %lu\n", GetCurrentThreadId());
        fprintf(f, "Process:   %lu\n", GetCurrentProcessId());

        writeRegisters(f, ep->ContextRecord);
        writeAllThreadStacks(f, GetCurrentThreadId(), ep);
        writeModuleList(f);

        fclose(f);
    }
}

// Unhandled Exception Filter: only fires when NO handler (including the
// JVM's own VEH for safepoint polls, implicit null checks, and stack
// overflow detection) could handle the exception. This is a genuine crash.
static LPTOP_LEVEL_EXCEPTION_FILTER g_prevFilter = nullptr;

static LONG WINAPI crashExceptionFilter(EXCEPTION_POINTERS* ep) {
    writeCrashFiles(ep);

    if (g_prevFilter)
        return g_prevFilter(ep);
    return EXCEPTION_CONTINUE_SEARCH;
}

static void installCrashHandler() {
    g_prevFilter = SetUnhandledExceptionFilter(crashExceptionFilter);
}

static void uninstallCrashHandler() {
    SetUnhandledExceptionFilter(g_prevFilter);
    g_prevFilter = nullptr;
}

// Tracy profiling: resolve ___tracy_* symbols from d3d9.dll at runtime so
// Java zones appear on the same timeline as dxvk-remix zones. We can't use
// TRACY_IMPORTS because we link against the Windows SDK d3d9.lib which
// doesn't contain Tracy symbols.
struct TracyCZoneCtx { uint32_t id; int active; };

using FnAllocSrclocName = uint64_t(*)(uint32_t line, const char* source, size_t sourceSz,
                                       const char* function, size_t functionSz,
                                       const char* name, size_t nameSz);
using FnEmitZoneBeginAlloc = TracyCZoneCtx(*)(uint64_t srcloc, int active);
using FnEmitZoneEnd = void(*)(TracyCZoneCtx ctx);
using FnSetThreadName = void(*)(const char* name);
using FnEmitPlot = void(*)(const char* name, double value);
using FnEmitMessage = void(*)(const char* text, size_t size, int callstack);
using FnEmitFrameMark = void(*)(const char* name);

static FnAllocSrclocName    s_allocSrclocName;
static FnEmitZoneBeginAlloc s_emitZoneBeginAlloc;
static FnEmitZoneEnd        s_emitZoneEnd;
static FnSetThreadName      s_setThreadName;
static FnEmitPlot           s_emitPlot;
static FnEmitMessage        s_emitMessage;
static FnEmitFrameMark      s_emitFrameMark;
static std::mutex           s_tracyMutex;
static bool                 s_tracyResolved = false;
static bool                 s_tracyAvailable = false;
static bool                 s_tracyEnabled = false;

static void resetTracyFunctionsLocked() {
    s_allocSrclocName = nullptr;
    s_emitZoneBeginAlloc = nullptr;
    s_emitZoneEnd = nullptr;
    s_setThreadName = nullptr;
    s_emitPlot = nullptr;
    s_emitMessage = nullptr;
    s_emitFrameMark = nullptr;
    s_tracyResolved = false;
    s_tracyAvailable = false;
}

static bool initTracyFunctionsLocked() {
    if (!s_tracyEnabled) return false;
    if (s_tracyResolved) return s_tracyAvailable;

    HMODULE d3d9 = GetModuleHandleA("d3d9.dll");
    if (!d3d9) return false;

    s_allocSrclocName    = (FnAllocSrclocName)   GetProcAddress(d3d9, "___tracy_alloc_srcloc_name");
    s_emitZoneBeginAlloc = (FnEmitZoneBeginAlloc)GetProcAddress(d3d9, "___tracy_emit_zone_begin_alloc");
    s_emitZoneEnd        = (FnEmitZoneEnd)       GetProcAddress(d3d9, "___tracy_emit_zone_end");
    s_setThreadName      = (FnSetThreadName)     GetProcAddress(d3d9, "___tracy_set_thread_name");
    s_emitPlot           = (FnEmitPlot)          GetProcAddress(d3d9, "___tracy_emit_plot");
    s_emitMessage        = (FnEmitMessage)       GetProcAddress(d3d9, "___tracy_emit_message");
    s_emitFrameMark      = (FnEmitFrameMark)     GetProcAddress(d3d9, "___tracy_emit_frame_mark");

    s_tracyAvailable = s_allocSrclocName && s_emitZoneBeginAlloc && s_emitZoneEnd;
    s_tracyResolved = true;
    return s_tracyAvailable;
}

// ════════════════════════════════════════════════════════════════════════════
// Global state: filled by initialize(), read by every other function.
// ════════════════════════════════════════════════════════════════════════════

static HMODULE          g_remixDll   = nullptr;
static remixapi_Interface g_remix    = {};
static bool             g_inited     = false;

// ════════════════════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════════════════════

static void fillSurface(JNIEnv* env, remixapi_MeshInfoSurfaceTriangles& surf,
                         jobject vertBuf, jint vertCount,
                         jobject idxBuf, jint idxCount,
                         jlong material,
                         jint flags,
                         jint bonesPerVertex,
                         jobject blendWeightBuf, jint blendWeightCount,
                         jobject blendIndexBuf, jint blendIndexCount)
{
    memset(&surf, 0, sizeof(surf));

    if (vertBuf) {
        surf.vertices_values = (const remixapi_HardcodedVertex*)
            env->GetDirectBufferAddress(vertBuf);
    }
    surf.vertices_count = (uint64_t)vertCount;

    if (idxBuf) {
        surf.indices_values = (const uint32_t*)
            env->GetDirectBufferAddress(idxBuf);
    }
    surf.indices_count = (uint64_t)idxCount;

    surf.material = (remixapi_MaterialHandle)(uintptr_t)material;
    surf.flags = (remixapi_MeshInfoSurfaceTrianglesFlags)flags;

    if (bonesPerVertex > 0 && blendWeightBuf && blendIndexBuf) {
        surf.skinning_hasvalue = 1;
        surf.skinning_value.bonesPerVertex = (uint32_t)bonesPerVertex;
        surf.skinning_value.blendWeights_values = (const float*)
            env->GetDirectBufferAddress(blendWeightBuf);
        surf.skinning_value.blendWeights_count = (uint32_t)blendWeightCount;
        surf.skinning_value.blendIndices_values = (const uint32_t*)
            env->GetDirectBufferAddress(blendIndexBuf);
        surf.skinning_value.blendIndices_count = (uint32_t)blendIndexCount;
    }
}

// Build an array of surfaces from parallel Java arrays.  Caller owns the
// returned pointer (stack or heap); for small counts we use alloca,
// otherwise heap.
#define MAX_STACK_SURFACES 16

static remixapi_MeshInfoSurfaceTriangles* buildSurfaces(
    JNIEnv* env, jint surfaceCount,
    jobjectArray vertexBuffers, jintArray vertexCounts,
    jobjectArray indexBuffers, jintArray indexCounts,
    jlongArray materialHandles,
    jintArray jFlags,
    jintArray jBonesPerVertex,
    jobjectArray blendWeightBuffers, jintArray blendWeightCounts,
    jobjectArray blendIndexBuffers, jintArray blendIndexCounts,
    remixapi_MeshInfoSurfaceTriangles* stackBuf)
{
    remixapi_MeshInfoSurfaceTriangles* surfs =
        (surfaceCount <= MAX_STACK_SURFACES) ? stackBuf
        : new remixapi_MeshInfoSurfaceTriangles[surfaceCount];

    jint*  vc  = env->GetIntArrayElements(vertexCounts, nullptr);
    jint*  ic  = env->GetIntArrayElements(indexCounts, nullptr);
    jlong* mh  = env->GetLongArrayElements(materialHandles, nullptr);
    jint*  flags = env->GetIntArrayElements(jFlags, nullptr);
    jint*  bpv = jBonesPerVertex ? env->GetIntArrayElements(jBonesPerVertex, nullptr) : nullptr;
    jint*  bwc = blendWeightCounts ? env->GetIntArrayElements(blendWeightCounts, nullptr) : nullptr;
    jint*  bic = blendIndexCounts ? env->GetIntArrayElements(blendIndexCounts, nullptr) : nullptr;

    for (jint i = 0; i < surfaceCount; i++) {
        jobject vb  = env->GetObjectArrayElement(vertexBuffers, i);
        jobject ib  = env->GetObjectArrayElement(indexBuffers, i);
        jobject bwb = blendWeightBuffers ? env->GetObjectArrayElement(blendWeightBuffers, i) : nullptr;
        jobject bib = blendIndexBuffers  ? env->GetObjectArrayElement(blendIndexBuffers, i)  : nullptr;

        fillSurface(env, surfs[i], vb, vc[i], ib, ic[i], mh[i], flags[i],
                     bpv ? bpv[i] : 0, bwb, bwc ? bwc[i] : 0, bib, bic ? bic[i] : 0);

        env->DeleteLocalRef(vb);
        env->DeleteLocalRef(ib);
        if (bwb) env->DeleteLocalRef(bwb);
        if (bib) env->DeleteLocalRef(bib);
    }

    env->ReleaseIntArrayElements(vertexCounts, vc, JNI_ABORT);
    env->ReleaseIntArrayElements(indexCounts, ic, JNI_ABORT);
    env->ReleaseLongArrayElements(materialHandles, mh, JNI_ABORT);
    env->ReleaseIntArrayElements(jFlags, flags, JNI_ABORT);
    if (bpv) env->ReleaseIntArrayElements(jBonesPerVertex, bpv, JNI_ABORT);
    if (bwc) env->ReleaseIntArrayElements(blendWeightCounts, bwc, JNI_ABORT);
    if (bic) env->ReleaseIntArrayElements(blendIndexCounts, bic, JNI_ABORT);

    return surfs;
}

static void freeSurfaces(remixapi_MeshInfoSurfaceTriangles* surfs,
                          remixapi_MeshInfoSurfaceTriangles* stackBuf) {
    if (surfs != stackBuf) delete[] surfs;
}


namespace {
template <typename T>
bool setJavaInstanceField(JNIEnv *env, jclass java_class,
                          jobject java_dstInstance, const char *java_fieldName,
                          T *c_srcValue) = delete;
template <>
bool setJavaInstanceField(JNIEnv *env, jclass java_class,
                          jobject java_dstInstance, const char *java_fieldName,
                          uint64_t *c_srcValue) {
  jfieldID fid = env->GetFieldID(java_class, java_fieldName, "J");
  if (fid == nullptr) {
    return false;
  }
  static_assert(sizeof(jlong) == sizeof(*c_srcValue), "Size mismatch");
  env->SetLongField(java_dstInstance, fid, *c_srcValue);
  return true;
}
template <>
bool setJavaInstanceField(JNIEnv *env, jclass java_class,
                          jobject java_dstInstance, const char *java_fieldName,
                          int32_t *c_srcValue) {
  jfieldID fid = env->GetFieldID(java_class, java_fieldName, "I");
  if (fid == nullptr) {
    return false;
  }
  static_assert(sizeof(jint) == sizeof(*c_srcValue), "Size mismatch");
  env->SetIntField(java_dstInstance, fid, (jint)*c_srcValue);
  return true;
}
template <>
bool setJavaInstanceField(JNIEnv *env, jclass java_class,
                          jobject java_dstInstance, const char *java_fieldName,
                          remixapi_TextureFormat *c_srcValue) {
  static_assert(sizeof(*c_srcValue) == sizeof(int32_t), "Size mismatch");
  return setJavaInstanceField<int32_t>(env, java_class, java_dstInstance,
                                       java_fieldName, (int32_t *)c_srcValue);
}
} // namespace

static jint initializeRuntime(const wchar_t* dllPath)
{
    // Extract parent directory for SetDllDirectoryW
    wchar_t parentDir[MAX_PATH];
    wcscpy_s(parentDir, dllPath);
    wchar_t* lastSlash = wcsrchr(parentDir, L'\\');
    if (!lastSlash) lastSlash = wcsrchr(parentDir, L'/');
    if (lastSlash) *lastSlash = L'\0';
    else parentDir[0] = L'\0';

    if (parentDir[0] != L'\0') {
        SetDllDirectoryW(parentDir);
    }

    g_remixDll = LoadLibraryW(dllPath);
    if (!g_remixDll) {
        return REMIXAPI_ERROR_CODE_LOAD_LIBRARY_FAILURE;
    }

    auto pfnInit = (PFN_remixapi_InitializeLibrary)
        GetProcAddress(g_remixDll, "remixapi_InitializeLibrary");
    if (!pfnInit) {
        FreeLibrary(g_remixDll);
        g_remixDll = nullptr;
        return REMIXAPI_ERROR_CODE_GET_PROC_ADDRESS_FAILURE;
    }

    remixapi_InitializeLibraryInfo initInfo = {};
    initInfo.sType   = REMIXAPI_STRUCT_TYPE_INITIALIZE_LIBRARY_INFO;
    initInfo.version = REMIXAPI_VERSION_MAKE(
        REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH);

    memset(&g_remix, 0, sizeof(g_remix));

    remixapi_ErrorCode rc = pfnInit(&initInfo, &g_remix);
    if (rc == REMIXAPI_ERROR_CODE_SUCCESS) {
        // ABI sanity check: every function pointer we rely on at runtime must be
        // populated. A partially-filled vtable indicates the loaded d3d9.dll is
        // from an older minor version that predates a tail-of-struct addition.
        // isVersionCompatible on the runtime side should catch this first, but
        // keep a belt-and-suspenders check for custom/forked runtimes.
        if (!g_remix.UpdateTextureFromMemory || !g_remix.UpdateMaterial
                || !g_remix.GetConfigVariableResolved) {
            fprintf(stderr,
                "[remixbridge] FATAL: loaded Remix runtime is missing "
                "required vtable tail entries. "
                "This indicates an ABI mismatch: expected REMIXAPI v%d.%d.%d "
                "or newer.\n",
                REMIXAPI_VERSION_MAJOR, REMIXAPI_VERSION_MINOR, REMIXAPI_VERSION_PATCH);
            fflush(stderr);
            memset(&g_remix, 0, sizeof(g_remix));
            FreeLibrary(g_remixDll);
            g_remixDll = nullptr;
            return REMIXAPI_ERROR_CODE_INCOMPATIBLE_VERSION;
        }
        g_inited = true;
        {
            std::lock_guard<std::mutex> lock(s_tracyMutex);
            s_tracyEnabled = true;
            resetTracyFunctionsLocked();
        }
        installCrashHandler();
    }
    return (jint)rc;
}

// ════════════════════════════════════════════════════════════════════════════
// JNI exports: com_nvidia_rtxremix_RemixBridge_*
// ════════════════════════════════════════════════════════════════════════════

extern "C" {

// ─── initialize ──────────────────────────────────────────────────────────

// Plain C entry point used by the developer tools in a short-lived subprocess.
// It deliberately follows the same initialization path as Java so this bridge,
// not the launcher, remains the authority on Remix API compatibility.
__declspec(dllexport) jint remixbridge_probe_runtime(const wchar_t* dllPath)
{
    if (!dllPath || !dllPath[0]) {
        return REMIXAPI_ERROR_CODE_LOAD_LIBRARY_FAILURE;
    }
    return initializeRuntime(dllPath);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_initialize(JNIEnv* env, jclass, jstring jDllPath)
{
    const char* path = env->GetStringUTFChars(jDllPath, nullptr);

    // Convert UTF-8 path to wide string for SetDllDirectoryW / LoadLibraryW
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path, -1, nullptr, 0);
    wchar_t* wpath = (wchar_t*)_alloca(wlen * sizeof(wchar_t));
    MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, wlen);

    env->ReleaseStringUTFChars(jDllPath, path);

    return initializeRuntime(wpath);
}

// ─── process command line ────────────────────────────────────────────────

// Crash dumps copy the process parameters out of the PEB, so this is the copy
// a redaction must reach.
static UNICODE_STRING& pebCommandLine() {
    return NtCurrentTeb()->ProcessEnvironmentBlock->ProcessParameters->CommandLine;
}

JNIEXPORT jstring JNICALL
Java_com_nvidia_rtxremix_RemixBridge_processCommandLine(JNIEnv* env, jclass)
{
    const UNICODE_STRING& commandLine = pebCommandLine();
    return env->NewString((const jchar*)commandLine.Buffer,
        (jsize)(commandLine.Length / sizeof(WCHAR)));
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_overwriteProcessCommandLine(JNIEnv* env, jclass,
    jstring jReplacement)
{
    UNICODE_STRING& commandLine = pebCommandLine();
    const jsize length = (jsize)(commandLine.Length / sizeof(WCHAR));
    if (!jReplacement || env->GetStringLength(jReplacement) != length)
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;

    std::vector<WCHAR> replacement(length);
    env->GetStringRegion(jReplacement, 0, length, (jchar*)replacement.data());

    // GetCommandLineA returns an ANSI conversion made at process start, sized
    // for the original line. Converting the replacement must fit in it, and is
    // shorter whenever a blanked character took more than one byte.
    char* ansi = GetCommandLineA();
    const size_t originalAnsiLength = strlen(ansi);
    const int ansiLength = WideCharToMultiByte(CP_ACP, 0, replacement.data(), length,
        nullptr, 0, nullptr, nullptr);
    if (ansiLength <= 0 || (size_t)ansiLength > originalAnsiLength)
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;

    // GetCommandLineW normally returns the PEB buffer itself. A separate copy
    // of the same line is kept in step.
    WCHAR* wide = GetCommandLineW();
    const bool separateWideCopy = wide != commandLine.Buffer
        && wcslen(wide) == (size_t)length
        && wmemcmp(wide, commandLine.Buffer, length) == 0;

    wmemcpy(commandLine.Buffer, replacement.data(), length);
    if (separateWideCopy)
        wmemcpy(wide, replacement.data(), length);
    WideCharToMultiByte(CP_ACP, 0, replacement.data(), length, ansi, ansiLength, nullptr, nullptr);
    // Zero through the original terminator, so no byte of a blanked value
    // survives past the new end of the line.
    SecureZeroMemory(ansi + ansiLength, originalAnsiLength - ansiLength + 1);
    return REMIXAPI_ERROR_CODE_SUCCESS;
}

// ─── lifecycle ───────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_startup(JNIEnv*, jclass,
    jlong hwnd, jboolean disableSrgb, jboolean forceNoSwapchain,
    jboolean editorMode, jboolean combineGui, jboolean interopImage)
{
    if (!g_inited || !g_remix.Startup) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    remixapi_StartupInfo info = {};
    info.sType                         = REMIXAPI_STRUCT_TYPE_STARTUP_INFO;
    info.hwnd                          = (HWND)(uintptr_t)hwnd;
    info.disableSrgbConversionForOutput = disableSrgb;
    info.forceNoVkSwapchain            = forceNoSwapchain;
    info.editorModeEnabled             = editorMode;
    info.combineGuiInFinalColor        = combineGui;
    info.interopImage                  = interopImage;
    return (jint)g_remix.Startup(&info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_shutdown(JNIEnv*, jclass)
{
    if (!g_inited || !g_remix.Shutdown) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    auto shutdown = g_remix.Shutdown;
    g_inited = false;
    uninstallCrashHandler();
    {
        std::lock_guard<std::mutex> lock(s_tracyMutex);
        s_tracyEnabled = false;
        resetTracyFunctionsLocked();
    }
    jint rc = (jint)shutdown();
    if (g_remixDll) { FreeLibrary(g_remixDll); g_remixDll = nullptr; }
    memset(&g_remix, 0, sizeof(g_remix));
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_submit(JNIEnv*, jclass,
    jint interopFlags)
{
    if (!g_inited || !g_remix.Submit) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    remixapi_SubmitInfo info = {};
    info.sType = REMIXAPI_STRUCT_TYPE_SUBMIT_INFO;
    info.interopFlags = (uint32_t)interopFlags;
    return (jint)g_remix.Submit(&info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_present(JNIEnv*, jclass, 
    jlong hwndOverride,
    jint skipVulkanSwapchain,
    jint interopFlipY,
    jlong clientDoneSemaphore_ValueToWait,
    jint disableCpuWaitForExternalSemaphores)
{
    if (!g_inited || !g_remix.Present) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    if (hwndOverride == 0) {
        return (jint)g_remix.Present(nullptr);
    }
    remixapi_PresentInfo info = {};
    info.sType        = REMIXAPI_STRUCT_TYPE_PRESENT_INFO;
    info.hwndOverride = (HWND)(uintptr_t)hwndOverride;
    info.skipVulkanSwapchain = (bool)skipVulkanSwapchain;
    info.interopFlipY = (bool)interopFlipY;
    info.clientDoneSemaphore_ValueToWait = (uint64_t)clientDoneSemaphore_ValueToWait;
    info.disableCpuWaitForExternalSemaphores = (bool)disableCpuWaitForExternalSemaphores;
    return (jint)g_remix.Present(&info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkGetInteropInfo(JNIEnv* env, jclass, jobject jOutInteropInfo)
{
    if (!g_inited || !g_remix.dxvk_GetInteropInfo) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;

    remixapi_dxvk_InteropInfo info = {};
    remixapi_ErrorCode r = g_remix.dxvk_GetInteropInfo(&info);
    if (r != REMIXAPI_ERROR_CODE_SUCCESS) return (jint)r;

    // Get class of Java interop info object
    jclass cls = env->GetObjectClass(jOutInteropInfo);
    if (cls == nullptr) {
        return REMIXAPI_ERROR_CODE_GET_PROC_ADDRESS_FAILURE;
    }

#define setJavaFieldInteropInfo(fieldName)                                     \
    if (!setJavaInstanceField(env, cls, jOutInteropInfo, #fieldName,            \
                              &info.fieldName)) {                              \
      return REMIXAPI_ERROR_CODE_GET_PROC_ADDRESS_FAILURE;                     \
    }

    // Set fields of Java interop info object
    setJavaFieldInteropInfo(dxvkDoneSemaphore_Handle);
    setJavaFieldInteropInfo(dxvkDoneSemaphore_ValueToWait);
    setJavaFieldInteropInfo(clientDoneSemaphore_Handle);
    setJavaFieldInteropInfo(imageMemory_Handle);
    setJavaFieldInteropInfo(allocationSize);
    setJavaFieldInteropInfo(width);
    setJavaFieldInteropInfo(height);
    setJavaFieldInteropInfo(interopImageFormat);
    setJavaFieldInteropInfo(depthImageMemory_Handle);
    setJavaFieldInteropInfo(depthAllocationSize);
    setJavaFieldInteropInfo(depthWidth);
    setJavaFieldInteropInfo(depthHeight);
    setJavaFieldInteropInfo(interopDepthImageFormat);

    return (jint)r;
}


// ─── materials ───────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_createMaterial(JNIEnv*, jclass,
    jlong hash,
    jlong albedoTex, jlong normalTex, jlong tangentTex, jlong emissiveTex,
    jfloat emIntensity, jfloat emR, jfloat emG, jfloat emB,
    jint spriteRow, jint spriteCol, jint spriteFps,
    jint filterMode, jint wrapU, jint wrapV,
    jlong pNext)
{
    if (!g_inited || !g_remix.CreateMaterial) return 0;

    remixapi_MaterialInfo info = {};
    info.sType              = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
    info.pNext              = (void*)(uintptr_t)pNext;
    info.hash               = (uint64_t)hash;
    info.albedoTexture      = (remixapi_TextureHandle)(uintptr_t)albedoTex;
    info.normalTexture      = (remixapi_TextureHandle)(uintptr_t)normalTex;
    info.tangentTexture     = (remixapi_TextureHandle)(uintptr_t)tangentTex;
    info.emissiveTexture    = (remixapi_TextureHandle)(uintptr_t)emissiveTex;
    info.emissiveIntensity  = emIntensity;
    info.emissiveColorConstant = { emR, emG, emB };
    info.spriteSheetRow     = (uint8_t)spriteRow;
    info.spriteSheetCol     = (uint8_t)spriteCol;
    info.spriteSheetFps     = (uint8_t)spriteFps;
    info.filterMode         = (uint8_t)filterMode;
    info.wrapModeU          = (uint8_t)wrapU;
    info.wrapModeV          = (uint8_t)wrapV;

    remixapi_MaterialHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.CreateMaterial(&info, &handle);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_updateMaterial(JNIEnv*, jclass,
    jlong h, jlong hash,
    jlong albedoTex, jlong normalTex, jlong tangentTex, jlong emissiveTex,
    jfloat emIntensity, jfloat emR, jfloat emG, jfloat emB,
    jint spriteRow, jint spriteCol, jint spriteFps,
    jint filterMode, jint wrapU, jint wrapV,
    jlong pNext)
{
    if (!g_inited || !g_remix.UpdateMaterial) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;

    remixapi_MaterialInfo info = {};
    info.sType              = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO;
    info.pNext              = (void*)(uintptr_t)pNext;
    info.hash               = (uint64_t)hash;
    info.albedoTexture      = (remixapi_TextureHandle)(uintptr_t)albedoTex;
    info.normalTexture      = (remixapi_TextureHandle)(uintptr_t)normalTex;
    info.tangentTexture     = (remixapi_TextureHandle)(uintptr_t)tangentTex;
    info.emissiveTexture    = (remixapi_TextureHandle)(uintptr_t)emissiveTex;
    info.emissiveIntensity  = emIntensity;
    info.emissiveColorConstant = { emR, emG, emB };
    info.spriteSheetRow     = (uint8_t)spriteRow;
    info.spriteSheetCol     = (uint8_t)spriteCol;
    info.spriteSheetFps     = (uint8_t)spriteFps;
    info.filterMode         = (uint8_t)filterMode;
    info.wrapModeU          = (uint8_t)wrapU;
    info.wrapModeV          = (uint8_t)wrapV;

    return (jint)g_remix.UpdateMaterial(
        (remixapi_MaterialHandle)(uintptr_t)h, &info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_destroyMaterial(JNIEnv*, jclass, jlong h)
{
    if (!g_inited || !g_remix.DestroyMaterial) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.DestroyMaterial((remixapi_MaterialHandle)(uintptr_t)h);
}

// ─── textures ────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_createTextureFromMemory(JNIEnv* env, jclass,
    jlong hash, jobject pixelData, jint width, jint height, jint format, jint purpose)
{
    if (!g_inited || !g_remix.CreateTextureFromMemory) return 0;

    remixapi_TextureFromMemoryInfo info = {};
    info.sType     = REMIXAPI_STRUCT_TYPE_TEXTURE_FROM_MEMORY_INFO;
    info.hash      = (uint64_t)hash;
    info.pixelData = (const uint8_t*)env->GetDirectBufferAddress(pixelData);
    info.width     = (uint32_t)width;
    info.height    = (uint32_t)height;
    info.format    = (remixapi_TextureFormat)format;
    info.purpose   = (remixapi_TexturePurpose)purpose;

    remixapi_TextureHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.CreateTextureFromMemory(&info, &handle);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_createTextureFromFile(JNIEnv* env, jclass, jstring jPath)
{
    if (!g_inited || !g_remix.CreateTextureFromFile) return 0;

    const char* utf8 = env->GetStringUTFChars(jPath, nullptr);
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    wchar_t* wpath = (wchar_t*)_alloca(wlen * sizeof(wchar_t));
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wpath, wlen);
    env->ReleaseStringUTFChars(jPath, utf8);

    remixapi_TextureHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.CreateTextureFromFile(wpath, &handle);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_destroyTexture(JNIEnv*, jclass, jlong h)
{
    if (!g_inited || !g_remix.DestroyTexture) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.DestroyTexture((remixapi_TextureHandle)(uintptr_t)h);
}

// Returns bytes-per-pixel for a given remixapi_TextureFormat, or 0 for an
// unrecognized format. Mirrors the table in the dxvk-side textureBytesPerPixel
// helper; keep these in sync if the format enum grows.
static uint32_t bytesPerPixel(remixapi_TextureFormat fmt) {
    switch (fmt) {
        case REMIXAPI_TEXTURE_FORMAT_RGBA8_UNORM:
        case REMIXAPI_TEXTURE_FORMAT_RGBA8_SRGB:
        case REMIXAPI_TEXTURE_FORMAT_BGRA8_UNORM:
        case REMIXAPI_TEXTURE_FORMAT_BGRA8_SRGB:   return 4;
        case REMIXAPI_TEXTURE_FORMAT_R8_UNORM:     return 1;
        case REMIXAPI_TEXTURE_FORMAT_RG8_UNORM:    return 2;
        case REMIXAPI_TEXTURE_FORMAT_RGBA16_SFLOAT: return 8;
        case REMIXAPI_TEXTURE_FORMAT_RGBA32_SFLOAT: return 16;
        default: return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_updateTextureFromMemory(JNIEnv* env, jclass,
    jlongArray jHandles,
    jintArray jEntryPatchCounts,
    jintArray jDstXs, jintArray jDstYs,
    jintArray jWidths, jintArray jHeights,
    jobjectArray jPixelBuffers,
    jintArray jRowPitches, jintArray jFormats)
{
    if (!g_inited) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    // Defense-in-depth: the initialize path already hard-fails when this
    // pointer is null, but re-check here so a null vtable entry never silently
    // drops texture updates.
    if (!g_remix.UpdateTextureFromMemory) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    if (!jHandles || !jEntryPatchCounts ||
        !jDstXs || !jDstYs || !jWidths || !jHeights ||
        !jPixelBuffers || !jRowPitches || !jFormats) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    jsize entryCount = env->GetArrayLength(jHandles);
    if (entryCount <= 0 ||
        env->GetArrayLength(jEntryPatchCounts) != entryCount) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    std::vector<jlong> handles((size_t)entryCount);
    std::vector<jint>  entryPatchCounts((size_t)entryCount);
    env->GetLongArrayRegion(jHandles, 0, entryCount, handles.data());
    env->GetIntArrayRegion(jEntryPatchCounts, 0, entryCount, entryPatchCounts.data());

    // Reject zero-size entries and sum to the expected flat-patch count.
    // Accumulating into 64-bit before the INT32_MAX check guards against a
    // caller that overflows the intermediate sum with pathological inputs.
    uint64_t totalPatches64 = 0;
    for (jsize e = 0; e < entryCount; e++) {
        if (!handles[(size_t)e] || entryPatchCounts[(size_t)e] <= 0) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        totalPatches64 += (uint64_t)(uint32_t)entryPatchCounts[(size_t)e];
        if (totalPatches64 > (uint64_t)INT32_MAX) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
    }
    jsize totalPatches = (jsize)totalPatches64;

    if (env->GetArrayLength(jDstXs) != totalPatches ||
        env->GetArrayLength(jDstYs) != totalPatches ||
        env->GetArrayLength(jWidths) != totalPatches ||
        env->GetArrayLength(jHeights) != totalPatches ||
        env->GetArrayLength(jPixelBuffers) != totalPatches ||
        env->GetArrayLength(jRowPitches) != totalPatches ||
        env->GetArrayLength(jFormats) != totalPatches) {
        return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    }

    std::vector<jint> dstXs(totalPatches), dstYs(totalPatches),
        widths(totalPatches), heights(totalPatches),
        rowPitches(totalPatches), formats(totalPatches);
    env->GetIntArrayRegion(jDstXs, 0, totalPatches, dstXs.data());
    env->GetIntArrayRegion(jDstYs, 0, totalPatches, dstYs.data());
    env->GetIntArrayRegion(jWidths, 0, totalPatches, widths.data());
    env->GetIntArrayRegion(jHeights, 0, totalPatches, heights.data());
    env->GetIntArrayRegion(jRowPitches, 0, totalPatches, rowPitches.data());
    env->GetIntArrayRegion(jFormats, 0, totalPatches, formats.data());

    // Upper bound on any single dimension. Prevents uint32 overflow when
    // multiplying width * bpp (bpp is at most 16, so 65535*16 = 1,048,560,
    // comfortably within INT_MAX) and is far larger than any reasonable
    // dynamic-texture patch.
    static constexpr jint kMaxDim = 65535;

    // Pre-scan + marshal all patches into one flat vector. We validate every
    // patch up-front so a bad entry aborts the whole batch before any native
    // call runs: no partial submissions.
    std::vector<remixapi_TexturePatch> patches((size_t)totalPatches);
    for (jsize i = 0; i < totalPatches; i++) {
        // Reject negative coordinates, non-positive / oversize dimensions.
        // (Java ints are signed; casting a negative to uint32_t would yield a
        // huge unsigned value that the native side cannot sanity-check.)
        if (dstXs[(size_t)i] < 0 || dstYs[(size_t)i] < 0 ||
            widths[(size_t)i] <= 0 || heights[(size_t)i] <= 0 ||
            widths[(size_t)i] > kMaxDim || heights[(size_t)i] > kMaxDim ||
            rowPitches[(size_t)i] < 0) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        uint32_t bpp = bytesPerPixel((remixapi_TextureFormat)formats[(size_t)i]);
        if (bpp == 0) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        // Compute row size in 64-bit to dodge overflow, then verify it fits in
        // the signed range we compare against later.
        uint64_t rowBytes = (uint64_t)(uint32_t)widths[(size_t)i] * (uint64_t)bpp;
        if (rowBytes > (uint64_t)INT32_MAX) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }
        // rowPitch must cover at least one full row of pixel data.
        if ((uint64_t)(uint32_t)rowPitches[(size_t)i] < rowBytes) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        jobject pixelBuffer = env->GetObjectArrayElement(jPixelBuffers, i);
        if (!pixelBuffer) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        void* pixelData = env->GetDirectBufferAddress(pixelBuffer);
        jlong bufCapacity = env->GetDirectBufferCapacity(pixelBuffer);
        env->DeleteLocalRef(pixelBuffer);
        if (!pixelData || bufCapacity < 0) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        // Required bytes: (height - 1) full-pitch rows plus one partial row of
        // actual pixel data. The tail row does not need rowPitch padding.
        uint64_t requiredBytes =
            (uint64_t)((uint32_t)heights[(size_t)i] - 1u) *
                (uint64_t)(uint32_t)rowPitches[(size_t)i] +
            rowBytes;
        if ((uint64_t)bufCapacity < requiredBytes) {
            return REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
        }

        remixapi_TexturePatch& patch = patches[(size_t)i];
        patch.dstX = (uint32_t)dstXs[(size_t)i];
        patch.dstY = (uint32_t)dstYs[(size_t)i];
        patch.width = (uint32_t)widths[(size_t)i];
        patch.height = (uint32_t)heights[(size_t)i];
        patch.pixelData = (const uint8_t*)pixelData;
        patch.rowPitch = (uint32_t)rowPitches[(size_t)i];
        patch.format = (remixapi_TextureFormat)formats[(size_t)i];
    }

    // Carve the flat patches[] buffer into per-entry sub-ranges. Entry pointers
    // reference patches[] in place, with no extra copy, so patches must outlive
    // the native call (it does).
    std::vector<remixapi_TextureBatchEntry> entries((size_t)entryCount);
    size_t flat = 0;
    for (jsize e = 0; e < entryCount; e++) {
        remixapi_TextureBatchEntry& entry = entries[(size_t)e];
        entry.handle = (remixapi_TextureHandle)(uintptr_t)handles[(size_t)e];
        entry.patchCount = (uint32_t)entryPatchCounts[(size_t)e];
        entry.patches = patches.data() + flat;
        flat += (size_t)entryPatchCounts[(size_t)e];
    }

    remixapi_TexturePatchInfo info = {};
    info.sType = REMIXAPI_STRUCT_TYPE_TEXTURE_PATCH_INFO;
    info.entryCount = (uint32_t)entryCount;
    info.entries = entries.data();
    return (jint)g_remix.UpdateTextureFromMemory(&info);
}

// ─── meshes ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_createMesh(JNIEnv* env, jclass,
    jlong hash, jint surfaceCount,
    jobjectArray vertexBuffers, jintArray vertexCounts,
    jobjectArray indexBuffers, jintArray indexCounts,
    jlongArray materialHandles,
    jintArray flags,
    jintArray bonesPerVertex,
    jobjectArray blendWeightBuffers, jintArray blendWeightCounts,
    jobjectArray blendIndexBuffers, jintArray blendIndexCounts)
{
    if (!g_inited || !g_remix.CreateMesh) return 0;

    remixapi_MeshInfoSurfaceTriangles stackBuf[MAX_STACK_SURFACES];
    remixapi_MeshInfoSurfaceTriangles* surfs = buildSurfaces(
        env, surfaceCount,
        vertexBuffers, vertexCounts, indexBuffers, indexCounts,
        materialHandles, flags, bonesPerVertex,
        blendWeightBuffers, blendWeightCounts, blendIndexBuffers, blendIndexCounts,
        stackBuf);

    remixapi_MeshInfo info = {};
    info.sType          = REMIXAPI_STRUCT_TYPE_MESH_INFO;
    info.hash           = (uint64_t)hash;
    info.surfaces_values = surfs;
    info.surfaces_count = (uint32_t)surfaceCount;

    remixapi_MeshHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.CreateMesh(&info, &handle);

    freeSurfaces(surfs, stackBuf);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_destroyMesh(JNIEnv*, jclass, jlong h)
{
    if (!g_inited || !g_remix.DestroyMesh) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.DestroyMesh((remixapi_MeshHandle)(uintptr_t)h);
}

// ─── camera ──────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_setupCamera(JNIEnv* env, jclass,
    jint type, jfloatArray jView, jfloatArray jProj, jlong pNext)
{
    if (!g_inited || !g_remix.SetupCamera) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;

    remixapi_CameraInfo info = {};
    info.sType = REMIXAPI_STRUCT_TYPE_CAMERA_INFO;
    info.pNext = (void*)(uintptr_t)pNext;
    info.type  = (remixapi_CameraType)type;

    jfloat* v = (jfloat*)env->GetPrimitiveArrayCritical(jView, nullptr);
    memcpy(&info.view, v, 16 * sizeof(float));
    env->ReleasePrimitiveArrayCritical(jView, v, JNI_ABORT);

    jfloat* p = (jfloat*)env->GetPrimitiveArrayCritical(jProj, nullptr);
    memcpy(&info.projection, p, 16 * sizeof(float));
    env->ReleasePrimitiveArrayCritical(jProj, p, JNI_ABORT);

    return (jint)g_remix.SetupCamera(&info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_setCameraMediumMaterial(JNIEnv*, jclass, jlong materialHandle)
{
    if (!g_inited || !g_remix.SetCameraMediumMaterial) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;

    remixapi_CameraMediumInfo info = {};
    info.sType = REMIXAPI_STRUCT_TYPE_CAMERA_MEDIUM_INFO;
    info.medium = (remixapi_MaterialHandle)(uintptr_t)materialHandle;

    return (jint)g_remix.SetCameraMediumMaterial(&info);
}

// ─── instances ───────────────────────────────────────────────────────────

static void fillInstanceInfo(JNIEnv* env, remixapi_InstanceInfo& info,
    jlong meshHandle, jfloatArray jTransform, jint categoryFlags,
    jint doubleSided, jlong pNext)
{
    memset(&info, 0, sizeof(info));
    info.sType         = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO;
    info.pNext         = (void*)(uintptr_t)pNext;
    info.categoryFlags = (uint32_t)categoryFlags;
    info.mesh          = (remixapi_MeshHandle)(uintptr_t)meshHandle;
    info.doubleSided   = (uint32_t)doubleSided;

    jfloat* t = (jfloat*)env->GetPrimitiveArrayCritical(jTransform, nullptr);
    memcpy(&info.transform, t, 12 * sizeof(float));
    env->ReleasePrimitiveArrayCritical(jTransform, t, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_drawInstance(JNIEnv* env, jclass,
    jlong meshHandle, jfloatArray jTransform, jint categoryFlags,
    jint doubleSided, jlong pNext)
{
    if (!g_inited || !g_remix.DrawInstance) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;

    remixapi_InstanceInfo info;
    fillInstanceInfo(env, info, meshHandle, jTransform, categoryFlags,
                     doubleSided, pNext);
    return (jint)g_remix.DrawInstance(&info);
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_sceneInstanceAdd(JNIEnv* env, jclass,
    jlong meshHandle, jfloatArray jTransform, jint categoryFlags,
    jint doubleSided, jlong pNext)
{
    if (!g_inited || !g_remix.SceneInstanceAdd) return 0;

    remixapi_InstanceInfo info;
    fillInstanceInfo(env, info, meshHandle, jTransform, categoryFlags,
                     doubleSided, pNext);

    remixapi_SceneInstanceHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.SceneInstanceAdd(&info, &handle);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_sceneInstanceUpdate(JNIEnv* env, jclass,
    jlong sceneInstanceHandle, jlong meshHandle, jfloatArray jTransform,
    jint categoryFlags, jint doubleSided, jlong pNext)
{
    if (!g_inited || !g_remix.SceneInstanceUpdate) {
        return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }

    remixapi_InstanceInfo info;
    fillInstanceInfo(env, info, meshHandle, jTransform, categoryFlags,
                     doubleSided, pNext);
    return (jint)g_remix.SceneInstanceUpdate(
        (remixapi_SceneInstanceHandle)(uintptr_t)sceneInstanceHandle, &info);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_sceneInstanceRemove(JNIEnv*, jclass,
    jlong sceneInstanceHandle)
{
    if (!g_inited || !g_remix.SceneInstanceRemove) {
        return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    }
    return (jint)g_remix.SceneInstanceRemove(
        (remixapi_SceneInstanceHandle)(uintptr_t)sceneInstanceHandle);
}

// ─── lights ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_createLight(JNIEnv*, jclass,
    jlong hash, jfloat radR, jfloat radG, jfloat radB, jlong pNext)
{
    if (!g_inited || !g_remix.CreateLight) return 0;

    remixapi_LightInfo info = {};
    info.sType    = REMIXAPI_STRUCT_TYPE_LIGHT_INFO;
    info.pNext    = (void*)(uintptr_t)pNext;
    info.hash     = (uint64_t)hash;
    info.radiance = { radR, radG, radB };

    remixapi_LightHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.CreateLight(&info, &handle);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_destroyLight(JNIEnv*, jclass, jlong h)
{
    if (!g_inited || !g_remix.DestroyLight) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.DestroyLight((remixapi_LightHandle)(uintptr_t)h);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_drawLightInstance(JNIEnv*, jclass, jlong h)
{
    if (!g_inited || !g_remix.DrawLightInstance) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.DrawLightInstance((remixapi_LightHandle)(uintptr_t)h);
}

// ─── config ──────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_setConfigVariable(JNIEnv* env, jclass,
    jstring jKey, jstring jValue)
{
    if (!g_inited || !g_remix.SetConfigVariable) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    const char* key   = env->GetStringUTFChars(jKey, nullptr);
    const char* value = env->GetStringUTFChars(jValue, nullptr);
    jint rc = (jint)g_remix.SetConfigVariable(key, value);
    env->ReleaseStringUTFChars(jKey, key);
    env->ReleaseStringUTFChars(jValue, value);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_getConfigVariableResolved(JNIEnv* env, jclass,
    jstring jKey, jbyteArray jValue)
{
    if (!g_inited || !g_remix.GetConfigVariableResolved) return -REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    if (!jKey) return -REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS;
    const char* key = env->GetStringUTFChars(jKey, nullptr);
    jbyte* value = jValue ? env->GetByteArrayElements(jValue, nullptr) : nullptr;
    remixapi_ConfigVariableResolvedInfo info = remixapi_ConfigVariableResolvedInfo_init();
    info.key = key;
    info.value = (char*)value;
    info.valueSize = jValue ? (uint32_t)env->GetArrayLength(jValue) : 0;
    remixapi_ErrorCode rc = g_remix.GetConfigVariableResolved(&info);
    if (value) {
        env->ReleaseByteArrayElements(jValue, value,
            rc == REMIXAPI_ERROR_CODE_SUCCESS ? 0 : JNI_ABORT);
    }
    env->ReleaseStringUTFChars(jKey, key);
    // Returning the size, rather than filling an out-array, lets a per-frame caller read without allocating.
    if (rc == REMIXAPI_ERROR_CODE_SUCCESS || rc == REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL) {
        return (jint)info.valueSize;
    }
    return -(jint)rc;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_acquireConfigLayer(JNIEnv* env, jclass,
    jstring jPath, jfloat priority, jfloat blendThreshold)
{
    if (!g_inited || !g_remix.AcquireConfigLayer) return 0;
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    remixapi_ConfigLayerHandle handle = nullptr;
    remixapi_ErrorCode rc = g_remix.AcquireConfigLayer(
        path, (float)priority, (float)blendThreshold, &handle);
    env->ReleaseStringUTFChars(jPath, path);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)handle : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_setConfigLayerBlending(JNIEnv*, jclass,
    jlong handle, jfloat strength, jfloat blendThreshold)
{
    if (!g_inited || !g_remix.SetConfigLayerBlending) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.SetConfigLayerBlending(
        (remixapi_ConfigLayerHandle)(uintptr_t)handle,
        (float)strength,
        (float)blendThreshold);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_releaseConfigLayer(JNIEnv*, jclass, jlong handle)
{
    if (!g_inited || !g_remix.ReleaseConfigLayer) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.ReleaseConfigLayer((remixapi_ConfigLayerHandle)(uintptr_t)handle);
}

/** @return JNI_TRUE when the loaded runtime exposes the config-layer API (API >= 0.9.0) */
JNIEXPORT jboolean JNICALL
Java_com_nvidia_rtxremix_RemixBridge_isConfigLayersSupported(JNIEnv*, jclass)
{
    return (g_inited
        && g_remix.AcquireConfigLayer
        && g_remix.SetConfigLayerBlending
        && g_remix.ReleaseConfigLayer) ? JNI_TRUE : JNI_FALSE;
}

// ─── DXVK interop ────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkCreateD3D9(JNIEnv*, jclass, jboolean editorMode)
{
    if (!g_inited || !g_remix.dxvk_CreateD3D9) return 0;
    IDirect3D9Ex* pD3D9 = nullptr;
    remixapi_ErrorCode rc = g_remix.dxvk_CreateD3D9(editorMode ? 1 : 0, &pD3D9);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)(uintptr_t)pD3D9 : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkRegisterD3D9Device(JNIEnv*, jclass, jlong device)
{
    if (!g_inited || !g_remix.dxvk_RegisterD3D9Device) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.dxvk_RegisterD3D9Device((IDirect3DDevice9Ex*)(uintptr_t)device);
}

JNIEXPORT jlongArray JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkGetExternalSwapchain(JNIEnv* env, jclass)
{
    if (!g_inited || !g_remix.dxvk_GetExternalSwapchain) return nullptr;
    uint64_t vkImage = 0, semDone = 0, semResume = 0;
    remixapi_ErrorCode rc = g_remix.dxvk_GetExternalSwapchain(&vkImage, &semDone, &semResume);
    if (rc != REMIXAPI_ERROR_CODE_SUCCESS) return nullptr;

    jlongArray arr = env->NewLongArray(3);
    jlong vals[3] = { (jlong)vkImage, (jlong)semDone, (jlong)semResume };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkGetVkImage(JNIEnv*, jclass, jlong surface)
{
    if (!g_inited || !g_remix.dxvk_GetVkImage) return 0;
    uint64_t vkImage = 0;
    remixapi_ErrorCode rc = g_remix.dxvk_GetVkImage(
        (IDirect3DSurface9*)(uintptr_t)surface, &vkImage);
    return (rc == REMIXAPI_ERROR_CODE_SUCCESS) ? (jlong)vkImage : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkCopyRenderingOutput(JNIEnv*, jclass,
    jlong surface, jint type)
{
    if (!g_inited || !g_remix.dxvk_CopyRenderingOutput) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    return (jint)g_remix.dxvk_CopyRenderingOutput(
        (IDirect3DSurface9*)(uintptr_t)surface,
        (remixapi_dxvk_CopyRenderingOutputType)type);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_dxvkSetDefaultOutput(JNIEnv*, jclass,
    jint type, jfloat r, jfloat g, jfloat b, jfloat a)
{
    if (!g_inited || !g_remix.dxvk_SetDefaultOutput) return REMIXAPI_ERROR_CODE_NOT_INITIALIZED;
    remixapi_Float4D color = { r, g, b, a };
    return (jint)g_remix.dxvk_SetDefaultOutput(
        (remixapi_dxvk_CopyRenderingOutputType)type, &color);
}

// ─── D3D9 COM helpers ────────────────────────────────────────────────────
// These replicate D3D9Device.java's vtable calls in C++ where they belong.

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_d3d9CreateDevice(JNIEnv*, jclass,
    jlong pD3D9ExJ, jlong hwndJ, jint width, jint height)
{
    auto pD3D9Ex = (IDirect3D9Ex*)(uintptr_t)pD3D9ExJ;
    HWND hwnd    = (HWND)(uintptr_t)hwndJ;

    // Build D3DPRESENT_PARAMETERS on the stack
    struct {
        UINT  BackBufferWidth;
        UINT  BackBufferHeight;
        UINT  BackBufferFormat;       // D3DFMT_UNKNOWN = 0
        UINT  BackBufferCount;
        UINT  MultiSampleType;        // D3DMULTISAMPLE_NONE = 0
        DWORD MultiSampleQuality;
        UINT  SwapEffect;             // D3DSWAPEFFECT_DISCARD = 1
        HWND  hDeviceWindow;
        BOOL  Windowed;
        BOOL  EnableAutoDepthStencil;
        UINT  AutoDepthStencilFormat;
        DWORD Flags;
        UINT  FullScreen_RefreshRateInHz;
        UINT  PresentationInterval;
    } pp = {};
    pp.BackBufferWidth  = (UINT)width;
    pp.BackBufferHeight = (UINT)height;
    pp.SwapEffect       = 1; // D3DSWAPEFFECT_DISCARD
    pp.hDeviceWindow    = hwnd;
    pp.Windowed         = TRUE;

    DWORD behaviorFlags = 0x04 /*MULTITHREADED*/ | 0x20 /*SOFTWARE_VP*/ | 0x800 /*NOWINDOWCHANGES*/;

    // IDirect3D9Ex vtable[20] = CreateDeviceEx
    void** vtable = *(void***)pD3D9Ex;
    typedef HRESULT(__stdcall* PFN_CreateDeviceEx)(
        IDirect3D9Ex*, UINT, UINT, HWND, DWORD, void*, void*, void**);
    auto pfnCreate = (PFN_CreateDeviceEx)vtable[20];

    IDirect3DDevice9Ex* pDevice = nullptr;
    HRESULT hr = pfnCreate(pD3D9Ex, 0, 1/*HAL*/, nullptr, behaviorFlags, &pp, nullptr, (void**)&pDevice);
    return (SUCCEEDED(hr) && pDevice) ? (jlong)(uintptr_t)pDevice : 0;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_d3d9CreateRenderTarget(JNIEnv*, jclass,
    jlong pDeviceJ, jint width, jint height, jint format)
{
    auto pDevice = (IDirect3DDevice9Ex*)(uintptr_t)pDeviceJ;
    void** vtable = *(void***)pDevice;

    // IDirect3DDevice9 vtable[28] = CreateRenderTarget
    typedef HRESULT(__stdcall* PFN_CreateRT)(
        IDirect3DDevice9Ex*, UINT, UINT, UINT, UINT, DWORD, BOOL, void**, void*);
    auto pfnCreateRT = (PFN_CreateRT)vtable[28];

    IDirect3DSurface9* pSurface = nullptr;
    HRESULT hr = pfnCreateRT(pDevice, (UINT)width, (UINT)height,
                              (UINT)format, 0/*NONE*/, 0, FALSE,
                              (void**)&pSurface, nullptr);
    return (SUCCEEDED(hr) && pSurface) ? (jlong)(uintptr_t)pSurface : 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_d3d9Reset(JNIEnv*, jclass,
    jlong pDeviceJ, jint width, jint height)
{
    auto pDevice = (IDirect3DDevice9Ex*)(uintptr_t)pDeviceJ;
    void** vtable = *(void***)pDevice;

    struct {
        UINT  BackBufferWidth;
        UINT  BackBufferHeight;
        UINT  BackBufferFormat;
        UINT  BackBufferCount;
        UINT  MultiSampleType;
        DWORD MultiSampleQuality;
        UINT  SwapEffect;
        HWND  hDeviceWindow;
        BOOL  Windowed;
        BOOL  EnableAutoDepthStencil;
        UINT  AutoDepthStencilFormat;
        DWORD Flags;
        UINT  FullScreen_RefreshRateInHz;
        UINT  PresentationInterval;
    } pp = {};
    pp.BackBufferWidth  = (UINT)width;
    pp.BackBufferHeight = (UINT)height;
    pp.BackBufferFormat = 22; // D3DFMT_X8R8G8B8
    pp.BackBufferCount  = 1;
    pp.SwapEffect       = 1;
    pp.Windowed         = TRUE;

    // vtable[16] = Reset
    typedef HRESULT(__stdcall* PFN_Reset)(IDirect3DDevice9Ex*, void*);
    auto pfnReset = (PFN_Reset)vtable[16];
    return (jint)pfnReset(pDevice, &pp);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_d3d9Present(JNIEnv*, jclass, jlong pDeviceJ)
{
    auto pDevice = (IDirect3DDevice9Ex*)(uintptr_t)pDeviceJ;
    void** vtable = *(void***)pDevice;
    // vtable[17] = Present
    typedef HRESULT(__stdcall* PFN_Present)(IDirect3DDevice9Ex*, void*, void*, HWND, void*);
    auto pfnPresent = (PFN_Present)vtable[17];
    return (jint)pfnPresent(pDevice, nullptr, nullptr, nullptr, nullptr);
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_d3d9Release(JNIEnv*, jclass, jlong comPtr)
{
    if (comPtr == 0) return 0;
    auto pUnk = (IUnknown*)(uintptr_t)comPtr;
    return (jint)pUnk->Release();
}

// ─── extension allocation ────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocOpaqueExt(JNIEnv*, jclass,
    jlong roughTex, jlong metalTex, jfloat anisotropy,
    jfloat albR, jfloat albG, jfloat albB,
    jfloat opacity, jfloat roughness, jfloat metallic,
    jboolean hasThinFilm, jfloat thinFilmThickness, jboolean alphaIsThinFilm,
    jlong heightTex, jfloat displaceIn,
    jboolean useDrawCallAlpha,
    jboolean hasBlendType, jint blendType, jboolean invertedBlend,
    jint alphaTestType, jint alphaRefValue, jfloat displaceOut,
    jlong chainNext)
{
    auto* ext = new remixapi_MaterialInfoOpaqueEXT{};
    ext->sType                      = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_EXT;
    ext->pNext                      = (void*)(uintptr_t)chainNext;
    ext->roughnessTexture           = (remixapi_TextureHandle)(uintptr_t)roughTex;
    ext->metallicTexture            = (remixapi_TextureHandle)(uintptr_t)metalTex;
    ext->anisotropy                 = anisotropy;
    ext->albedoConstant             = { albR, albG, albB };
    ext->opacityConstant            = opacity;
    ext->roughnessConstant          = roughness;
    ext->metallicConstant           = metallic;
    ext->thinFilmThickness_hasvalue = hasThinFilm ? 1u : 0u;
    ext->thinFilmThickness_value    = thinFilmThickness;
    ext->alphaIsThinFilmThickness   = alphaIsThinFilm ? 1u : 0u;
    ext->heightTexture              = (remixapi_TextureHandle)(uintptr_t)heightTex;
    ext->displaceIn                 = displaceIn;
    ext->useDrawCallAlphaState      = useDrawCallAlpha ? 1u : 0u;
    ext->blendType_hasvalue         = hasBlendType ? 1u : 0u;
    ext->blendType_value            = blendType;
    ext->invertedBlend              = invertedBlend ? 1u : 0u;
    ext->alphaTestType              = alphaTestType;
    ext->alphaReferenceValue        = (uint8_t)alphaRefValue;
    ext->displaceOut                = displaceOut;
    // DLSS NR enabled for all materials
    ext->enableDlssControlMask             = 1u;
    ext->dlssControlMaskIntensity          = 1.0f;
    ext->dlssControlMaskToneStrength       = 0.3f;
    ext->dlssControlMaskStructuralStrength = 0.7f;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocOpaqueSubsurfaceExt(JNIEnv*, jclass,
    jlong transTex, jlong thickTex, jlong ssaTex,
    jfloat transR, jfloat transG, jfloat transB,
    jfloat measDist,
    jfloat ssaR, jfloat ssaG, jfloat ssaB,
    jfloat volAniso, jboolean diffProfile,
    jfloat radR, jfloat radG, jfloat radB,
    jfloat radScale, jfloat maxSampleRad,
    jlong radTex,
    jlong chainNext)
{
    auto* ext = new remixapi_MaterialInfoOpaqueSubsurfaceEXT{};
    ext->sType                                = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT;
    ext->pNext                                = (void*)(uintptr_t)chainNext;
    ext->subsurfaceTransmittanceTexture       = (remixapi_TextureHandle)(uintptr_t)transTex;
    ext->subsurfaceThicknessTexture           = (remixapi_TextureHandle)(uintptr_t)thickTex;
    ext->subsurfaceSingleScatteringAlbedoTexture = (remixapi_TextureHandle)(uintptr_t)ssaTex;
    ext->subsurfaceTransmittanceColor         = { transR, transG, transB };
    ext->subsurfaceMeasurementDistance         = measDist;
    ext->subsurfaceSingleScatteringAlbedo     = { ssaR, ssaG, ssaB };
    ext->subsurfaceVolumetricAnisotropy       = volAniso;
    ext->subsurfaceDiffusionProfile           = diffProfile ? 1u : 0u;
    ext->subsurfaceRadius                     = { radR, radG, radB };
    ext->subsurfaceRadiusScale                = radScale;
    ext->subsurfaceMaxSampleRadius            = maxSampleRad;
    ext->subsurfaceRadiusTexture              = (remixapi_TextureHandle)(uintptr_t)radTex;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocTranslucentExt(JNIEnv*, jclass,
    jlong transTex, jfloat ior,
    jfloat transR, jfloat transG, jfloat transB,
    jfloat measDist,
    jboolean hasThinWall, jfloat thinWall,
    jboolean useDiffuse,
    jlong chainNext)
{
    auto* ext = new remixapi_MaterialInfoTranslucentEXT{};
    ext->sType                          = REMIXAPI_STRUCT_TYPE_MATERIAL_INFO_TRANSLUCENT_EXT;
    ext->pNext                          = (void*)(uintptr_t)chainNext;
    ext->transmittanceTexture           = (remixapi_TextureHandle)(uintptr_t)transTex;
    ext->refractiveIndex                = ior;
    ext->transmittanceColor             = { transR, transG, transB };
    ext->transmittanceMeasurementDistance = measDist;
    ext->thinWallThickness_hasvalue     = hasThinWall ? 1u : 0u;
    ext->thinWallThickness_value        = thinWall;
    ext->useDiffuseLayer                = useDiffuse ? 1u : 0u;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocBlendExt(JNIEnv*, jclass,
    jboolean alphaTestEnabled, jint alphaTestRef, jint alphaTestCompareOp,
    jboolean alphaBlendEnabled,
    jint srcColor, jint dstColor, jint colorOp,
    jint tcArg1, jint tcArg2, jint tcOp,
    jint taArg1, jint taArg2, jint taOp,
    jint tFactor, jboolean isTexFactor,
    jint srcAlpha, jint dstAlpha, jint alphaOp,
    jint writeMask, jboolean isVertexBaked,
    jlong chainNext)
{
    auto* ext = new remixapi_InstanceInfoBlendEXT{};
    ext->sType                       = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BLEND_EXT;
    ext->pNext                       = (void*)(uintptr_t)chainNext;
    ext->alphaTestEnabled            = alphaTestEnabled ? 1u : 0u;
    ext->alphaTestReferenceValue     = (uint8_t)alphaTestRef;
    ext->alphaTestCompareOp          = (uint32_t)alphaTestCompareOp;
    ext->alphaBlendEnabled           = alphaBlendEnabled ? 1u : 0u;
    ext->srcColorBlendFactor         = (uint32_t)srcColor;
    ext->dstColorBlendFactor         = (uint32_t)dstColor;
    ext->colorBlendOp                = (uint32_t)colorOp;
    ext->textureColorArg1Source      = (uint32_t)tcArg1;
    ext->textureColorArg2Source      = (uint32_t)tcArg2;
    ext->textureColorOperation       = (uint32_t)tcOp;
    ext->textureAlphaArg1Source      = (uint32_t)taArg1;
    ext->textureAlphaArg2Source      = (uint32_t)taArg2;
    ext->textureAlphaOperation       = (uint32_t)taOp;
    ext->tFactor                     = (uint32_t)tFactor;
    ext->isTextureFactorBlend        = isTexFactor ? 1u : 0u;
    ext->srcAlphaBlendFactor         = (uint32_t)srcAlpha;
    ext->dstAlphaBlendFactor         = (uint32_t)dstAlpha;
    ext->alphaBlendOp                = (uint32_t)alphaOp;
    ext->writeMask                   = (uint32_t)writeMask;
    ext->isVertexColorBakedLighting  = isVertexBaked ? 1u : 0u;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocBoneTransformsExt(JNIEnv* env, jclass,
    jobject transforms, jint count, jlong chainNext)
{
    auto* ext = new remixapi_InstanceInfoBoneTransformsEXT{};
    ext->sType                = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT;
    ext->pNext                = (void*)(uintptr_t)chainNext;
    ext->boneTransforms_count = (uint32_t)count;

    // Copy the transform data so the ByteBuffer can be reused immediately
    size_t bytes = (size_t)count * 48; // 12 floats * 4 bytes
    auto* copy = new uint8_t[bytes];
    memcpy(copy, env->GetDirectBufferAddress(transforms), bytes);
    ext->boneTransforms_values = (const remixapi_Transform*)copy;

    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocGpuInstancingExt(JNIEnv* env, jclass,
    jobject transforms, jint count, jlong chainNext)
{
    auto* ext = new remixapi_InstanceInfoGpuInstancingEXT{};
    ext->sType                    = REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_GPU_INSTANCING_EXT;
    ext->pNext                    = (void*)(uintptr_t)chainNext;
    ext->instanceTransforms_count = (uint32_t)count;

    size_t bytes = (size_t)count * 48;
    auto* copy = new uint8_t[bytes];
    memcpy(copy, env->GetDirectBufferAddress(transforms), bytes);
    ext->instanceTransforms_values = (const remixapi_Transform*)copy;

    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocSphereExt(JNIEnv*, jclass,
    jfloat px, jfloat py, jfloat pz, jfloat radius,
    jboolean hasShaping,
    jfloat sdx, jfloat sdy, jfloat sdz,
    jfloat coneAngle, jfloat coneSoft, jfloat focusExp,
    jfloat volRadScale,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoSphereEXT{};
    ext->sType                  = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_SPHERE_EXT;
    ext->pNext                  = (void*)(uintptr_t)chainNext;
    ext->position               = { px, py, pz };
    ext->radius                 = radius;
    ext->shaping_hasvalue       = hasShaping ? 1u : 0u;
    ext->shaping_value.direction     = { sdx, sdy, sdz };
    ext->shaping_value.coneAngleDegrees = coneAngle;
    ext->shaping_value.coneSoftness  = coneSoft;
    ext->shaping_value.focusExponent = focusExp;
    ext->volumetricRadianceScale = volRadScale;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocDistantExt(JNIEnv*, jclass,
    jfloat dx, jfloat dy, jfloat dz,
    jfloat angDiam, jfloat volRadScale,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoDistantEXT{};
    ext->sType                   = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISTANT_EXT;
    ext->pNext                   = (void*)(uintptr_t)chainNext;
    ext->direction               = { dx, dy, dz };
    ext->angularDiameterDegrees  = angDiam;
    ext->volumetricRadianceScale = volRadScale;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocRectExt(JNIEnv*, jclass,
    jfloat px, jfloat py, jfloat pz,
    jfloat xax, jfloat xay, jfloat xaz, jfloat xSize,
    jfloat yax, jfloat yay, jfloat yaz, jfloat ySize,
    jfloat dx, jfloat dy, jfloat dz,
    jboolean hasShaping,
    jfloat sdx, jfloat sdy, jfloat sdz,
    jfloat coneAngle, jfloat coneSoft, jfloat focusExp,
    jfloat volRadScale,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoRectEXT{};
    ext->sType              = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_RECT_EXT;
    ext->pNext              = (void*)(uintptr_t)chainNext;
    ext->position           = { px, py, pz };
    ext->xAxis              = { xax, xay, xaz };
    ext->xSize              = xSize;
    ext->yAxis              = { yax, yay, yaz };
    ext->ySize              = ySize;
    ext->direction          = { dx, dy, dz };
    ext->shaping_hasvalue   = hasShaping ? 1u : 0u;
    ext->shaping_value.direction     = { sdx, sdy, sdz };
    ext->shaping_value.coneAngleDegrees = coneAngle;
    ext->shaping_value.coneSoftness  = coneSoft;
    ext->shaping_value.focusExponent = focusExp;
    ext->volumetricRadianceScale = volRadScale;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocDiskExt(JNIEnv*, jclass,
    jfloat px, jfloat py, jfloat pz,
    jfloat xax, jfloat xay, jfloat xaz, jfloat xRadius,
    jfloat yax, jfloat yay, jfloat yaz, jfloat yRadius,
    jfloat dx, jfloat dy, jfloat dz,
    jboolean hasShaping,
    jfloat sdx, jfloat sdy, jfloat sdz,
    jfloat coneAngle, jfloat coneSoft, jfloat focusExp,
    jfloat volRadScale,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoDiskEXT{};
    ext->sType              = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DISK_EXT;
    ext->pNext              = (void*)(uintptr_t)chainNext;
    ext->position           = { px, py, pz };
    ext->xAxis              = { xax, xay, xaz };
    ext->xRadius            = xRadius;
    ext->yAxis              = { yax, yay, yaz };
    ext->yRadius            = yRadius;
    ext->direction          = { dx, dy, dz };
    ext->shaping_hasvalue   = hasShaping ? 1u : 0u;
    ext->shaping_value.direction     = { sdx, sdy, sdz };
    ext->shaping_value.coneAngleDegrees = coneAngle;
    ext->shaping_value.coneSoftness  = coneSoft;
    ext->shaping_value.focusExponent = focusExp;
    ext->volumetricRadianceScale = volRadScale;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocCylinderExt(JNIEnv*, jclass,
    jfloat px, jfloat py, jfloat pz,
    jfloat radius,
    jfloat ax, jfloat ay, jfloat az,
    jfloat axisLength,
    jfloat volRadScale,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoCylinderEXT{};
    ext->sType                   = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_CYLINDER_EXT;
    ext->pNext                   = (void*)(uintptr_t)chainNext;
    ext->position                = { px, py, pz };
    ext->radius                  = radius;
    ext->axis                    = { ax, ay, az };
    ext->axisLength              = axisLength;
    ext->volumetricRadianceScale = volRadScale;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocDomeExt(JNIEnv* env, jclass,
    jfloatArray jTransform, jlong colorTex, jfloat opacity, jint blendType,
    jlong chainNext)
{
    auto* ext = new remixapi_LightInfoDomeEXT{};
    ext->sType        = REMIXAPI_STRUCT_TYPE_LIGHT_INFO_DOME_EXT;
    ext->pNext        = (void*)(uintptr_t)chainNext;
    ext->colorTexture = (remixapi_TextureHandle)(uintptr_t)colorTex;
    ext->opacity      = opacity;
    ext->blendType    = blendType;

    jfloat* t = (jfloat*)env->GetPrimitiveArrayCritical(jTransform, nullptr);
    memcpy(&ext->transform, t, 12 * sizeof(float));
    env->ReleasePrimitiveArrayCritical(jTransform, t, JNI_ABORT);

    return (jlong)(uintptr_t)ext;
}

JNIEXPORT jlong JNICALL
Java_com_nvidia_rtxremix_RemixBridge_allocCameraParamExt(JNIEnv*, jclass,
    jfloat px, jfloat py, jfloat pz,
    jfloat fx, jfloat fy, jfloat fz,
    jfloat ux, jfloat uy, jfloat uz,
    jfloat rx, jfloat ry, jfloat rz,
    jfloat fovY, jfloat aspect, jfloat nearP, jfloat farP,
    jlong chainNext)
{
    auto* ext = new remixapi_CameraInfoParameterizedEXT{};
    ext->sType          = REMIXAPI_STRUCT_TYPE_CAMERA_INFO_PARAMETERIZED_EXT;
    ext->pNext          = (void*)(uintptr_t)chainNext;
    ext->position       = { px, py, pz };
    ext->forward        = { fx, fy, fz };
    ext->up             = { ux, uy, uz };
    ext->right          = { rx, ry, rz };
    ext->fovYInDegrees  = fovY;
    ext->aspect         = aspect;
    ext->nearPlane      = nearP;
    ext->farPlane       = farP;
    return (jlong)(uintptr_t)ext;
}

JNIEXPORT void JNICALL
Java_com_nvidia_rtxremix_RemixBridge_freeExt(JNIEnv*, jclass, jlong extPtr)
{
    struct ExtHeader {
        uint32_t sType;
        void* pNext;
    };

    void* current = (void*)(uintptr_t)extPtr;
    while (current != nullptr) {
        auto* header = (ExtHeader*)current;
        uint32_t sType = header->sType;
        void* next = header->pNext;

        // For bone/gpu-instancing extensions, also free the copied transform array.
        if (sType == REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_BONE_TRANSFORMS_EXT) {
            auto* bone = (remixapi_InstanceInfoBoneTransformsEXT*)current;
            delete[] (uint8_t*)bone->boneTransforms_values;
        } else if (sType == REMIXAPI_STRUCT_TYPE_INSTANCE_INFO_GPU_INSTANCING_EXT) {
            auto* gpu = (remixapi_InstanceInfoGpuInstancingEXT*)current;
            delete[] (uint8_t*)gpu->instanceTransforms_values;
        }

        // All extension structs are allocated with operator new, so this is safe
        // regardless of the actual type -- they're all POD-like.
        operator delete(current);
        current = next;
    }
}

// ─── Tracy profiling zones callable from Java ─────────────────────────

JNIEXPORT jlong JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracyZoneBegin(
    JNIEnv* env, jclass, jstring name)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!initTracyFunctionsLocked()) return 0;
    const char* cname = env->GetStringUTFChars(name, nullptr);
    uint64_t srcloc = s_allocSrclocName(
        0, "Java", 4, cname, strlen(cname), cname, strlen(cname));
    env->ReleaseStringUTFChars(name, cname);
    TracyCZoneCtx ctx = s_emitZoneBeginAlloc(srcloc, 1);
    return (jlong)ctx.id | ((jlong)ctx.active << 32);
}

JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracyZoneEnd(
    JNIEnv*, jclass, jlong packed)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!packed || !s_emitZoneEnd) return;
    TracyCZoneCtx ctx;
    ctx.id     = (uint32_t)(packed & 0xFFFFFFFF);
    ctx.active = (int32_t)(packed >> 32);
    s_emitZoneEnd(ctx);
}

JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracySetThreadName(
    JNIEnv* env, jclass, jstring name)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!initTracyFunctionsLocked() || !s_setThreadName) return;
    const char* cname = env->GetStringUTFChars(name, nullptr);
    s_setThreadName(cname);
    env->ReleaseStringUTFChars(name, cname);
}

// Tracy identifies a plot by the address of its name and fetches the characters
// lazily, so the pointer handed to ___tracy_emit_plot must stay valid for the life
// of the process. Names are interned once here; the set's nodes never move.
static std::set<std::string> s_plotNames;

static const char* internPlotName(JNIEnv* env, jstring name)
{
    const char* utf8 = env->GetStringUTFChars(name, nullptr);
    if (!utf8) return nullptr;
    const char* stable = s_plotNames.emplace(utf8).first->c_str();
    env->ReleaseStringUTFChars(name, utf8);
    return stable;
}

JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracyPlot(
    JNIEnv* env, jclass, jstring name, jdouble value)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!initTracyFunctionsLocked() || !s_emitPlot) return;
    const char* stable = internPlotName(env, name);
    if (stable) s_emitPlot(stable, (double)value);
}

JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracyMessage(
    JNIEnv* env, jclass, jstring message)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!initTracyFunctionsLocked() || !s_emitMessage) return;
    const char* cmessage = env->GetStringUTFChars(message, nullptr);
    s_emitMessage(cmessage, strlen(cmessage), 0);
    env->ReleaseStringUTFChars(message, cmessage);
}

JNIEXPORT void JNICALL Java_com_nvidia_rtxremix_RemixBridge_tracyFrameMark(
    JNIEnv* env, jclass, jstring name)
{
    std::lock_guard<std::mutex> lock(s_tracyMutex);
    if (!initTracyFunctionsLocked() || !s_emitFrameMark) return;
    const char* cname = nullptr;
    if (name) {
        cname = env->GetStringUTFChars(name, nullptr);
    }
    s_emitFrameMark(cname);
    if (name) {
        env->ReleaseStringUTFChars(name, cname);
    }
}

typedef uint64_t GLuint64;
typedef void (*PFNGLCREATESEMAPHORESNVPROC)(GLsizei n, GLuint* semaphores);
typedef void (*PFNGLSEMAPHOREPARAMETERIVNVPROC)(GLuint semaphore, GLenum pname, const GLint* params);
typedef void (*PFNGLSEMAPHOREPARAMETERUI64VEXTPROC)(GLuint semaphore, GLenum pname, const GLuint64* params);

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_openglSemaphoreParameterui64vEXT(JNIEnv* env, jclass, jint semaphore, jint pname, jlong param0)
{
    if (!g_inited) return -1;

    static PFNGLSEMAPHOREPARAMETERUI64VEXTPROC glSemaphoreParameterui64vEXT = nullptr;
    if (!glSemaphoreParameterui64vEXT) {
      glSemaphoreParameterui64vEXT = (PFNGLSEMAPHOREPARAMETERUI64VEXTPROC)wglGetProcAddress("glSemaphoreParameterui64vEXT");
      if (!glSemaphoreParameterui64vEXT) {
        return -2; // not found
      };
    }

    glSemaphoreParameterui64vEXT((GLuint)semaphore, (GLenum)pname,
                                 (const GLuint64 *)&param0);

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_openglSemaphoreParameterivNV(JNIEnv* env, jclass, jint semaphore, jint pname, jint param0){
    if (!g_inited) return -1;

    static PFNGLSEMAPHOREPARAMETERIVNVPROC glSemaphoreParameterivNV = nullptr;
    if (!glSemaphoreParameterivNV) {
      glSemaphoreParameterivNV = (PFNGLSEMAPHOREPARAMETERIVNVPROC)wglGetProcAddress("glSemaphoreParameterivNV");
      if (!glSemaphoreParameterivNV) {
        return -2; // not found
      };
    }

    glSemaphoreParameterivNV((GLuint)semaphore, (GLenum)pname,
                             (const GLint *)&param0);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_nvidia_rtxremix_RemixBridge_openglCreateSemaphoresNV(JNIEnv* env, jclass)
{
    if (!g_inited) return 0;

    static PFNGLCREATESEMAPHORESNVPROC glCreateSemaphoresNV = nullptr;
    if (!glCreateSemaphoresNV) {
      glCreateSemaphoresNV = (PFNGLCREATESEMAPHORESNVPROC)wglGetProcAddress("glCreateSemaphoresNV");
      if (!glCreateSemaphoresNV) {
        return 0; // not found
      };
    }

    GLuint semaphore = 0;
    glCreateSemaphoresNV(1, &semaphore);
    return (jint)semaphore;
}

} // extern "C"
