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
#ifndef SDL_eclipse_h
#define SDL_eclipse_h

#include "SDL_internal.h"

/*
 * The seam between SDL and the Eclipse Launcher's native bootstrap layer,
 * eclipseexec. eclipseexec runs before the game: it picks and opens the GL or
 * Vulkan driver (through a private linker namespace when the driver lives where
 * Android will not look for it), publishes the render parameters the driver has
 * to see, and pins the render thread to a big CPU core. SDL sits above it and
 * asks for those things here instead of reaching into the Android linker.
 *
 * Everything in this header is safe to call whether or not SDL was built with
 * eclipseexec. Without it the functions report "nothing to hand over" and SDL
 * loads its drivers the ordinary way, so the same libSDL3.so still builds and
 * runs — only the handover is missing.
 *
 * Build with -DECLIPSE_EXEC_ROOT=<path to an eclipseexec checkout> to switch
 * the handover on; CMake then defines SDL_ECLIPSE_EXEC for the whole build.
 */

/**
 * Ask eclipseexec for the GL driver the launcher prepared.
 *
 * Returns a fresh reference, or NULL when eclipseexec is not in the build, the
 * launcher has not prepared a driver, or SDL_HINT_ECLIPSE_GL_DRIVER is false.
 * NULL means "carry on with the normal load", never "fail".
 */
SDL_SharedObject *SDL_EclipseLoadGLDriver(void);

/**
 * Ask eclipseexec for the Vulkan loader: the Turnip driver when the launcher
 * selected it, otherwise the system loader.
 *
 * Returns NULL when eclipseexec is not in the build or has nothing to offer;
 * the caller then loads the library itself.
 */
SDL_SharedObject *SDL_EclipseLoadVulkanDriver(void);

/**
 * Warm the Vulkan path up before it is first used, so the driver is not opened
 * on the first frame. A no-op when the launcher did not select Turnip, when
 * SDL_HINT_ECLIPSE_VULKAN_DRIVER is false, and in builds without eclipseexec.
 */
void SDL_EclipsePreloadVulkan(void);

/**
 * Fold the launcher's render parameters into SDL's GL attributes: which of GL
 * and GLES to ask for, and the GLES major version to request.
 *
 * The launcher says what the context should be; SDL should not quietly ask the
 * driver for something else. Pass the addresses of the attribute SDL is about
 * to use. Calling it more than once is harmless — the same values are written
 * each time.
 */
void SDL_EclipseApplyRenderSpec(int *profile_mask, int *major_version, int *minor_version);

/**
 * Move the calling thread onto the fastest CPU in the device, once per thread.
 *
 * Called from the points where SDL knows it is talking to the render thread.
 * No effect when SDL_HINT_ECLIPSE_BIGCORE_AFFINITY is false or eclipseexec is
 * not in the build.
 */
void SDL_EclipsePinRenderThread(void);

/**
 * True when this build can hand over to eclipseexec at all. Useful for
 * diagnostics and for deciding whether a missing driver is worth reporting.
 */
bool SDL_EclipseIsAvailable(void);

#endif /* SDL_eclipse_h */
