/*
 *  eclipsesdl — SDL for the Eclipse Launcher
 *  Copyright (c) 2026 Shadow.
 *
 *  This software is provided 'as-is', without any express or implied
 *  warranty.  In no event will the authors be held liable for any damages
 *  arising from the use of this software.
 *
 *  Permission is granted to anyone to use this software for any purpose,
 *  including commercial applications, and to alter it and redistribute it
 *  freely, subject to the following restrictions:
 *
 *  1. The origin of this software must not be misrepresented; you must not
 *     claim that you wrote the original software. If you use this software
 *     in a product, an acknowledgment in the product documentation would be
 *     appreciated but is not required.
 *  2. Altered source versions must be plainly marked as such, and must not be
 *     misrepresented as being the original software.
 *  3. This notice may not be removed or altered from any source distribution.
 */
#include "SDL_internal.h"

#include "SDL_eclipse.h"

#include <SDL3/SDL_hints.h>
#include <SDL3/SDL_log.h>
#include <SDL3/SDL_video.h>

#ifdef SDL_ECLIPSE_EXEC

#include <dlfcn.h>
#include <pthread.h>

#include <eclipseexec.h>

/*
 * eclipseexec is found, not linked: libSDL3.so carries no dependency on it, so
 * an app that ships one gets the handover and an app that does not still gets
 * a library that loads. The header above supplies every prototype and the
 * layout of the render spec, so nothing here is guessed; dlsym only supplies
 * the addresses.
 *
 * If the library is missing, or a symbol it should have is missing, the whole
 * handover is switched off after one attempt and SDL goes on loading drivers
 * itself. That is a reason to log, never a reason to fail.
 */

typedef void *(*eclipse_acq_egl_fn)(void);
typedef void *(*eclipse_acq_vulkan_fn)(void);
typedef void (*eclipse_preload_vulkan_fn)(void);
typedef bool (*eclipse_set_affinity_fn)(bool);
typedef bool (*eclipse_affinity_enabled_fn)(void);
typedef void (*eclipse_make_affine_fn)(void);
typedef const char *(*eclipse_last_error_fn)(void);

/*
 * Each typedef above is pinned to the prototype in eclipseexec.h: if the two
 * ever drift apart, this fails to compile instead of failing at runtime with a
 * misdeclared call. The typeof is unevaluated, so taking the address here does
 * not make libSDL3.so depend on the library either.
 */
#if defined(__clang__) || defined(__GNUC__)
typedef char eclipse_check_acq_egl[
    __builtin_types_compatible_p(eclipse_acq_egl_fn, __typeof__(&eclipseexec_acq_egl_handle)) ? 1 : -1];
typedef char eclipse_check_acq_vulkan[
    __builtin_types_compatible_p(eclipse_acq_vulkan_fn, __typeof__(&eclipseexec_acq_vulkan_handle)) ? 1 : -1];
typedef char eclipse_check_preload[
    __builtin_types_compatible_p(eclipse_preload_vulkan_fn, __typeof__(&eclipseexec_preload_vulkan)) ? 1 : -1];
typedef char eclipse_check_set_affinity[
    __builtin_types_compatible_p(eclipse_set_affinity_fn, __typeof__(&eclipseexec_set_affinity_enabled)) ? 1 : -1];
typedef char eclipse_check_affinity_enabled[
    __builtin_types_compatible_p(eclipse_affinity_enabled_fn, __typeof__(&eclipseexec_affinity_enabled)) ? 1 : -1];
typedef char eclipse_check_make_affine[
    __builtin_types_compatible_p(eclipse_make_affine_fn, __typeof__(&eclipseexec_make_bigcore_affine)) ? 1 : -1];
typedef char eclipse_check_last_error[
    __builtin_types_compatible_p(eclipse_last_error_fn, __typeof__(&eclipseexec_last_error)) ? 1 : -1];
#endif

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static void *g_lib;
static eclipse_acq_egl_fn g_acq_egl;
static eclipse_acq_vulkan_fn g_acq_vulkan;
static eclipse_preload_vulkan_fn g_preload_vulkan;
static eclipse_make_affine_fn g_make_affine;
static eclipse_last_error_fn g_last_error;
static const eclipseexec_renderspec_t *g_renderspec;
static bool g_ready;

/* Resolve one symbol. NULL is never a valid answer for any of these. */
static void *eclipse_symbol(const char *name)
{
    void *sym = dlsym(g_lib, name);
    if (sym == NULL) {
        SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: libeclipseexec.so does not export %s: %s",
                     name, dlerror());
    }
    return sym;
}

