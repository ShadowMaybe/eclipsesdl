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

package me.shadow.eclipselauncher.sdl;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Java side of SDL's JNI bridge for this fork.
 *
 * <p>Two audiences share this class. Native code reaches in through the natives declared here and
 * through the statics in the section marked "called by native", and it does both without any
 * Java object to speak to: SDL holds a {@code jclass} for this type only. The host Activity
 * reaches in through {@link #attach(Activity)}, {@link #startMain(String, String, String[])} and
 * the {@code handle*} forwarders, which exist because this class is not an Activity and so cannot
 * receive lifecycle callbacks of its own.
 *
 * <p>Everything that touches views or windows runs on the main looper: SDL calls in from its own
 * threads and Android does not tolerate that anywhere else. Every call that *answers* native
 * (openURL, the file dialog, the permission request, device queries) runs inline on the calling
 * thread instead, so the return value native reads is the real one rather than "a post happened".
 */
public final class EclipseSDL {

    private static final String TAG = "EclipseSDL";

    /** Manifest meta-data keys prefixed with this become environment variables. */
    private static final String MANIFEST_VARIABLE_PREFIX = "SDL_ENV.";

    /**
     * Message numbers SDL sends through {@link #sendMessage(int, int)}; the values are defined by
     * native code and cannot be renamed here. Command 1 (change title) is deliberately absent from
     * the switch: it carries a String in a channel that only has ints, which is why the title has
     * its own entry point in {@link #setActivityTitle(String)}.
     */
    private static final int COMMAND_CHANGE_TITLE = 1;
    private static final int COMMAND_CHANGE_WINDOW_STYLE = 2;
    private static final int COMMAND_TEXTEDIT_HIDE = 3;
    private static final int COMMAND_SET_KEEP_SCREEN_ON = 5;

    /** SDL_DisplayOrientation values, which are what native stores for the panel's orientation. */
    private static final int ORIENTATION_LANDSCAPE = 1;
    private static final int ORIENTATION_PORTRAIT = 3;

    /** Return value of the orientation helpers meaning "the hint asked for nothing, leave it be". */
    private static final int ORIENTATION_UNSET = -1;

    /** Guards the lifecycle state that more than one thread reads and writes. */
    private static final Object sLifecycleLock = new Object();

    /** Guards the two "has this been published already" flags below. */
    private static final Object sDisplayLock = new Object();

    /** Told apart from the Application so a detached Activity can still be found by the host. */
    private static volatile Activity sActivity;

    /** Kept after {@link #detach()}: clipboard and device queries still need a Context. */
    private static volatile Application sApplication;

    /** The one view SDL draws into, and the only thing that may post IME work. */
    private static volatile EclipseSurfaceView sSurfaceView;

    /**
     * The Surface handed to native. Published *before* {@code onNativeSurfaceCreated()} and cleared
     * before {@code onNativeSurfaceDestroyed()}: native's {@code getNativeSurface()} lookup is how
     * it decides whether a surface exists, so the two have to agree in that order or the EGL
     * surface is created against a buffer that is already gone.
     */
    private static volatile Surface sNativeSurface;

    private static volatile Handler sUiHandler;

    private static volatile ClipboardManager sClipboard;

    /** True while a key event is travelling from the view down to SDL; see isDispatchingKey(). */
    private static volatile boolean sKeyDispatch;

    /** SDL asked for pointer capture; the view reads this to decide how to report mouse motion. */
    private static volatile boolean sRelativeMouse;

    /** The only file dialog SDL is waiting on, so a host result can be recognised as ours. */
    private static volatile int sPendingFileDialog = -1;

    /** True once a clipboard listener has been registered on the main looper. */
    private static boolean sClipboardWatched;

    /** Start guard; also the signal that SDL, and not Android, owns the back key from here on. */
    private static boolean sMainStarted;

    /** Degrees of the last rotation handed to native, or -1 before the first one. */
    private static int sPublishedRotation = -1;

    /** Whether the panel's natural orientation has been sent once already. */
    private static boolean sPublishedNaturalOrientation;

    /** Request codes this class issued for runtime permissions, so results can be matched. */
    private static final List<Integer> sPermissionRequests = new ArrayList<Integer>();

    /** Locale tag and night mode last seen, so a configuration change only reports differences. */
    private static String sLastLocaleTag;
    private static Boolean sLastDarkMode;

    /** Custom cursors, keyed by the id native was given in return. */
    private static final Map<Integer, PointerIcon> sCustomCursors = new HashMap<Integer, PointerIcon>();

    private static int sNextCursorId;

    /**
     * SDL's system cursor ordinals, in order, mapped onto PointerIcon's type constants. SDL has
     * names for resize directions the platform only ships as the four diagonals and cardinals, so
     * the nearest icon wins rather than falling back to an arrow.
     */
    private static final int[] SYSTEM_CURSOR_TYPES = {
        PointerIcon.TYPE_ARROW,                             /* default */
        PointerIcon.TYPE_TEXT,                              /* text / I-beam */
        PointerIcon.TYPE_WAIT,                              /* wait */
        PointerIcon.TYPE_CROSSHAIR,                         /* crosshair */
        PointerIcon.TYPE_WAIT,                              /* progress */
        PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,    /* north-west to south-east */
        PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,   /* north-east to south-west */
        PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,           /* east-west */
        PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,             /* north-south */
        PointerIcon.TYPE_GRAB,                              /* move */
        PointerIcon.TYPE_NO_DROP,                           /* not allowed */
        PointerIcon.TYPE_HAND,                              /* pointer / link */
        PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,    /* north-west */
        PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,             /* north */
        PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,   /* north-east */
        PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,           /* east */
        PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,    /* south-east */
        PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,             /* south */
        PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,   /* south-west */
        PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,           /* west */
    };

    /** The platform only reports clipboard changes to a listener, so one is registered once. */
    private static final ClipboardManager.OnPrimaryClipChangedListener sClipboardListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    onNativeClipboardChanged();
                }
            };

    /** Native talks to this type through its Class; there is never an instance to talk to. */
    private EclipseSDL() {
    }

    // --- lifecycle driven by the host ---------------------------------------

    /**
     * Bind the Activity that owns this window. Every native call that needs an Activity — assets,
     * the IME, intents, the manifest — answers from here, so this has to happen before
     * {@link #startMain(String, String, String[])}.
     *
     * <p>Should be called from the main thread: it also registers the clipboard listener, which is
     * posted when it is not.
     *
     * @param activity the Activity the SDL surface lives in
     */
    public static void attach(Activity activity) {
        if (activity == null) {
            throw new NullPointerException("attach(Activity) needs an Activity");
        }
        synchronized (sLifecycleLock) {
            sActivity = activity;
            if (sApplication == null) {
                sApplication = activity.getApplication();
            }
            rememberConfiguration(activity.getResources().getConfiguration());
        }
        watchClipboard();
    }

    /**
     * The Activity is going away. The host calls this on destroy; SDL must have been paused
     * first, because from here on getContext() answers null and the native paths that assume a
     * live Activity would fail rather than degrade.
     *
     * <p>The Application is deliberately kept: it is the Context everything else falls back to.
     */
    public static void detach() {
        synchronized (sLifecycleLock) {
            sActivity = null;
        }
    }

    /** The Activity currently hosting SDL, or null before attach() and after detach(). */
    public static Activity activity() {
        return sActivity;
    }

    /** The best Context available right now: the Activity while it lives, else the Application. */
    public static Context context() {
        Activity activity = sActivity;
        if (activity != null) {
            return activity;
        }
        return sApplication;
    }

    /**
     * Start SDL: bind the JNI tables, then hand the process over to the game's main function on a
     * thread named {@code SDL main}.
     *
     * <p>The order below is fixed, and it is the order a running SDL activity on a device shows
     * when the two halves it is assembled from are put back together:
     *
     * <ol>
     *   <li>Every {@code nativeSetupJNI()} resolves the Java methods its threads will call and
     *       builds the mutex those callbacks lock, so it has to happen before the first SDL
     *       thread exists. The three calls run in the order native's readiness check expects —
     *       it refuses to consider the bridge bound until audio and controllers are in — and HID
     *       discovery comes last because it wants a Context for the USB service, which only the
     *       Activity has at this point.</li>
     *   <li>The panel's natural orientation, its current rotation and the night-mode flag are
     *       pushed next rather than at the first rotation: SDL copies them into the video device
     *       while it starts up, on the thread created below, so anything sent afterwards races
     *       the window's creation.</li>
     *   <li>{@code nativeInitMainThread()}, the game, then {@code nativeCleanupMainThread()}
     *       must run together on one thread — they attach that thread to the JVM and give it the
     *       JNIEnv the whole game loop uses — so they move as a block and cannot be interleaved
     *       with anything on the caller's thread.</li>
     * </ol>
     *
     * <p>Calling this twice is a no-op. SDL has no supported way to be started again in the same
     * process, so a host that wants a fresh SDL has to make one.
     *
     * <p>The step order and the {@code SDL main} thread name follow
     * {@code android-project/app/src/main/java/org/libsdl/app/SDLActivity.java} in this tree: that
     * class is the reference implementation of the startup sequence, and matching it keeps the
     * ordering assumptions on the native side (JNI tables bound before the first SDL thread,
     * orientation published before the window exists, init/run/cleanup sharing one JNIEnv) true
     * here too.
     *
     * @param library  shared object to load; must not be null — native code passes it straight to
     *                 {@code GetStringUTFChars} and then {@code dlopen} without a null check
     * @param function entry point inside that library, normally {@code SDL_main}; must not be
     *                 null, for the same {@code GetStringUTFChars} reason
     * @param args     argv handed to the entry point; null is treated as an empty argument list
     * @throws IllegalStateException    if attach() has not run, which would otherwise fail deep
     *                                  in native code where the stack says nothing useful
     * @throws IllegalArgumentException if library or function is null, before SDL is marked
     *                                  started so a bad call does not permanently wedge startup
     */
    public static void startMain(String library, String function, String[] args) {
        if (library == null || function == null) {
            // nativeRunMain() does GetStringUTFChars(library, NULL) and GetStringUTFChars(function,
            // NULL) with no null check; a null jstring there is undefined behaviour in the VM, not
            // an SDL error. Reject it here, before sMainStarted is set, so the caller may retry.
            throw new IllegalArgumentException(
                    "startMain() requires non-null library and function names");
        }
        if (context() == null) {
            throw new IllegalStateException(
                    "startMain() before attach(Activity): SDL would have no Context");
        }
        synchronized (sLifecycleLock) {
            if (sMainStarted) {
                Log.w(TAG, "startMain() ignored: SDL is already running in this process");
                return;
            }
            sMainStarted = true;
        }

        nativeSetupJNI();
        EclipseAudioManager.nativeSetupJNI();
        EclipseControllerManager.nativeSetupJNI();
        EclipseHIDDeviceManager.ensureCreated(context());
        publishDisplayState();

        final String libraryName = library;
        final String entryPoint = function;
        final java.lang.Object argv = (args != null) ? args : new String[0];

        Thread sdlMain = new Thread(new Runnable() {
            @Override
            public void run() {
                nativeInitMainThread();
                try {
                    int status = nativeRunMain(libraryName, entryPoint, argv);
                    if (status != 0) {
                        Log.e(TAG, "SDL main returned status " + status);
                    }
                } finally {
                    nativeCleanupMainThread();
                }
            }
        }, "SDL main");
        sdlMain.start();
    }

    /** True between startMain() and the return of the game's main function. */
    static boolean isMainStarted() {
        synchronized (sLifecycleLock) {
            return sMainStarted;
        }
    }

    // --- surface and input plumbing -----------------------------------------

    /** Called by the view when it attaches, so the class knows where windows and cursors live. */
    static void registerSurface(EclipseSurfaceView view) {
        sSurfaceView = view;
    }

    /** Called by the view when it detaches; SDL keeps no reference to a dead window. */
    static void unregisterSurface(EclipseSurfaceView view) {
        if (sSurfaceView == view) {
            sSurfaceView = null;
        }
    }

    static void setNativeSurface(Surface surface) {
        sNativeSurface = surface;
    }

    static void clearNativeSurface() {
        sNativeSurface = null;
    }

    /**
     * Bracket a key event on its way to SDL. The input connection reads this to tell "SDL is
     * about to receive this character as a key" from "the IME typed it", because only the second
     * one may synthesize a scancode — otherwise one key press produces two SDL key events.
     */
    static void beginKeyDispatch() {
        sKeyDispatch = true;
    }

    static void endKeyDispatch() {
        sKeyDispatch = false;
    }

    static boolean isDispatchingKey() {
        return sKeyDispatch;
    }

    /** Whether SDL asked for pointer capture; the view reports relative motion while set. */
    static boolean isRelativeMouse() {
        return sRelativeMouse;
    }

    // --- natives -------------------------------------------------------------

    // All of these are static because SDL binds them to this type's Class, not to an object: the
    // C side keeps a jclass and calls through it.

    public static native String nativeGetVersion();

    public static native void nativeSetupJNI();

    public static native void nativeInitMainThread();

    public static native void nativeCleanupMainThread();

    /**
     * Runs the entry point on the calling thread.
     *
     * @param library   shared object to load, or null for this process
     * @param function  entry point to call
     * @param arguments the argv array; must be a {@code String[]}, hence java.lang.Object here
     * @return the entry point's return value
     */
    public static native int nativeRunMain(String library, String function, java.lang.Object arguments);

    public static native void nativeSetScreenResolution(int surfaceWidth, int surfaceHeight,
                                                       int deviceWidth, int deviceHeight,
                                                       float density, float refreshRate);

    public static native void onNativeResize();

    public static native void onNativeSurfaceCreated();

    public static native void onNativeSurfaceChanged();

    public static native void onNativeSurfaceDestroyed();

    public static native void onNativeKeyDown(int keyCode);

    public static native void onNativeKeyUp(int keyCode);

    /** @return true if SDL consumed the return key, meaning the IME should now close */
    public static native boolean onNativeSoftReturnKey();

    public static native void onNativeKeyboardFocusLost();

    /** @param x, y normalised to 0..1 across the surface, as SDL's touch backend wants them */
    public static native void onNativeTouch(int touchDeviceId, int pointerId, int action,
                                            float x, float y, float pressure);

    public static native void onNativePinchStart();

    public static native void onNativePinchUpdate(float scale);

    public static native void onNativePinchEnd();

    /** @param relative true when the deltas, not the coordinates, are what SDL should read */
    public static native void onNativeMouse(int buttonState, int action, float x, float y,
                                            boolean relative);

    public static native void onNativePen(int penId, int deviceType, int buttonState, int action,
                                          float x, float y, float pressure);

    public static native void onNativeAccel(float x, float y, float z);

    public static native void onNativeDropFile(String file);

    public static native void onNativeClipboardChanged();

    public static native void onNativeLocaleChanged();

    public static native void onNativeDarkModeChanged(boolean darkMode);

    public static native void onNativeScreenKeyboardShown();

    public static native void onNativeScreenKeyboardHidden();

    public static native void onNativeInsetsChanged(int left, int right, int top, int bottom);

    /** @param degrees one of 0, 90, 180, 270 — the display's rotation, not the panel's */
    public static native void onNativeRotationChanged(int degrees);

    /** @param orientation one of SDL_DisplayOrientation's values */
    public static native void nativeSetNaturalOrientation(int orientation);

    /** Reports the outcome of a runtime permission request; see handlePermissionResult(). */
    public static native void nativePermissionResult(int requestCode, boolean granted);

    /**
     * Delivers a file dialog's result.
     *
     * @param requestCode the code the dialog was opened with
     * @param files       content URIs chosen, an empty array for a cancel, null for an error
     * @param filter      selected filter index, or -1 when the platform cannot say
     */
    public static native void onNativeFileDialog(int requestCode, String[] files, int filter);

    public static native void nativeAddTouch(int touchId, String name);

    public static native boolean nativeAllowRecreateActivity();

    public static native int nativeCheckSDLThreadCounter();

    public static native void nativeLowMemory();

    public static native void nativePause();

    public static native void nativeResume();

    public static native void nativeFocusChanged(boolean hasFocus);

    public static native void nativeQuit();

    public static native void nativeSendQuit();

    public static native void nativeSetenv(String name, String value);

    public static native String nativeGetHint(String name);

    public static native boolean nativeGetHintBoolean(String name, boolean defaultValue);

    // --- statics native code calls ------------------------------------------

    /**
     * SDL's window, or null while there is none. Published by the view before it announces the
     * surface and cleared before it announces the loss, in that order, because native reads this
     * to decide whether a surface exists at all.
     */
    public static android.view.Surface getNativeSurface() {
        return sNativeSurface;
    }

    /**
     * The Activity native code asks for through {@code midGetContext} and then uses as the
     * Context for assets, file descriptors, message boxes and battery state. Null once
     * {@link #detach()} has run — every native caller checks for that now.
     */
    public static android.app.Activity getContext() {
        return sActivity;
    }

    /**
     * Reads {@code SDL_ENV.*} meta-data into the process environment.
     *
     * @return true once the manifest has been read, which is native's signal not to try again
     */
    public static boolean getManifestEnvironmentVariables() {
        Context context = context();
        if (context == null) {
            return false;
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = info.metaData;
            if (metaData == null) {
                return false;
            }
            for (String key : metaData.keySet()) {
                if (key == null || !key.startsWith(MANIFEST_VARIABLE_PREFIX)) {
                    continue;
                }
                String value = metaData.getString(key);
                if (value != null) {
                    nativeSetenv(key.substring(MANIFEST_VARIABLE_PREFIX.length()), value);
                }
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "manifest is not readable: " + e);
            return false;
        }
    }

    public static String clipboardGetText() {
        ClipboardManager manager = clipboard();
        if (manager == null) {
            return "";
        }
        try {
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return "";
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context());
            return (text != null) ? text.toString() : "";
        } catch (RuntimeException e) {
            // Why: Android refuses clipboard reads from a background app, and which refusal takes
            // the form of an exception has changed between releases.
            return "";
        }
    }

    public static boolean clipboardHasText() {
        ClipboardManager manager = clipboard();
        if (manager == null) {
            return false;
        }
        try {
            ClipData clip = manager.getPrimaryClip();
            return clip != null && clip.getItemCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void clipboardSetText(String string) {
        ClipboardManager manager = clipboard();
        if (manager == null) {
            return;
        }
        try {
            manager.setPrimaryClip(ClipData.newPlainText(
                    null, (string != null) ? string : ""));
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard write refused: " + e);
        }
    }

    /**
     * Builds a pointer icon out of SDL's ARGB pixels.
     *
     * @return an id to pass back to {@link #setCustomCursor(int)}, or 0 when icons do not exist
     *         on this API level or the pixels are not one complete image
     */
    public static int createCustomCursor(int[] colors, int width, int height,
                                         int hotSpotX, int hotSpotY) {
        if (Build.VERSION.SDK_INT < 24 || colors == null || width <= 0 || height <= 0) {
            return 0;
        }
        if (colors.length != width * height) {
            Log.w(TAG, "custom cursor rejected: " + colors.length + " pixels for "
                    + width + "x" + height);
            return 0;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
            PointerIcon icon = PointerIcon.create(bitmap, hotSpotX, hotSpotY);
            if (icon == null) {
                return 0;
            }
            synchronized (sCustomCursors) {
                sNextCursorId++;
                sCustomCursors.put(Integer.valueOf(sNextCursorId), icon);
                return sNextCursorId;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "custom cursor could not be built: " + e);
            return 0;
        }
    }

    public static void destroyCustomCursor(int cursorId) {
        synchronized (sCustomCursors) {
            sCustomCursors.remove(Integer.valueOf(cursorId));
        }
    }

    /**
     * @return true once the icon is on the view, or when there is nothing to put an icon on
     * @return false when the id is unknown or the platform refused it
     */
    public static boolean setCustomCursor(int cursorId) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        EclipseSurfaceView view = sSurfaceView;
        if (view == null) {
            return false;
        }
        PointerIcon icon;
        synchronized (sCustomCursors) {
            icon = sCustomCursors.get(Integer.valueOf(cursorId));
        }
        if (icon == null) {
            return false;
        }
        try {
            view.setPointerIcon(icon);
        } catch (RuntimeException e) {
            Log.w(TAG, "custom cursor refused: " + e);
            return false;
        }
        return true;
    }

    /**
     * @return true when the cursor was set, or when this API level has no pointer icons to set
     *         — SDL treats false as "the cursor did not change" and warns about it
     */
    public static boolean setSystemCursor(int cursorId) {
        if (Build.VERSION.SDK_INT < 24) {
            return true;
        }
        EclipseSurfaceView view = sSurfaceView;
        if (view == null) {
            return true;
        }
        int type = PointerIcon.TYPE_ARROW;
        if (cursorId >= 0 && cursorId < SYSTEM_CURSOR_TYPES.length) {
            type = SYSTEM_CURSOR_TYPES[cursorId];
        }
        try {
            view.setPointerIcon(PointerIcon.getSystemIcon(view.getContext(), type));
        } catch (RuntimeException e) {
            Log.w(TAG, "system cursor " + cursorId + " refused: " + e);
            return false;
        }
        return true;
    }

    /**
     * The orientation SDL wants for the next window. SDL asks by describing what it would accept
     * rather than by naming an orientation, so the description is turned into a request here:
     * a resizable window takes the user's orientation lock into account, a fixed one is pinned to
     * whichever way its own width and height go.
     *
     * @param width    window width in pixels, 1 when SDL has no window yet
     * @param height   window height in pixels
     * @param resizable whether the app can be rotated freely
     * @param hint     the SDL_HINT_APP_DISPLAY_ORIENTATION value, may be null
     */
    public static void setOrientation(int width, int height, boolean resizable, String hint) {
        if (width <= 1 || height <= 1) {
            // Why: SDL sends 1x1 before the window exists; there is nothing to orient yet, and
            // answering that would pin the Activity to the wrong way round.
            return;
        }
        String list = (hint != null) ? hint : "";
        int landscape = landscapeRequest(list);
        int portrait = portraitRequest(list);
        final int request = orientationRequest(width, height, resizable, landscape, portrait);
        if (request == ORIENTATION_UNSET) {
            return;
        }
        final Activity activity = sActivity;
        if (activity == null) {
            return;
        }
        postToUi(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.setRequestedOrientation(request);
                } catch (RuntimeException e) {
                    Log.w(TAG, "setRequestedOrientation refused: " + e);
                }
            }
        });
    }

    public static boolean supportsRelativeMouse() {
        if (Build.VERSION.SDK_INT < 24) {
            // Why: relative motion is read off AXIS_RELATIVE_X/Y, which does not exist before 24.
            return false;
        }
        if (Build.VERSION.SDK_INT < 27 && isDeXMode()) {
            // Why: Samsung's desktop mode dropped the relative axes and only gained them in 8.1.
            return false;
        }
        return true;
    }

    public static boolean setRelativeMouseEnabled(boolean enabled) {
        if (enabled && !supportsRelativeMouse()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (enabled && Build.VERSION.SDK_INT < 27 && isDeXMode()) {
                return false;
            }
            EclipseSurfaceView view = sSurfaceView;
            if (view == null) {
                // Why: capture belongs to a view's window, so with no view there is nothing to
                // capture and claiming success would leave SDL reading absolute coordinates it
                // believes are relative.
                return false;
            }
            if (enabled) {
                view.requestPointerCapture();
            } else {
                view.releasePointerCapture();
            }
        }
        // Why: below 26 there is no capture to request; the flag alone is what makes the view
        // read the relative axes instead of the touch coordinates.
        sRelativeMouse = enabled;
        return true;
    }

    /** True when SDL should hide the window on focus loss. It should not: Android pauses us. */
    public static boolean shouldMinimizeOnFocusLoss() {
        return false;
    }

    public static void manualBackButton() {
        final Activity activity = sActivity;
        if (activity == null) {
            return;
        }
        postToUi(new Runnable() {
            @Override
            @SuppressWarnings("deprecation") // the framework replacement is AndroidX's OnBackPressedDispatcher, which a dependency-free library cannot use
            public void run() {
                try {
                    activity.onBackPressed();
                } catch (RuntimeException e) {
                    Log.w(TAG, "back button refused: " + e);
                }
            }
        });
    }

    public static void minimizeWindow() {
        Activity activity = sActivity;
        if (activity == null) {
            return;
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            activity.startActivity(home);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no home screen to go to");
        } catch (RuntimeException e) {
            Log.w(TAG, "minimize refused: " + e);
        }
    }

    public static boolean setActivityTitle(String title) {
        final Activity activity = sActivity;
        if (activity == null || title == null) {
            return false;
        }
        postToUi(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.setTitle(title);
                } catch (RuntimeException e) {
                    Log.w(TAG, "title refused: " + e);
                }
            }
        });
        return true;
    }

    /**
     * Fullscreen means SDL owns the pixels, which is the system UI flag set on any API level and
     * the display cutout on 30 and up — without the cutout mode the window stops at the notch and
     * SDL renders into a strip it cannot see.
     *
     * @param fullscreen true to hide the system chrome, false to bring it back
     */
    public static void setWindowStyle(boolean fullscreen) {
        final Activity activity = sActivity;
        if (activity == null) {
            return;
        }
        postToUi(new Runnable() {
            @Override
            public void run() {
                applyWindowStyle(activity, fullscreen);
            }
        });
    }

    public static boolean sendMessage(int command, int param) {
        switch (command) {
            case COMMAND_CHANGE_TITLE: {
                // Why: this channel carries one int, and a title is a String; SDL's title path
                // is setActivityTitle() instead, so there is nothing to do here.
                return false;
            }
            case COMMAND_CHANGE_WINDOW_STYLE: {
                setWindowStyle(param != 0);
                return true;
            }
            case COMMAND_TEXTEDIT_HIDE: {
                final EclipseSurfaceView view = sSurfaceView;
                if (view == null) {
                    return false;
                }
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        view.hideKeyboard();
                    }
                });
                return true;
            }
            case COMMAND_SET_KEEP_SCREEN_ON: {
                final Activity activity = sActivity;
                if (activity == null) {
                    return false;
                }
                final boolean keepOn = (param != 0);
                postToUi(new Runnable() {
                    @Override
                    public void run() {
                        applyKeepScreenOn(activity, keepOn);
                    }
                });
                return true;
            }
            default:
                Log.w(TAG, "SDL sent an unhandled message: command " + command);
                return false;
        }
    }

    /**
     * Opens the soft keyboard. The rect SDL passes is where a detached editor widget would have
     * been pinned on screen; this view *is* the editor, so only the input type survives —
     * the rect is accepted because the signature is fixed, and ignored because there is nothing
     * to place.
     *
     * @return true when the request was queued on the main looper
     */
    public static boolean showTextInput(int inputType, int x, int y, int w, int h) {
        final EclipseSurfaceView view = sSurfaceView;
        if (view == null) {
            return false;
        }
        return postToUi(new Runnable() {
            @Override
            public void run() {
                view.showKeyboard(inputType);
            }
        });
    }

    public static boolean openURL(String url) {
        Activity activity = sActivity;
        if (activity == null || url == null) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // Why: a launcher must not collect a browser in its own back stack, so the target gets
            // a task of its own and leaving it returns to wherever the user came from.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "nothing can open " + url);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "opening " + url + " refused: " + e);
            return false;
        }
    }

    /**
     * Asks for one runtime permission.
     *
     * <p>Answers inline: native has already recorded the request and is waiting for
     * {@link #nativePermissionResult(int, boolean)}, so a false here means nobody ever will.
     *
     * @return true when the system now owns the request, false when it could not be made
     */
    public static void requestPermission(String permission, int requestCode) {
        Activity activity = sActivity;
        if (activity == null) {
            return;
        }
        rememberPermissionRequest(requestCode);
        if (Build.VERSION.SDK_INT < 23) {
            // Why: below 23 install time granted everything, so the answer exists already.
            nativePermissionResult(requestCode, true);
            forgetPermissionRequest(requestCode);
            return;
        }
        try {
            if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                nativePermissionResult(requestCode, true);
                forgetPermissionRequest(requestCode);
                return;
            }
            activity.requestPermissions(new String[] { permission }, requestCode);
        } catch (RuntimeException e) {
            Log.w(TAG, "permission request for " + permission + " refused: " + e);
            nativePermissionResult(requestCode, false);
            forgetPermissionRequest(requestCode);
        }
    }

    /**
     * Opens the platform's document picker. Runs inline so that when SDL reads the return value
     * it learns whether a picker is really on screen; if it is not, native drops the pending
     * callback itself and the request code recorded here goes away with it.
     *
     * @param filters       file patterns such as {@code *.png}, or null
     * @param allowMultiple whether more than one file may be chosen
     * @param forWrite      true for a save dialog, false for an open dialog
     * @param requestCode   the code native will recognise the result by
     * @return true when the picker was launched
     */
    public static boolean showFileDialog(String[] filters, boolean allowMultiple,
                                         boolean forWrite, int requestCode) {
        Activity activity = sActivity;
        if (activity == null) {
            return false;
        }
        try {
            Intent intent = new Intent(forWrite ? Intent.ACTION_CREATE_DOCUMENT
                                                : Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = mimeTypesFor(filters);
            if (mimeTypes.length > 0) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            }
            if (allowMultiple && !forWrite) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            sPendingFileDialog = requestCode;
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no document picker available");
            sPendingFileDialog = -1;
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "document picker refused: " + e);
            sPendingFileDialog = -1;
            return false;
        }
    }

    /**
     * Toasts a message. Posted, because a Toast has to be built and shown on the looper that
     * owns the process's UI.
     *
     * @param duration 0 for a short toast, anything else for a long one
     * @param gravity  a Gravity value, or 0 to let the platform decide
     * @return true when the toast was queued
     */
    public static boolean showToast(String message, int duration, int gravity,
                                    int xOffset, int yOffset) {
        if (message == null) {
            return false;
        }
        return postToUi(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast toast = Toast.makeText(context(), message,
                            (duration == 1) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    if (gravity != 0) {
                        toast.setGravity(gravity, xOffset, yOffset);
                    }
                    toast.show();
                } catch (RuntimeException e) {
                    Log.w(TAG, "toast refused: " + e);
                }
            }
        });
    }

    /**
     * Opens a file descriptor SDL will read through its own VFS layer. The descriptor's ownership
     * moves to native when this returns a number, so nothing here may close it afterwards.
     *
     * @param uri  content URI, as delivered by the file dialog
     * @param mode "r", "w", "rw" and friends
     * @return the descriptor, or -1 if it could not be opened
     */
    public static int openFileDescriptor(String uri, String mode) {
        Context context = context();
        if (context == null || uri == null || mode == null) {
            return -1;
        }
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = context.getContentResolver().openFileDescriptor(Uri.parse(uri), mode);
            if (descriptor == null) {
                return -1;
            }
            int fd = descriptor.detachFd();
            descriptor = null;
            return fd;
        } catch (Exception e) {
            // Why: a file dialog hands back URIs the app may not have been granted, and which
            // failure that turns into varies by provider; SDL only needs "no descriptor".
            Log.w(TAG, "could not open " + uri + ": " + e);
            return -1;
        } finally {
            closeQuietly(descriptor);
        }
    }

    /** A seven inch diagonal or more is SDL's definition of a tablet, measured physically. */
    public static boolean isTablet() {
        return diagonalInches() >= 7.0;
    }

    public static boolean isAndroidTV() {
        Context context = context();
        if (context == null) {
            return false;
        }
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    public static boolean isChromebook() {
        Context context = context();
        if (context == null) {
            return false;
        }
        return context.getPackageManager().hasSystemFeature("org.chromium.arc.device_kind");
    }

    public static boolean isDeXMode() {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        Context context = context();
        if (context == null) {
            return false;
        }
        try {
            Configuration configuration = context.getResources().getConfiguration();
            Class<?> type = configuration.getClass();
            // Why: desktop mode is announced through two Configuration fields that only Samsung
            // builds carry, one holding the "on" value and one holding the current value; both
            // are read by name so nothing here links against an API the SDK does not have.
            int enabled = type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(type);
            int current = type.getField("semDesktopModeEnabled").getInt(configuration);
            return enabled == current;
        } catch (Exception e) {
            // Why: a missing field is the normal answer on every other manufacturer.
            return false;
        }
    }

    /**
     * Reports the preferred locale list as a comma separated tag list, most preferred first.
     * SDL parses it into a language, a country and a script, so an unknown language has to be
     * spelled the way SDL expects rather than the way Java spells it: Java calls Indonesian
     * "in" and the empty language "in"/"" where SDL wants "id" and "und".
     */
    @SuppressWarnings("deprecation") // minSdk 21: Configuration.getLocales() is API 24
    public static String getPreferredLocales() {
        StringBuilder out = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList locales = LocaleList.getAdjustedDefault();
            for (int i = 0; i < locales.size(); i++) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(formatLocale(locales.get(i)));
            }
            return out.toString();
        }
        Context context = context();
        if (context == null) {
            return "";
        }
        return formatLocale(context.getResources().getConfiguration().locale);
    }

    /**
     * Announces every touch device the process can see. SDL polls this once when the joystick
     * subsystem starts, so a device plugged in later is caught by the next poll instead.
     */
    public static void initTouch() {
        int[] ids = android.view.InputDevice.getDeviceIds();
        for (int id : ids) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null) {
                continue;
            }
            // Why: a virtual device can send touchscreen events too, and SDL wants to be able to
            // address them by id either way.
            if ((device.getSources() & android.view.InputDevice.SOURCE_TOUCHSCREEN) != 0
                    || device.isVirtual()) {
                nativeAddTouch(device.getId(), device.getName());
            }
        }
    }

    // --- results the host forwards back --------------------------------------

    /**
     * Carries a file dialog's result from the host's onActivityResult() to native.
     *
     * @return true when the code belonged to {@link #showFileDialog}, false when the host should
     *         handle it itself
     */
    public static boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != sPendingFileDialog) {
            return false;
        }
        sPendingFileDialog = -1;
        if (resultCode != Activity.RESULT_OK || data == null) {
            // Why: an empty list is SDL's spelling of "the user cancelled", where a null one is
            // an error the app would be told about.
            onNativeFileDialog(requestCode, new String[0], -1);
            return true;
        }
        List<String> chosen = new ArrayList<String>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    chosen.add(uri.toString());
                }
            }
        } else if (data.getData() != null) {
            chosen.add(data.getData().toString());
        }
        if (chosen.isEmpty()) {
            // Why: the picker said yes and produced nothing readable, which SDL should see as a
            // failure rather than as a cancellation.
            onNativeFileDialog(requestCode, null, -1);
            return true;
        }
        onNativeFileDialog(requestCode, chosen.toArray(new String[chosen.size()]), -1);
        return true;
    }

    /**
     * Carries a permission result from the host's onRequestPermissionsResult() to native.
     *
     * @return true when the code was one this class issued, false when it was the host's own
     */
    public static boolean handlePermissionResult(int requestCode, String[] permissions,
                                                 int[] grantResults) {
        if (!isPermissionRequest(requestCode)) {
            return false;
        }
        boolean granted = (grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        forgetPermissionRequest(requestCode);
        nativePermissionResult(requestCode, granted);
        return true;
    }

    /**
     * Carries a configuration change from the host's onConfigurationChanged() to native. SDL
     * reads the locale and the night mode when it starts and expects to be told when they move,
     * because nothing else will: it has no Activity to receive the system's own broadcast.
     *
     * @param config the new configuration, as Android hands it to the host
     */
    public static void handleConfigurationChanged(Configuration config) {
        if (config == null) {
            return;
        }
        String localeTag = localeTag(config);
        if (sLastLocaleTag != null && !sLastLocaleTag.equals(localeTag)) {
            onNativeLocaleChanged();
        }
        boolean dark = isDarkMode(config);
        if (sLastDarkMode != null && sLastDarkMode.booleanValue() != dark) {
            onNativeDarkModeChanged(dark);
        }
        rememberConfiguration(config);
    }

    // --- private helpers -----------------------------------------------------

    /**
     * Pushes the display state SDL copies while it starts up. Both halves dedupe, so the view may
     * call this on every surface change and startMain() may call it before the view exists.
     */
    static void publishDisplayState() {
        publishNaturalOrientation();
        publishRotation(displayRotationDegrees());
    }

    /**
     * Which way the panel itself is built, worked out from the rotation it is currently in and
     * the orientation its resources were laid out for: the two disagree exactly when the panel is
     * landscape, because a landscape panel turned a quarter turn still reports a portrait
     * configuration.
     */
    private static void publishNaturalOrientation() {
        synchronized (sDisplayLock) {
            if (sPublishedNaturalOrientation) {
                return;
            }
            sPublishedNaturalOrientation = true;
        }
        Activity activity = sActivity;
        int rotation = displayRotationDegrees();
        boolean configSaysLandscape = activity != null
                && activity.getResources().getConfiguration().orientation
                   == Configuration.ORIENTATION_LANDSCAPE;
        boolean turned = (rotation == 90 || rotation == 270);
        boolean landscape = turned != configSaysLandscape;
        nativeSetNaturalOrientation(landscape ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT);
    }

    /** Hands the current rotation to native, and only then when it actually differs. */
    private static void publishRotation(int degrees) {
        synchronized (sDisplayLock) {
            if (sPublishedRotation == degrees) {
                return;
            }
            sPublishedRotation = degrees;
        }
        onNativeRotationChanged(degrees);
    }

    @SuppressWarnings("deprecation") // minSdk 21: WindowManager.getCurrentWindowMetrics() is API 30
    private static int displayRotationDegrees() {
        Activity activity = sActivity;
        if (activity == null) {
            return 0;
        }
        Display display = activity.getWindowManager().getDefaultDisplay();
        if (display == null) {
            return 0;
        }
        switch (display.getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * The orientation SDL would accept for a landscape-only hint.
     *
     * @return an ActivityInfo orientation, or ORIENTATION_UNSET when the hint does not mention
     *         landscape at all
     */
    private static int landscapeRequest(String hint) {
        boolean left = hint.contains("LandscapeLeft");
        boolean right = hint.contains("LandscapeRight");
        if (left && right) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        }
        if (left) {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
        if (right) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        }
        return ORIENTATION_UNSET;
    }

    /**
     * The orientation SDL would accept for a portrait-only hint. The plain word has to be
     * distinguished from "portrait upside down", and it is only a plain word when it ends the
     * hint or is followed by something other than "UpsideDown".
     *
     * @return an ActivityInfo orientation, or ORIENTATION_UNSET when the hint does not mention
     *         portrait at all
     */
    private static int portraitRequest(String hint) {
        boolean plain = hint.contains("Portrait ") || hint.endsWith("Portrait");
        boolean upsideDown = hint.contains("PortraitUpsideDown");
        if (plain && upsideDown) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        }
        if (plain) {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
        if (upsideDown) {
            return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        }
        return ORIENTATION_UNSET;
    }

    /**
     * Chooses the request from the window's shape and the hint's permissions.
     *
     * @return an ActivityInfo orientation, or ORIENTATION_UNSET when the hint rules out both
     *         directions equally and the window is resizable, i.e. when SDL has no preference
     */
    private static int orientationRequest(int width, int height, boolean resizable,
                                          int landscape, int portrait) {
        boolean allowsLandscape = (landscape != ORIENTATION_UNSET);
        boolean allowsPortrait = (portrait != ORIENTATION_UNSET);

        if (!allowsLandscape && !allowsPortrait) {
            if (resizable) {
                // Why: nothing was asked for and the window may change shape, so the user's own
                // orientation lock is the only preference left to respect.
                return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
            }
            return (width > height) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        }

        if (resizable) {
            if (allowsLandscape && allowsPortrait) {
                return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
            }
            return allowsLandscape ? landscape : portrait;
        }

        if (allowsLandscape && allowsPortrait) {
            return (width > height) ? landscape : portrait;
        }
        return allowsLandscape ? landscape : portrait;
    }

    @SuppressWarnings("deprecation") // minSdk 21: WindowInsetsController is API 30
    private static void applyWindowStyle(Activity activity, boolean fullscreen) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (fullscreen) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        } else {
            flags |= View.SYSTEM_UI_FLAG_VISIBLE;
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        decor.setSystemUiVisibility(flags);

        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = fullscreen
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }

        // Why: hiding the chrome changes the insets, and SDL can only learn that from a dispatch
        // rather than from a guess — asking the view to re-read them is what starts that.
        EclipseSurfaceView view = sSurfaceView;
        if (view != null) {
            view.requestApplyInsets();
        }
    }

    private static void applyKeepScreenOn(Activity activity, boolean keepOn) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Posts to the main looper. @return true when the work was accepted for later. */
    private static boolean postToUi(Runnable work) {
        Handler handler = sUiHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            sUiHandler = handler;
        }
        try {
            return handler.post(work);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not reach the main looper: " + e);
            return false;
        }
    }

    private static ClipboardManager clipboard() {
        ClipboardManager manager = sClipboard;
        if (manager != null) {
            return manager;
        }
        Context context = context();
        if (context == null) {
            return null;
        }
        manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            sClipboard = manager;
        }
        return manager;
    }

    /** Registers the clipboard listener once, on the looper that owns the app's UI. */
    private static void watchClipboard() {
        postToUi(new Runnable() {
            @Override
            public void run() {
                if (sClipboardWatched) {
                    return;
                }
                ClipboardManager manager = clipboard();
                if (manager == null) {
                    return;
                }
                manager.addPrimaryClipChangedListener(sClipboardListener);
                sClipboardWatched = true;
            }
        });
    }

    /**
     * Turns SDL's file patterns into the MIME types a document picker understands. A pattern that
     * already looks like a type is passed through, and a bare {@code *} widens to every type.
     */
    private static String[] mimeTypesFor(String[] filters) {
        if (filters == null || filters.length == 0) {
            return new String[0];
        }
        MimeTypeMap map = MimeTypeMap.getSingleton();
        List<String> types = new ArrayList<String>();
        for (int i = 0; i < filters.length; i++) {
            String pattern = filters[i];
            if (pattern == null) {
                continue;
            }
            String type = null;
            if (pattern.indexOf('/') >= 0) {
                type = pattern;
            } else {
                String extension = pattern;
                while (extension.startsWith("*") || extension.startsWith(".")) {
                    extension = extension.substring(1);
                }
                if (extension.length() == 0) {
                    type = "*/*";
                } else {
                    type = map.getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
                }
            }
            if (type != null && !types.contains(type)) {
                types.add(type);
            }
        }
        return types.toArray(new String[types.size()]);
    }

    /** SDL's spelling of a locale: ISO 639 for Indonesian, and "und" for a language-less tag. */
    private static String formatLocale(Locale locale) {
        String language = locale.getLanguage();
        if (language == null || language.length() == 0) {
            return "und";
        }
        if ("in".equals(language)) {
            return "id";
        }
        String country = locale.getCountry();
        if (country == null || country.length() == 0) {
            return language;
        }
        return language + "_" + country;
    }

    /** A comparable form of the configuration's locale list, most preferred first. */
    @SuppressWarnings("deprecation") // minSdk 21: Configuration.getLocales() is API 24
    private static String localeTag(Configuration config) {
        StringBuilder out = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.LocaleList locales = config.getLocales();
            for (int i = 0; i < locales.size(); i++) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(locales.get(i).toLanguageTag());
            }
            return out.toString();
        }
        Locale locale = config.locale;
        return (locale != null) ? locale.toLanguageTag() : "";
    }

    private static boolean isDarkMode(Configuration config) {
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Remembers the locale and night mode so a later change can be told from the first read. */
    private static void rememberConfiguration(Configuration config) {
        sLastLocaleTag = localeTag(config);
        sLastDarkMode = Boolean.valueOf(isDarkMode(config));
    }

    private static void rememberPermissionRequest(int requestCode) {
        synchronized (sPermissionRequests) {
            if (!sPermissionRequests.contains(Integer.valueOf(requestCode))) {
                sPermissionRequests.add(Integer.valueOf(requestCode));
            }
        }
    }

    private static boolean isPermissionRequest(int requestCode) {
        synchronized (sPermissionRequests) {
            return sPermissionRequests.contains(Integer.valueOf(requestCode));
        }
    }

    private static void forgetPermissionRequest(int requestCode) {
        synchronized (sPermissionRequests) {
            sPermissionRequests.remove(Integer.valueOf(requestCode));
        }
    }

    /** Longest diagonal of the screen in physical inches, which is how tablets are told from
     *  phones: the dp based measure would call a large phone a tablet. */
    private static double diagonalInches() {
        Context context = context();
        if (context == null) {
            return 0.0;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        if (metrics == null || metrics.xdpi <= 0 || metrics.ydpi <= 0) {
            return 0.0;
        }
        double widthInches = metrics.widthPixels / metrics.xdpi;
        double heightInches = metrics.heightPixels / metrics.ydpi;
        return Math.sqrt(widthInches * widthInches + heightInches * heightInches);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (Exception e) {
            Log.w(TAG, "closing a descriptor failed: " + e);
        }
    }
}
