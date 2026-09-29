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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java half of SDL's audio-device bridge.
 *
 * The three native methods are registered from the EclipseAudioManager table in
 * JNI_OnLoad (src/core/android/SDL_android.c): nativeSetupJNI caches this class
 * on the C side, and nativeAddAudioDevice / nativeRemoveAudioDevice announce
 * devices to SDL. The three plain methods are SDL calling back the other way:
 * registerAudioDeviceCallback when audio hotplug starts (it must report every
 * device that already exists before it returns), unregisterAudioDeviceCallback
 * when hotplug stops, and audioSetThreadPriority as each audio thread begins.
 *
 * startMain calls nativeSetupJNI() before SDL is started, so the binding is
 * warm by the time SDL asks for a device list.
 *
 * Locking: the lock of this class is only ever held for Java and Android work,
 * never across a call into SDL. SDL's threads enter here while they already
 * own SDL's locks (hotplug runs from the audio subsystem's device detect
 * pass), so taking our lock there keeps one fixed order — SDL's locks, then
 * ours. The order must never run the other way, so the paths the main thread
 * reaches (the two callbacks below) stage their changes under the lock and
 * issue the native calls after releasing it.
 *
 * The Context reaches us through EclipseHIDDeviceManager.sharedContext(),
 * because startup only hands a Context to EclipseHIDDeviceManager.ensureCreated();
 * if none has arrived yet we still report a fixed device list rather than
 * nothing at all.
 */
public final class EclipseAudioManager {

    private static final String TAG = "EclipseAudio";

    // Ids for the devices we can only describe by hand. The API 23+ path uses
    // AudioDeviceInfo's own ids; these sit far above the small dense numbers
    // Android hands out, so a device reported from either path keeps one
    // stable id for the lifetime of the registration and the two paths can
    // never name a different device the same way.
    private static final int FIXED_PLAYBACK_ID = 0x10000001;
    private static final int FIXED_RECORDING_ID = 0x10000002;
    private static final int FIXED_HEADSET_ID = 0x10000003;

    private static final String FIXED_PLAYBACK_NAME = "Android default output";
    private static final String FIXED_RECORDING_NAME = "Android default input";
    private static final String HEADSET_NAME = "Wired headset";

    /** Guards every field below it. Never held across a call into SDL; see the class comment. */
    private static final Object sLock = new Object();

    /** Every device we have announced to SDL and not yet withdrawn, by key(). */
    private static final Map<Long, DeviceReport> sReported = new HashMap<>();

    private static boolean sRegistered;

    // AudioDeviceCallback only exists from API 23, so it is built lazily inside
    // the guarded branch below and only ever touched from one; merely holding a
    // field of that type costs nothing on API 21/22.
    private static AudioManager sModernManager;
    private static AudioDeviceCallback sDeviceCallback;
    private static BroadcastReceiver sHeadsetReceiver;

    private EclipseAudioManager() {
        // Static state only; there is nothing an instance would do.
    }

    // Registered by the EclipseAudioManager table in JNI_OnLoad — these have no
    // Java body by design, the implementations live in SDL_android.c.
    public static native void nativeSetupJNI();

    public static native void nativeAddAudioDevice(boolean recording, String name, int deviceId);

    public static native void nativeRemoveAudioDevice(boolean recording, int deviceId);

    /**
     * Called by SDL through JNI when audio hotplug starts
     * (Android_StartAudioHotplug). SDL expects every existing device to have
     * been reported by the time this returns, then keeps listening for changes
     * through the callback we register here.
     */
    public static void registerAudioDeviceCallback() {
        final List<DeviceReport> pending = new ArrayList<>();
        synchronized (sLock) {
            if (sRegistered) {
                return;
            }
            final Context context = EclipseHIDDeviceManager.sharedContext();
            AudioManager manager = null;
            if (context != null) {
                try {
                    manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                } catch (RuntimeException e) {
                    Log.w(TAG, "cannot reach the audio service", e);
                }
            }
            if (Build.VERSION.SDK_INT >= 23 && manager != null) {
                beginModernRegistration(manager, pending);
            } else {
                // API 21/22 has neither AudioDeviceInfo nor AudioDeviceCallback
                // (and a missing Context lands here too), so fall back to the
                // devices we can name without asking the system.
                beginFixedRegistration(manager, context, pending);
            }
        }
        flush(pending);
    }