static void eclipse_resolve(void)
{
    g_lib = dlopen("libeclipseexec.so", RTLD_NOW | RTLD_LOCAL);
    if (g_lib == NULL) {
        SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: libeclipseexec.so is not loaded (%s); "
                                             "SDL will load its own drivers",
                     dlerror());
        return;
    }

    g_acq_egl = (eclipse_acq_egl_fn)eclipse_symbol("eclipseexec_acq_egl_handle");
    g_acq_vulkan = (eclipse_acq_vulkan_fn)eclipse_symbol("eclipseexec_acq_vulkan_handle");
    g_preload_vulkan = (eclipse_preload_vulkan_fn)eclipse_symbol("eclipseexec_preload_vulkan");
    g_make_affine = (eclipse_make_affine_fn)eclipse_symbol("eclipseexec_make_bigcore_affine");
    g_last_error = (eclipse_last_error_fn)eclipse_symbol("eclipseexec_last_error");
    g_renderspec = (const eclipseexec_renderspec_t *)eclipse_symbol("eclipseexec_renderspec");

    /* All or nothing: half a handover is worse than none, because the missing
     * half is the half that would have said why. */
    if (!g_acq_egl || !g_acq_vulkan || !g_preload_vulkan || !g_make_affine ||
        !g_last_error || !g_renderspec) {
        SDL_LogWarn(SDL_LOG_CATEGORY_VIDEO, "Eclipse: libeclipseexec.so is present but incomplete; "
                                            "disabling the driver handover");
        dlclose(g_lib);
        g_lib = NULL;
        g_acq_egl = NULL;
        g_acq_vulkan = NULL;
        g_preload_vulkan = NULL;
        g_make_affine = NULL;
        g_last_error = NULL;
        g_renderspec = NULL;
        return;
    }

    g_ready = true;
    SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: libeclipseexec.so found and ready");
}

static bool eclipse_ready(void)
{
    pthread_once(&g_once, eclipse_resolve);
    return g_ready;
}

/* The reason the last eclipseexec call failed, or a sentence that says the
 * library never answered in the first place. */
static const char *eclipse_reason(void)
{
    const char *why = g_last_error ? g_last_error() : NULL;
    return (why && *why) ? why : "libeclipseexec.so is not available";
}

#endif /* SDL_ECLIPSE_EXEC */

bool SDL_EclipseIsAvailable(void)
{
#ifdef SDL_ECLIPSE_EXEC
    return eclipse_ready();
#else
    return false;
#endif
}

SDL_SharedObject *SDL_EclipseLoadGLDriver(void)
{
#ifdef SDL_ECLIPSE_EXEC
    void *handle;

    if (!SDL_GetHintBoolean(SDL_HINT_ECLIPSE_GL_DRIVER, true)) {
        SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: GL driver handover disabled by hint");
        return NULL;
    }
    if (!eclipse_ready()) {
        return NULL;
    }

    handle = g_acq_egl();
    if (handle == NULL) {
        /* Not an error: the launcher may simply have decided this device
         * should take the system driver, or may not have prepared one yet. */
        SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: no GL driver handed over (%s); loading one "
                                             "normally",
                     eclipse_reason());
        return NULL;
    }

    SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: GL driver handed over at %p", handle);
    return (SDL_SharedObject *)handle;
#else
    return NULL;
#endif
}

SDL_SharedObject *SDL_EclipseLoadVulkanDriver(void)
{
#ifdef SDL_ECLIPSE_EXEC
    void *handle;

    if (!SDL_GetHintBoolean(SDL_HINT_ECLIPSE_VULKAN_DRIVER, true)) {
        return NULL;
    }
    if (!eclipse_ready()) {
        return NULL;
    }

    handle = g_acq_vulkan();
    if (handle == NULL) {
        SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: no Vulkan loader handed over (%s); loading "
                                             "one normally",
                     eclipse_reason());
        return NULL;
    }

    SDL_LogDebug(SDL_LOG_CATEGORY_VIDEO, "Eclipse: Vulkan loader handed over at %p", handle);
    return (SDL_SharedObject *)handle;
#else
    return NULL;
#endif
}

void SDL_EclipsePreloadVulkan(void)
{
#ifdef SDL_ECLIPSE_EXEC
    if (!SDL_GetHintBoolean(SDL_HINT_ECLIPSE_VULKAN_DRIVER, true)) {
        return;
    }
    if (!eclipse_ready()) {
        return;
    }
    /* A no-op unless the launcher selected Turnip. When it did, this opens the
     * driver, arms the interposer and clones the system loader once, here —
     * rather than on the first frame the game renders. */
    g_preload_vulkan();
#endif
}

void SDL_EclipseApplyRenderSpec(int *profile_mask, int *major_version, int *minor_version)
{
#ifdef SDL_ECLIPSE_EXEC
    const eclipseexec_renderspec_t *spec;

    if (profile_mask == NULL || major_version == NULL || minor_version == NULL) {
        return;
    }
    if (!SDL_GetHintBoolean(SDL_HINT_ECLIPSE_GL_DRIVER, true)) {
        return;
    }
    if (!eclipse_ready()) {
        return;
    }

    spec = g_renderspec;
    if (spec->force_gles_context) {
        /* The launcher specifies a GLES major version and no minor one, so the
         * minor is cleared as well: a desktop GL request of 4.5 must become a
         * GLES request of 3, not of 3.5, which no driver will accept. */
        *profile_mask = SDL_GL_CONTEXT_PROFILE_ES;
        *minor_version = 0;
    }
    if (spec->override_major_version > 0) {
        *major_version = spec->override_major_version;
    }
#else
    (void)profile_mask;
    (void)major_version;
    (void)minor_version;
#endif
}

void SDL_EclipsePinRenderThread(void)
{
#ifdef SDL_ECLIPSE_EXEC
    if (!SDL_GetHintBoolean(SDL_HINT_ECLIPSE_BIGCORE_AFFINITY, true)) {
        return;
    }
    if (!eclipse_ready()) {
        return;
    }
    /* eclipseexec remembers the fastest core once, and remembers per thread
     * that it has already pinned this one, so calling it on every make-current
     * costs a thread-local test after the first time. */
    g_make_affine();
#endif
}
