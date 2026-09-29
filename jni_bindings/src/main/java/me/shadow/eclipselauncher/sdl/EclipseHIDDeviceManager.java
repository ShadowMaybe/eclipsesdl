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

import android.content.Context;
import android.util.Log;

/**
 * Java half of SDL's hidapi callback bridge, plus the single place a Context
 * enters this package.
 *
 * Startup calls ensureCreated(context) once. That builds the one instance and
 * hands it to native through HIDDeviceRegisterCallback, which stores a global
 * reference to it (see HIDDeviceRegisterCallback in src/hidapi/android/hid.cpp).
 * From then on hid_init() calls back into initialize(), and hidapi's open/
 * read/write/close paths call openDevice(), readReport(), writeReport() and
 * closeDevice() on that same instance. The other eight methods are native
 * pushing device events the other way: connection, reports, open results.
 *
 * Because native keeps the instance, this class is also where the Context
 * lives: startup hands it to ensureCreated() and EclipseAudioManager and
 * EclipseControllerManager borrow it through sharedContext(). Nothing here
 * keeps an Activity, only the application context, so it can outlive the
 * activity it was created with.
 *
 * Nothing in this class may throw. hidapi calls in from SDL's joystick and
 * HID threads while holding its own locks, and an exception crossing a JNI
 * callback turns a missing capability into an abort.
 */
public final class EclipseHIDDeviceManager {

    private static final String TAG = "EclipseHID";

    /** Why: ensureCreated() can be reached from startup and from UI code at once. */
    private static final Object sCreationLock = new Object();

    /** Why: volatile so a caller that skips the lock still sees a fully built instance. */
    private static volatile EclipseHIDDeviceManager sInstance;

    /** Why: application context, never an Activity — see the class comment. */
    private static volatile Context sContext;

    /**
     * Why: hid_init() calls initialize() once per process; "ready" means the
     * callback is registered and SDL may assume this side is listening.
     */
    private static volatile boolean sReady;

    /**
     * Creates the one instance if it does not exist and registers it with
     * native so hidapi's callbacks have somewhere to land.
     *
     * Idempotent: every caller after the first gets the same instance and no
     * second registration happens (a second HIDDeviceRegisterCallback would
     * just drop and recreate native's global reference).
     *
     * @param context startup context; stored as the application context and
     *                may be null, in which case callers of sharedContext()
     *                simply degrade to having no Context.
     */
    public static EclipseHIDDeviceManager ensureCreated(Context context) {
        if (context != null && sContext == null) {
            final Context app = context.getApplicationContext();
            // Why: getApplicationContext() is null in a few instrumented cases;
            // the context we were handed is still better than none.
            sContext = app != null ? app : context;
        }
        synchronized (sCreationLock) {
            if (sInstance != null) {
                return sInstance;
            }
            final EclipseHIDDeviceManager created = new EclipseHIDDeviceManager();
            try {
                created.HIDDeviceRegisterCallback();
            } catch (Throwable t) {
                // Why: the JNI library may not be loaded yet (or at all on a
                // build without SDL). Losing HID is survivable; taking down
                // startup over it is not, and sharedContext() below still works.
                Log.w(TAG, "could not register the HID callback", t);
            }
            sInstance = created;
            return created;
        }
    }

    /**
     * Why: SDL's other Java classes need a Context but startup only ever passes
     * one to ensureCreated(); returning null means "not available yet", which
     * every caller is written to handle.
     */
    public static Context sharedContext() {
        return sContext;
    }

    /** Why: lets callers tell "callback registered" from "never initialized". */
    public static boolean isReady() {
        return sReady;
    }

    private EclipseHIDDeviceManager() {
        // Why: exactly one instance exists, created under sCreationLock; a
        // second one would mean native holds a reference nobody else matches.
    }

    // --- native -> Java ------------------------------------------------------
    // Registered from the EclipseHIDDeviceManager table in JNI_OnLoad; these are
    // instance natives because hid.cpp keeps a jobject (the instance created
    // above) and calls them on it.

    public native void HIDDeviceRegisterCallback();

    public native void HIDDeviceReleaseCallback();

    /**
     * @param reportId which report id the device uses, so openDevice() can set
     *                  it before the first transfer.
     */
    public native void HIDDeviceConnected(int deviceId, String identifier, int vendorId,
                                          int productId, String serialNumber, int releaseNumber,
                                          String manufacturer, String product, int interfaceNumber,
                                          int interfaceClass, int interfaceSubclass,
                                          int interfaceProtocol, boolean bluetooth, int reportId);

    public native void HIDDeviceOpenPending(int deviceId);

    public native void HIDDeviceOpenResult(int deviceId, boolean opened);

    public native void HIDDeviceDisconnected(int deviceId);

    public native void HIDDeviceInputReport(int deviceId, byte[] data);

    public native void HIDDeviceReportResponse(int deviceId, byte[] data);

    // --- Java -> native (called by hid.cpp on the instance) ------------------

    /**
     * Called by hid_init() (and again after the Bluetooth permission is
     * granted, which is the second, permission-driven pass).
     *
     * Android exposes gamepads through InputDevice, which is handled by
     * EclipseControllerManager — that is the controller path this build ships.
     * Raw USB and Bluetooth LE HID reports are a separate feature that is not
     * shipping yet, so this reports success (SDL's hid_init only needs to know
     * the backend is functional) while enumerating no devices: nothing claims a
     * device id that openDevice() could then fail to open.
     *
     * @param lateInit true when called from hid_init after startup, false when
     *                 driven by a permission result.
     * @param bluetoothPermission true when the Bluetooth permission has just
     *                            been granted.
     * @return true — the backend is initialized, it simply has no devices.
     */
    public boolean initialize(boolean lateInit, boolean bluetoothPermission) {
        // Why: both arguments only change which pass we are in, and since no
        // devices are enumerated the two passes produce the same state.
        sReady = true;
        return true;
    }

    /**
     * @return false — no device can be opened until raw HID support exists, and
     *         false is how hidapi asks the caller to stop trying rather than
     *         retry forever.
     */
    public boolean openDevice(int deviceId) {
        return false;
    }

    /**
     * @return -1, which is hidapi's write-failed code; returning anything else
     *         would claim bytes were sent.
     */
    public int writeReport(int deviceId, byte[] data, boolean feature) {
        return -1;
    }

    /**
     * @return false — there is no buffered report to hand back, so hidapi
     *         reports "nothing read" instead of inventing data.
     */
    public boolean readReport(int deviceId, byte[] data, boolean feature) {
        return false;
    }

    /** Why: a no-op is correct here — no device was ever opened to close. */
    public void closeDevice(int deviceId) {
        // Nothing to release; see openDevice().
    }
}