    /**
     * Called by SDL through JNI when audio hotplug stops.
     *
     * <p>SDL destroys every physical device it owns before this runs — SDL_QuitAudio
     * empties the device hash and only then calls Deinitialize, which is where this
     * comes from — so SDL's side is already empty and ours has to become empty too.
     * Leaving it populated would make the next registration's dedup throw away every
     * device on the list, announce nothing, and leave SDL with no default playback
     * device at all for that session.
     */
    public static void unregisterAudioDeviceCallback() {
        final AudioManager manager;
        final BroadcastReceiver receiver;
        synchronized (sLock) {
            if (!sRegistered) {
                return;
            }
            sRegistered = false;
            sReported.clear();
            manager = sModernManager;
            sModernManager = null;
            receiver = sHeadsetReceiver;
            sHeadsetReceiver = null;
        }
        if (manager != null && Build.VERSION.SDK_INT >= 23) {
            try {
                manager.unregisterAudioDeviceCallback(sDeviceCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot unregister the audio device callback", e);
            }
        }
        if (receiver != null) {
            final Context context = EclipseHIDDeviceManager.sharedContext();
            if (context != null) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (IllegalArgumentException e) {
                    // Already gone — unregistering twice is not worth a crash.
                }
            }
        }
    }

    /**
     * Called by SDL through JNI on every audio thread that starts
     * (Android_AudioThreadInit).
     *
     * The device id SDL passes is its own audio instance id, not one of ours,
     * so there is nothing device-specific to look up and an id we do not
     * recognise must not be treated as an error. What is known is which
     * direction the thread serves, and that is what the priority is chosen
     * from: capture is the deadline-critical side (samples nobody reads are
     * lost), playback gets the ordinary audio tier.
     */
    public static void audioSetThreadPriority(boolean recording, int deviceId) {
        // The id still earns its keep in the thread name, which is the only
        // place a developer can tell SDL's audio threads apart in a trace.
        Thread.currentThread().setName(recording
                ? "EclipseAudioCapture-" + deviceId
                : "EclipseAudioPlayback-" + deviceId);
        try {
            Process.setThreadPriority(recording
                    ? Process.THREAD_PRIORITY_URGENT_AUDIO
                    : Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException e) {
            // Raising a thread's priority is the platform's call to make; an
            // audio thread that runs at default priority still works, so log
            // and carry on rather than killing SDL's audio startup.
            Log.w(TAG, "could not raise the audio thread priority", e);
        }
    }

    /** Enumeration for API 23 and up: real device ids, real hotplug. */
    private static void beginModernRegistration(AudioManager manager, List<DeviceReport> pending) {
        sModernManager = manager;
        if (sDeviceCallback == null) {
            sDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    stageDeviceChanges(addedDevices, false);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    stageDeviceChanges(removedDevices, true);
                }
            };
        }

