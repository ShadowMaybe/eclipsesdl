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
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns Android input devices into SDL joysticks and SDL haptic devices.
 *
 * <p>Three threads meet here and the locking rule below is what keeps them apart:
 * <ul>
 *   <li>The SDL joystick thread drives {@link #pollInputDevices()}; SDL's C side already holds
 *       its own joystick locks when it calls in (see {@code Detect()} in SDL_sysjoystick.c), so
 *       this class must never make SDL wait on its monitor while it is inside an SDL call.</li>
 *   <li>The SDL haptic thread drives {@link #hapticRun}/{@link #hapticRumble}/{@link #hapticStop}
 *       and {@link #pollHapticDevices()}.</li>
 *   <li>The Android UI thread drives {@link #onKeyDown}/{@link #onKeyUp}/{@link #onMotionEvent},
 *       which must never block behind either of the above.</li>
 * </ul>
 * So the rule is: writers (the two SDL threads) take {@code sLock} only to update plain Java
 * bookkeeping, every {@code native*} call happens with the monitor released, and the UI thread
 * reads device state lock-free from {@link ConcurrentHashMap} over effectively-immutable
 * records. That preserves SDL's lock ordering: SDL locks are always taken before ours and ours
 * is never held while calling back into SDL.
 *
 * <p>Button and axis <em>numbers</em> are decided in C ({@code keycode_to_SDL}); this class
 * only advertises which buttons/axes exist (the GUID capability bits) and forwards raw Android
 * events with their values normalized to SDL's -1..1 range.
 */
public final class EclipseControllerManager {

    /** Why: guards the plain HashMaps below; never held across a call into SDL. */
    private static final Object sLock = new Object();

    /** Why: keyed by Android input device id, which SDL also uses as its joystick device id. */
    private static final Map<Integer, Gamepad> sGamepads =
            new ConcurrentHashMap<Integer, Gamepad>();

    /** Why: haptic id -> vibrators, so the haptic thread can act without taking any lock. */
    private static final Map<Integer, Vibrator[]> sVibrators =
            new ConcurrentHashMap<Integer, Vibrator[]>();

    /** Why: which haptic ids native already knows about, so a re-poll never double-adds. */
    private static final Map<Integer, String> sAdvertisedHaptics = new HashMap<Integer, String>();

    /**
     * Why: sensors and lights are API 31 only. The hooks object is created exclusively inside
     * an SDK_INT >= 31 branch, so older devices never load a class whose field types do not
     * exist there.
     */
    private static final Map<Integer, Api31Hooks> sHooks =
            new ConcurrentHashMap<Integer, Api31Hooks>();

    /**
     * Why: SDL's haptic list needs an id that round-trips back to Java; Android input device ids
     * are small non-negative integers, so a large sentinel can never collide with one.
     */
    private static final int SYSTEM_VIBRATOR_ID = 999999;

    private EclipseControllerManager() {
        // Why: native code holds only a jclass for this type (see the checker's
        // STATIC_ONLY_VARS), so there is nothing for an instance to do even if one existed.
    }

    // --- SDL <-> Java binding -----------------------------------------------

    /** Why: SDL calls this once to hand its class reference to the native side. */
    public static native void nativeSetupJNI();

    /**
     * Why: the vendor/product/button/axis/hat values feed SDL's GUID, so two pads of the same
     * model get the same GUID and one shared gamecontrollerdb entry.
     */
    public static native void nativeAddJoystick(int deviceId, String name, String desc,
                                                int vendorId, int productId, int buttonMask,
                                                int nAxes, int axisMask, int nHats,
                                                boolean canRumble, boolean hasRgbLed,
                                                boolean hasAccelerometer, boolean hasGyroscope);

    public static native void nativeRemoveJoystick(int deviceId);

    public static native void nativeAddHaptic(int deviceId, String name);

    public static native void nativeRemoveHaptic(int deviceId);

    /** @return true if native translated the keycode and delivered it to SDL as a button. */
    public static native boolean onNativePadDown(int deviceId, int keyCode, int scanCode);

    /** @return true if native translated the keycode and delivered it to SDL as a button. */
    public static native boolean onNativePadUp(int deviceId, int keyCode, int scanCode);

    /** @param axis the sorted axis index SDL uses, not the Android axis constant. */
    public static native void onNativeJoy(int deviceId, int axis, float value);

    public static native void onNativeHat(int deviceId, int hatId, int x, int y);

    public static native void onNativeJoySensor(int deviceId, int sensorType, long timestamp,
                                                float x, float y, float z);

    // --- device discovery ---------------------------------------------------

    /**
     * Why: SDL polls this on its own thread (and from {@code Detect()} while its joystick locks
     * are held), so Android is inspected first, our records are swapped in under the monitor,
     * and the natives run afterwards with the monitor released.
     */
    public static void pollInputDevices() {
        final int[] ids = InputDevice.getDeviceIds();
        final List<Gamepad> added = new ArrayList<Gamepad>();
        final List<Integer> removed = new ArrayList<Integer>();

        synchronized (sLock) {
            for (int id : ids) {
                // Why: Android reuses ids, so "already known" is checked by id and a device
                // that disappears first is removed on a later pass rather than rebuilt.
                if (id < 0 || sGamepads.containsKey(Integer.valueOf(id))
                        || !looksLikeGamepad(id)) {
                    continue;
                }
                Gamepad pad = describeDevice(id);
                if (pad == null) {
                    continue;
                }
                sGamepads.put(Integer.valueOf(id), pad);
                added.add(pad);
            }

            for (Integer known : new ArrayList<Integer>(sGamepads.keySet())) {
                if (!containsId(ids, known.intValue())) {
                    sGamepads.remove(known);
                    removed.add(known);
                }
            }
        }

        for (Gamepad pad : added) {
            nativeAddJoystick(pad.id, pad.name, pad.desc, pad.vendorId, pad.productId,
                    pad.buttonMask, pad.axes.size(), pad.axisMask, pad.hats.length / 2,
                    pad.canRumble, pad.hasRgbLed, pad.hasAccelerometer, pad.hasGyroscope);
        }
        for (Integer id : removed) {
            // Why: sensors keep delivering events after a disconnect unless they are torn down.
            teardownHooks(id.intValue());
            nativeRemoveJoystick(id.intValue());
        }
    }

    private static boolean containsId(int[] ids, int id) {
        for (int candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why: SDL wants controllers, not keyboards pretending to be controllers, so a device only
     * qualifies if it advertises joystick-class sources or a gamepad/d-pad source set.
     */
    private static boolean looksLikeGamepad(int deviceId) {
        if (deviceId < 0) {
            // Why: the virtual keyboard reports -1 and is handled as ordinary key input.
            return false;
        }
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return false;
        }
        final int sources = device.getSources();
        return (sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                || (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static Gamepad describeDevice(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return null;
        }

        String name = device.getName() != null ? device.getName() : ("input " + deviceId);
        String desc = device.getDescriptor();
        if (desc == null || desc.length() == 0) {
            // Why: SDL puts the descriptor in the GUID, and an empty one would make every pad
            // of the same model indistinguishable from a pad with no descriptor at all.
            desc = name;
        }

        final List<InputDevice.MotionRange> ranges =
                new ArrayList<InputDevice.MotionRange>(device.getMotionRanges());
        Collections.sort(ranges, AXIS_ORDER);

        final List<Axis> axes = new ArrayList<Axis>();
        final List<Integer> hatAxes = new ArrayList<Integer>();
        final Set<Integer> seenAxes = new HashSet<Integer>();
        boolean haveZ = false;
        boolean haveAxisAfterZBeforeRz = false;

        for (InputDevice.MotionRange range : ranges) {
            if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
                continue;
            }
            final int axis = range.getAxis();
            // Why: some devices list the same axis on more than one source; SDL would get the
            // value twice for one index if duplicates survived.
            if (!seenAxes.add(Integer.valueOf(axis))) {
                continue;
            }
            if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
                hatAxes.add(Integer.valueOf(axis));
                continue;
            }
            axes.add(new Axis(axis, range.getMin(), range.getRange()));
            if (axis == MotionEvent.AXIS_Z) {
                haveZ = true;
            } else if (axis > MotionEvent.AXIS_Z && axis < MotionEvent.AXIS_RZ) {
                haveAxisAfterZBeforeRz = true;
            }
        }

        final int axisMask = axisMask(axes.size(), haveZ, haveAxisAfterZBeforeRz);
        final int buttonMask = buttonMask(device);

        Api31Hooks hooks = null;
        if (Build.VERSION.SDK_INT >= 31) {
            // Why: sensors and lights are only reachable per input device from API 31, so
            // everything below it advertises no accelerometer, gyroscope or LED.
            hooks = new Api31Hooks(deviceId, device);
            sHooks.put(Integer.valueOf(deviceId), hooks);
        }

        return new Gamepad(deviceId, name, desc, device.getVendorId(), device.getProductId(),
                buttonMask, axisMask, axes, toIntArray(hatAxes), canRumble(device),
                hooks != null && hooks.hasRgbLed(), hooks != null && hooks.hasAccelerometer(),
                hooks != null && hooks.hasGyroscope());
    }

    /**
     * Why: SDL orders axes itself, and Android hands them over in whatever order the driver
     * reported. SDL's convention is left stick (X, Y), right stick (RX, RY), then Z/RZ, then
     * triggers — so two rewrites are needed while sorting: the GAS/BRAKE pair some pads use for
     * triggers is swapped (they report the two backwards), and Z is placed between RY and RZ
     * because X+Y and RX+RY are the common pairings while Z+RZ often holds the right stick.
     * Sorting by these rewritten values yields SDL's expected order for Xbox-style pads, and
     * pads without a Z axis are unaffected.
     */
    private static final Comparator<InputDevice.MotionRange> AXIS_ORDER =
            new Comparator<InputDevice.MotionRange>() {
                @Override
                public int compare(InputDevice.MotionRange lhs, InputDevice.MotionRange rhs) {
                    final int left = axisSortKey(lhs.getAxis());
                    final int right = axisSortKey(rhs.getAxis());
                    if (left != right) {
                        return left - right;
                    }
                    // Why: a device reporting the same axis twice must keep a stable order,
                    // otherwise the axis indexes SDL learned could swap between polls.
                    return lhs.getAxis() - rhs.getAxis();
                }
            };

    private static int axisSortKey(int axis) {
        if (axis == MotionEvent.AXIS_GAS) {
            axis = MotionEvent.AXIS_BRAKE;
        } else if (axis == MotionEvent.AXIS_BRAKE) {
            axis = MotionEvent.AXIS_GAS;
        }
        if (axis == MotionEvent.AXIS_Z) {
            axis = MotionEvent.AXIS_RZ - 1;
        } else if (axis > MotionEvent.AXIS_Z && axis < MotionEvent.AXIS_RZ) {
            --axis;
        }
        return axis;
    }

    /**
     * Why: the mask goes into SDL's GUID and tells a controller mapping which of the four
     * axis groups exist; only 2, 4 and 6 axes are distinguished because that is what SDL's
     * Android mappings have historically keyed on.
     *
     * <p>The high bit records that the sort order above changed the reported order (a Z axis
     * alongside something between Z and RZ), which SDL uses to reject outdated
     * gamecontrollerdb entries written for the unsorted order.
     */
    private static int axisMask(int nAxes, boolean haveZ, boolean haveAxisAfterZBeforeRz) {
        int mask = 0;
        if (nAxes >= 2) {
            mask |= 0x0003; // SDL_GAMEPAD_AXIS_LEFTX | SDL_GAMEPAD_AXIS_LEFTY
        }
        if (nAxes >= 4) {
            mask |= 0x000c; // SDL_GAMEPAD_AXIS_RIGHTX | SDL_GAMEPAD_AXIS_RIGHTY
        }
        if (nAxes >= 6) {
            mask |= 0x0030; // SDL_GAMEPAD_AXIS_LEFT_TRIGGER | SDL_GAMEPAD_AXIS_RIGHT_TRIGGER
        }
        if (haveZ && haveAxisAfterZBeforeRz) {
            mask |= 0x8000;
        }
        return mask;
    }

    /**
     * Why: this mask only feeds SDL's GUID (which buttons a pad claims), while the live
     * key-to-button translation happens in C; the bit values therefore have to match the SDL
     * gamepad button positions so a GUID-based mapping finds the right buttons.
     */
    private static final int[] BUTTON_KEYS = {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            // Why: these have no SDL button of their own; they still get bits so that pads
            // exposing them are distinguishable from pads that do not.
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
            KeyEvent.KEYCODE_BUTTON_5,
            KeyEvent.KEYCODE_BUTTON_6,
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10,
            KeyEvent.KEYCODE_BUTTON_11,
            KeyEvent.KEYCODE_BUTTON_12,
            KeyEvent.KEYCODE_BUTTON_13,
            KeyEvent.KEYCODE_BUTTON_14,
            KeyEvent.KEYCODE_BUTTON_15,
            KeyEvent.KEYCODE_BUTTON_16
    };

    private static final int[] BUTTON_BITS = {
            1 << 0,  1 << 1,  1 << 2,  1 << 3,
            1 << 4,  1 << 6,  1 << 5,  1 << 6,
            1 << 7,  1 << 8,  1 << 9,  1 << 10,
            1 << 11, 1 << 12, 1 << 13, 1 << 14,
            1 << 4,  1 << 0,
            1 << 15, 1 << 16, 1 << 17, 1 << 18,
            1 << 20, 1 << 21, 1 << 22, 1 << 23,
            1 << 24, 1 << 25, 1 << 26, 1 << 27,
            1 << 28, 1 << 29, 1 << 30, 1 << 31,
            // Why: the mask is a 32-bit int and 13..16 would need bits 32..35, so all four
            // collapse onto the all-ones value; native treats that as "more buttons than we
            // can name" rather than as four specific buttons.
            0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF
    };

    private static int buttonMask(InputDevice device) {
        int mask = 0;
        boolean[] present;
        try {
            present = device.hasKeys(BUTTON_KEYS);
        } catch (RuntimeException e) {
            // Why: an exotic driver rejecting the query must not abort device discovery.
            return 0;
        }
        if (present == null) {
            return 0;
        }
        for (int i = 0; i < present.length && i < BUTTON_BITS.length; i++) {
            if (present[i]) {
                // Why: |= because several keycodes deliberately share a bit (START/MENU and
                // BACK/SELECT both mean "the same SDL button").
                mask |= BUTTON_BITS[i];
            }
        }
        return mask;
    }

    @SuppressWarnings("deprecation") // minSdk 21: the replacement, VibratorManager, is API 31
    private static boolean canRumble(InputDevice device) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = device.getVibratorManager();
            if (manager == null) {
                return false;
            }
            for (int id : manager.getVibratorIds()) {
                Vibrator vibrator = manager.getVibrator(id);
                if (vibrator != null && vibrator.hasVibrator()) {
                    return true;
                }
            }
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            Vibrator vibrator = device.getVibrator();
            return vibrator != null && vibrator.hasVibrator();
        }
        // Why: InputDevice.getVibrator() is API 23, so API 21/22 pads report no rumble and
        // SDL never sends effects we could not play.
        return false;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).intValue();
        }
        return out;
    }

    // --- key and motion events (Android UI thread) --------------------------

    /**
     * Why: the activity routes key events here first so a gamepad press never reaches Android's
     * back/menu handling while SDL has focus.
     *
     * @return true if this was a gamepad button SDL already saw, false to let Android continue
     *         dispatching the event normally.
     */
    public static boolean onKeyDown(KeyEvent event) {
        if (event == null || !looksLikeGamepad(event.getDeviceId())) {
            return false;
        }
        // Why: the native side decides whether the keycode maps to an SDL button, so a
        // gamepad's volume-like or system key still falls through to Android.
        return onNativePadDown(event.getDeviceId(), event.getKeyCode(), event.getScanCode());
    }

    /** @see #onKeyDown(KeyEvent) */
    public static boolean onKeyUp(KeyEvent event) {
        if (event == null || !looksLikeGamepad(event.getDeviceId())) {
            return false;
        }
        return onNativePadUp(event.getDeviceId(), event.getKeyCode(), event.getScanCode());
    }

    /**
     * Why: SDL wants joystick axis motion, not Android's generic motion stream, so only
     * ACTION_MOVE from a gamepad is translated; anything else is refused so the caller can
     * keep treating it as hover/scroll/pen input.
     *
     * @return true if the event was gamepad axis motion, already consumed here.
     */
    public static boolean onMotionEvent(MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        final int deviceId = event.getDeviceId();
        if (!looksLikeGamepad(deviceId)) {
            return false;
        }
        Gamepad pad = sGamepads.get(Integer.valueOf(deviceId));
        if (pad == null) {
            // Why: SDL has not been told about this pad yet (it polls on its own schedule), so
            // there is no joystick to feed — but the event is still gamepad motion and must
            // not be reinterpreted as mouse input by the caller.
            return true;
        }

        final int pointer = event.getActionIndex();
        for (int i = 0; i < pad.axes.size(); i++) {
            final Axis axis = pad.axes.get(i);
            final float raw = event.getAxisValue(axis.code, pointer);
            final float value = axis.range > 0.0f
                    ? ((raw - axis.min) / axis.range) * 2.0f - 1.0f
                    : 0.0f;
            // Why: i is the index SDL learned from nativeAddJoystick, so it is the axis number
            // SDL expects back — not the Android axis constant.
            onNativeJoy(deviceId, i, value);
        }
        for (int i = 0; i + 1 < pad.hats.length; i += 2) {
            final int x = Math.round(event.getAxisValue(pad.hats[i], pointer));
            final int y = Math.round(event.getAxisValue(pad.hats[i + 1], pointer));
            // Why: native wants -1..1 directions, so the analog hat axis is rounded, not
            // normalized — a hat reports -1, 0 or 1.
            onNativeHat(deviceId, i / 2, x, y);
        }
        return true;
    }

    // --- haptics ------------------------------------------------------------

    /**
     * Why: SDL rebuilds its haptic list from what is advertised here, so the set is reconciled
     * rather than appended: a device that vanished is removed (native would otherwise keep a
     * dead entry forever) and an id already known is not added twice (native does not
     * deduplicate).
     */
    public static void pollHapticDevices() {
        final Map<Integer, Vibrator[]> current = new HashMap<Integer, Vibrator[]>();
        final Map<Integer, String> names = new HashMap<Integer, String>();

        final Vibrator system = systemVibrator();
        if (system != null && system.hasVibrator()) {
            current.put(Integer.valueOf(SYSTEM_VIBRATOR_ID), new Vibrator[] {system});
            names.put(Integer.valueOf(SYSTEM_VIBRATOR_ID), "System vibrator");
        }
        for (Integer deviceId : sGamepads.keySet()) {
            final Vibrator[] deviceVibrators = vibratorsFor(deviceId.intValue());
            if (deviceVibrators.length > 0) {
                final InputDevice device = InputDevice.getDevice(deviceId.intValue());
                final String label = device != null && device.getName() != null
                        ? device.getName() : ("input " + deviceId);
                current.put(deviceId, deviceVibrators);
                names.put(deviceId, label + " vibrator");
            }
        }

        final List<Integer> toAdd = new ArrayList<Integer>();
        final List<Integer> toRemove = new ArrayList<Integer>();
        synchronized (sLock) {
            for (Integer id : current.keySet()) {
                if (!sAdvertisedHaptics.containsKey(id)) {
                    toAdd.add(id);
                }
            }
            for (Integer id : new ArrayList<Integer>(sAdvertisedHaptics.keySet())) {
                if (!current.containsKey(id)) {
                    toRemove.add(id);
                }
            }
            sAdvertisedHaptics.clear();
            sAdvertisedHaptics.putAll(names);
        }

        // Why: the vibrators map is swapped alongside the advertised set so hapticRun can never
        // act on an id native no longer knows about; this runs without the monitor held because
        // native calls are the whole point of keeping it out of the critical section.
        for (Iterator<Integer> it = sVibrators.keySet().iterator(); it.hasNext(); ) {
            if (!current.containsKey(it.next())) {
                it.remove();
            }
        }
        sVibrators.putAll(current);

        for (Integer id : toRemove) {
            nativeRemoveHaptic(id.intValue());
        }
        for (Integer id : toAdd) {
            nativeAddHaptic(id.intValue(), names.get(id));
        }
    }

    /**
     * Why: SDL's one-shot haptic effect; intensity arrives as 0..1 and a zero intensity means
     * "stop", because SDL sends it to cancel an effect that is still running.
     */
    public static void hapticRun(int id, float intensity, int length) {
        final Vibrator[] targets = sVibrators.get(Integer.valueOf(id));
        if (targets == null || targets.length == 0) {
            return;
        }
        if (intensity <= 0.0f) {
            cancel(targets);
            return;
        }
        pulse(targets[0], intensity, length);
    }

    /**
     * Why: SDL's rumble carries separate low/high frequencies. Android vibrators cannot play
     * tones, so two vibrators get one channel each and a single vibrator gets a blend weighted
     * the same way SDL blends the magnitudes (60/40).
     */
    public static void hapticRumble(int id, float lowFrequencyIntensity,
                                    float highFrequencyIntensity, int length) {
        final Vibrator[] targets = sVibrators.get(Integer.valueOf(id));
        if (targets == null || targets.length == 0) {
            return;
        }
        if (lowFrequencyIntensity <= 0.0f && highFrequencyIntensity <= 0.0f) {
            cancel(targets);
            return;
        }
        if (targets.length >= 2) {
            pulse(targets[0], lowFrequencyIntensity, length);
            pulse(targets[1], highFrequencyIntensity, length);
            return;
        }
        final float blend = (lowFrequencyIntensity * 0.6f) + (highFrequencyIntensity * 0.4f);
        if (blend <= 0.0f) {
            cancel(targets);
            return;
        }
        vibrate(targets[0], length, amplitudeFor(blend));
    }

    public static void hapticStop(int id) {
        final Vibrator[] targets = sVibrators.get(Integer.valueOf(id));
        if (targets != null) {
            cancel(targets);
        }
    }

    private static void cancel(Vibrator[] targets) {
        for (Vibrator vibrator : targets) {
            try {
                vibrator.cancel();
            } catch (RuntimeException ignored) {
                // Why: the system server can race a device disconnect; haptics are best-effort
                // and must never take down SDL's haptic thread.
            }
        }
    }

    /**
     * Why: a channel SDL is not driving must be stopped rather than left buzzing from the
     * effect before it, which is what a zero intensity means on this API.
     */
    private static void pulse(Vibrator target, float intensity, int length) {
        if (target == null) {
            return;
        }
        if (intensity <= 0.0f) {
            try {
                target.cancel();
            } catch (RuntimeException ignored) {
                // Why: same best-effort rule as cancel().
            }
            return;
        }
        vibrate(target, length, amplitudeFor(intensity));
    }

    private static int amplitudeFor(float intensity) {
        // Why: 0 means "no vibration" to VibrationEffect, so anything SDL asks for maps into
        // the 1..255 range and 0 is handled as a cancel by the caller instead.
        int amplitude = Math.round(intensity * 255.0f);
        if (amplitude < 1) {
            return 1;
        }
        return Math.min(amplitude, 255);
    }

    @SuppressWarnings("deprecation") // minSdk 21: Vibrator.vibrate(VibrationEffect) is API 26
    private static void vibrate(Vibrator vibrator, int length, int amplitude) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(Math.max(length, 1), amplitude));
            } else {
                // Why: amplitude control does not exist below API 26, duration is all there is.
                vibrator.vibrate(Math.max(length, 1));
            }
        } catch (RuntimeException first) {
            try {
                // Why: some vendor builds reject VibrationEffect but still honour the legacy
                // call; failing to rumble should degrade, not throw into SDL.
                vibrator.vibrate(Math.max(length, 1));
            } catch (RuntimeException ignored) {
                // Nothing left to try.
            }
        }
    }

    @SuppressWarnings("deprecation") // minSdk 21: VIBRATOR_MANAGER_SERVICE is API 31
    private static Vibrator systemVibrator() {
        final Context context = EclipseHIDDeviceManager.sharedContext();
        if (context == null) {
            // Why: startup may not have handed us a Context yet (or never does on a build that
            // skips HID); there is simply no system vibrator to advertise then.
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return manager != null ? manager.getDefaultVibrator() : null;
            }
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation") // minSdk 21: the replacement, VibratorManager, is API 31
    private static Vibrator[] vibratorsFor(int deviceId) {
        if (deviceId == SYSTEM_VIBRATOR_ID) {
            final Vibrator system = systemVibrator();
            return system != null ? new Vibrator[] {system} : new Vibrator[0];
        }
        final InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return new Vibrator[0];
        }
        final List<Vibrator> found = new ArrayList<Vibrator>(2);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = device.getVibratorManager();
            if (manager != null) {
                for (int id : manager.getVibratorIds()) {
                    final Vibrator vibrator = manager.getVibrator(id);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        found.add(vibrator);
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            final Vibrator vibrator = device.getVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                found.add(vibrator);
            }
        }
        return found.toArray(new Vibrator[0]);
    }

    // --- optional per-device capabilities (API 31) ---------------------------

    /**
     * Why: SDL sets LEDs and enables sensors at runtime; both are API 31 features on a per
     * input-device basis, so this is a no-op on older systems instead of an error.
     */
    public static void joystickSetLED(int deviceId, int red, int green, int blue) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        final Api31Hooks hooks = sHooks.get(Integer.valueOf(deviceId));
        if (hooks == null || hooks.session == null || !hooks.hasRgbLed()) {
            return;
        }
        try {
            final LightsRequest.Builder request = new LightsRequest.Builder();
            final LightState state = new LightState.Builder()
                    .setColor(Color.rgb(red, green, blue)).build();
            for (Light light : hooks.rgbLights) {
                request.addLight(light, state);
            }
            hooks.session.requestLights(request.build());
        } catch (RuntimeException ignored) {
            // Why: a session can be revoked when the pad disconnects mid-call.
        }
    }

    /**
     * Why: SDL only starts sensors a joystick declares, and registering twice would deliver
     * every sample twice; below API 31 no pad declares any, so this is a guarded no-op.
     */
    public static void joystickSetSensorsEnabled(int deviceId, boolean enabled) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        final Api31Hooks hooks = sHooks.get(Integer.valueOf(deviceId));
        if (hooks == null || hooks.sensors == null || hooks.listener == null) {
            return;
        }
        try {
            if (enabled) {
                if (hooks.accelerometer != null) {
                    hooks.sensors.registerListener(hooks.listener, hooks.accelerometer,
                            SensorManager.SENSOR_DELAY_GAME);
                }
                if (hooks.gyroscope != null) {
                    hooks.sensors.registerListener(hooks.listener, hooks.gyroscope,
                            SensorManager.SENSOR_DELAY_GAME);
                }
            } else {
                hooks.sensors.unregisterListener(hooks.listener);
            }
        } catch (RuntimeException ignored) {
            // Why: restricted profiles can refuse sensor access; SDL treats that as absent.
        }
    }

    private static void teardownHooks(int deviceId) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        final Api31Hooks hooks = sHooks.remove(Integer.valueOf(deviceId));
        if (hooks == null) {
            return;
        }
        try {
            if (hooks.sensors != null && hooks.listener != null) {
                hooks.sensors.unregisterListener(hooks.listener);
            }
        } catch (RuntimeException ignored) {
            // Why: the session may already be gone with the device.
        }
        try {
            if (hooks.session != null) {
                hooks.session.close();
            }
        } catch (RuntimeException ignored) {
            // Why: closing a session twice is a no-op for us but throws on some builds.
        }
    }

    /**
     * Why: a separate type means API 31 field types are only ever resolved on a device that has
     * them — the outer class only ever refers to this class, never to those types directly.
     */
    private static final class Api31Hooks {
        final SensorManager sensors;
        final Sensor accelerometer;
        final Sensor gyroscope;
        final SensorEventListener listener;
        final List<Light> rgbLights = new ArrayList<Light>();
        final LightsManager.LightsSession session;

        Api31Hooks(int deviceId, InputDevice device) {
            SensorManager manager = null;
            Sensor accel = null;
            Sensor gyro = null;
            LightsManager.LightsSession openedSession = null;
            try {
                manager = device.getSensorManager();
                if (manager != null) {
                    accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                }
                LightsManager lights = device.getLightsManager();
                if (lights != null) {
                    for (Light light : lights.getLights()) {
                        if (light != null && light.hasRgbControl()) {
                            rgbLights.add(light);
                        }
                    }
                    if (!rgbLights.isEmpty()) {
                        // Why: lights can only be written through an open session, so one is
                        // taken for the lifetime of the pad instead of per color change.
                        openedSession = lights.openSession();
                    }
                }
            } catch (RuntimeException ignored) {
                // Why: half of the capability stays unavailable rather than discovery failing.
            }
            this.sensors = manager;
            this.accelerometer = accel;
            this.gyroscope = gyro;
            this.session = openedSession;
            this.listener = new PadSensorListener(deviceId);
        }

        boolean hasRgbLed() {
            return !rgbLights.isEmpty() && session != null;
        }

        boolean hasAccelerometer() {
            return accelerometer != null;
        }

        boolean hasGyroscope() {
            return gyroscope != null;
        }
    }

    private static final class PadSensorListener implements SensorEventListener {
        private final int deviceId;

        PadSensorListener(int deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null || event.values == null
                    || event.values.length < 3) {
                return;
            }
            // Why: native keys sensors off the Android sensor type (1 = accelerometer,
            // 4 = gyroscope) and reads the timestamp as SDL's sensor clock.
            onNativeJoySensor(deviceId, event.sensor.getType(), event.timestamp,
                    event.values[0], event.values[1], event.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Why: SDL has no accuracy channel; nothing to forward.
        }
    }

    // --- immutable device records -------------------------------------------

    /** Why: min/max/range are captured once so the UI thread never has to query Android. */
    private static final class Axis {
        final int code;
        final float min;
        final float range;

        Axis(int code, float min, float range) {
            this.code = code;
            this.min = min;
            this.range = range;
        }
    }

    /**
     * Why: SDL reads these fields from the UI thread without taking any lock, so the record is
     * built completely before it is published to the concurrent map.
     */
    private static final class Gamepad {
        final int id;
        final String name;
        final String desc;
        final int vendorId;
        final int productId;
        final int buttonMask;
        final int axisMask;
        final List<Axis> axes;
        final int[] hats;
        final boolean canRumble;
        final boolean hasRgbLed;
        final boolean hasAccelerometer;
        final boolean hasGyroscope;

        Gamepad(int id, String name, String desc, int vendorId, int productId, int buttonMask,
                int axisMask, List<Axis> axes, int[] hats, boolean canRumble, boolean hasRgbLed,
                boolean hasAccelerometer, boolean hasGyroscope) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.vendorId = vendorId;
            this.productId = productId;
            this.buttonMask = buttonMask;
            this.axisMask = axisMask;
            this.axes = Collections.unmodifiableList(axes);
            this.hats = hats;
            this.canRumble = canRumble;
            this.hasRgbLed = hasRgbLed;
            this.hasAccelerometer = hasAccelerometer;
            this.hasGyroscope = hasGyroscope;
        }
    }
}