        final Set<Long> present = new HashSet<>();
        try {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                    continue; // the in-call path cannot be opened as an SDL device
                }
                present.add(key(false, device.getId()));
                stageAddLocked(false, describeDevice(device), device.getId(), pending);
            }
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                present.add(key(true, device.getId()));
                stageAddLocked(true, describeDevice(device), device.getId(), pending);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot enumerate audio devices", e);
        }

        // Devices reported during an earlier registration that are gone now
        // (unplugged while hotplug was stopped) have to be withdrawn, or SDL
        // would keep offering a device that no longer exists.
        for (Long key : new ArrayList<>(sReported.keySet())) {
            if (!present.contains(key)) {
                stageRemoveLocked(key, pending);
            }
        }

        sRegistered = true;
        try {
            // Android answers this by invoking onAudioDevicesAdded for the
            // current device list on the main thread; those reports find the
            // devices already recorded above and do nothing, which is exactly
            // what a re-announcement of the same ids should do.
            manager.registerAudioDeviceCallback(sDeviceCallback, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot listen for audio device changes", e);
        }
    }

    /**
     * Enumeration for API 21/22, where AudioDeviceInfo does not exist: the two
     * devices every Android has, plus a wired headset tracked through the
     * ACTION_HEADSET_PLUG broadcast. That broadcast is sticky, so registering
     * the receiver also hands us the current plug state.
     */
    private static void beginFixedRegistration(AudioManager manager, Context context,
                                               List<DeviceReport> pending) {
        sRegistered = true;
        stageAddLocked(false, FIXED_PLAYBACK_NAME, FIXED_PLAYBACK_ID, pending);
        stageAddLocked(true, FIXED_RECORDING_NAME, FIXED_RECORDING_ID, pending);
        if (context == null) {
            return;
        }
        sHeadsetReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                    return;
                }
                // The extras are named by the broadcast itself: "state" is 1
                // while something is plugged in, 0 when it is not.
                stageHeadset(intent.getIntExtra("state", 0) != 0);
            }
        };
        Intent state = null;
        try {
            state = context.registerReceiver(sHeadsetReceiver,
                    new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot watch for headset changes", e);
        }
        if (state != null && state.getIntExtra("state", 0) != 0) {
            final List<DeviceReport> headset = new ArrayList<>();
            synchronized (sLock) {
                stageHeadsetLocked(true, headset);
            }
            flush(headset);
        }
    }

    /** Main-thread path: stage under the lock, report after releasing it. */
    private static void stageDeviceChanges(AudioDeviceInfo[] devices, boolean removed) {
        if (devices == null) {
            return;
        }
        final List<DeviceReport> pending = new ArrayList<>();
        synchronized (sLock) {
            if (!sRegistered) {
                return;
            }
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                    continue;
                }
                if (removed) {
                    stageRemoveLocked(key(!device.isSink(), device.getId()), pending);
                } else {
                    stageAddLocked(!device.isSink(), describeDevice(device), device.getId(), pending);
                }
            }
        }
        flush(pending);
    }

    /** Main-thread path, same staging discipline as stageDeviceChanges. */
    private static void stageHeadset(boolean plugged) {
        final List<DeviceReport> pending = new ArrayList<>();
        synchronized (sLock) {
            if (!sRegistered) {
                return;
            }
            stageHeadsetLocked(plugged, pending);
        }
        flush(pending);
    }

    private static void stageHeadsetLocked(boolean plugged, List<DeviceReport> pending) {
        if (plugged) {
            stageAddLocked(false, HEADSET_NAME, FIXED_HEADSET_ID, pending);
        } else {
            stageRemoveLocked(key(false, FIXED_HEADSET_ID), pending);
        }
    }

    private static void stageAddLocked(boolean recording, String name, int deviceId,
                                       List<DeviceReport> pending) {
        final long key = key(recording, deviceId);
        if (sReported.containsKey(key)) {
            return; // already announced: SDL must not hear the same id twice
        }
        final DeviceReport report = new DeviceReport(recording, deviceId, name);
        sReported.put(key, report);
        pending.add(report);
    }

    private static void stageRemoveLocked(long key, List<DeviceReport> pending) {
        final DeviceReport reported = sReported.remove(key);
        if (reported != null) {
            // Only devices we announced ourselves may be withdrawn: SDL takes
            // an unknown id here as a request to disconnect something.
            pending.add(reported.asWithdrawal());
        }
    }

    /** Turns staged reports into native calls — always outside the lock. */
    private static void flush(List<DeviceReport> pending) {
        for (DeviceReport report : pending) {
            if (report.name == null) {
                nativeRemoveAudioDevice(report.recording, report.deviceId);
            } else {
                nativeAddAudioDevice(report.recording, report.name, report.deviceId);
            }
        }
    }

    private static String describeDevice(AudioDeviceInfo device) {
        final CharSequence product = device.getProductName();
        final String name = product != null ? product.toString().trim() : "";
        // Some vendors leave the product name empty; SDL renders whatever it
        // is given, and a blank row in a device picker helps nobody.
        return name.isEmpty() ? "Android audio device" : name;
    }

    private static long key(boolean recording, int deviceId) {
        return ((long) (recording ? 1 : 0) << 32) | (((long) deviceId) & 0xFFFFFFFFL);
    }

    /** One thing to tell SDL; a null name withdraws the device instead. */
    private static final class DeviceReport {
        final boolean recording;
        final int deviceId;
        final String name;

        DeviceReport(boolean recording, int deviceId, String name) {
            this.recording = recording;
            this.deviceId = deviceId;
            this.name = name;
        }

        DeviceReport asWithdrawal() {
            return new DeviceReport(recording, deviceId, null);
        }
    }
}
