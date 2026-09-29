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
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * What SDL draws into, and where Android's input arrives.
 *
 * <p>This is the fork's stand-in for upstream's {@code SDLSurface} plus the parts of
 * {@code SDLActivity} that belong to a view rather than an Activity: the surface callbacks that
 * build and rebuild SDL's window, the key and pointer paths that end in a call to
 * {@link EclipseSDL}, the keyboard, and the window insets SDL draws inside. The sequences are
 * upstream's — surface published before announced, resolution before resize before surface
 * changed, controllers before keyboard — because those are the orders the native side was written
 * against.
 *
 * <p>Everything here runs on the main looper, which is the thread Android delivers input and
 * surface callbacks on and the only thread EclipseSDL lets touch a view. Native calls made from
 * inside these callbacks are the ones SDL makes on purpose from a Java thread; none of them may
 * throw, because an exception escaping a callback reaches a process SDL considers healthy.
 */
public class EclipseSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, View.OnKeyListener, View.OnTouchListener,
        ScaleGestureDetector.OnScaleGestureListener {

    private static final String TAG = "EclipseSurfaceView";

    /**
     * SDL_PenDeviceType values, which live in a header Java has no name for.
     */
    private static final int PEN_DEVICE_UNKNOWN = 0;
    private static final int PEN_DEVICE_DIRECT = 1;
    private static final int PEN_DEVICE_INDIRECT = 2;

    /**
     * The surface's size in pixels, which touch coordinates are normalised against. It starts at
     * the safe 1x1 so that an event arriving before the first surfaceChanged cannot divide by
     * zero; a 1x1 surface normalises to the centre instead, which is the least wrong answer.
     */
    private float mWidth = 1.0f;
    private float mHeight = 1.0f;

    /** The display the surface lives on, for its true pixel size and its refresh rate. */
    private final Display mDisplay;

    /** Turns two moving fingers into the pinch SDL hears as an SDL_EVENT_PINCH_*. */
    private final ScaleGestureDetector mScaleDetector;

    /** The connection the IME is driving, kept so a key event can put its character through it. */
    private volatile EclipseInputConnection mInputConnection;

    /** The input type SDL asked for; read back by the next connection the IME asks to build. */
    private int mInputType = InputType.TYPE_CLASS_TEXT;

    /**
     * Whether SDL has been told the keyboard is up. Two things can find that out — the insets
     * listener from API 30 on, and showKeyboard()/hideKeyboard() below it — and both go through
     * {@link #setKeyboardVisible(boolean)}, which drops the repeat, because SDL wants one shown
     * and one hidden rather than a stream of confirmations of the same state.
     */
    private boolean mKeyboardVisible;

    @SuppressWarnings({ "deprecation", "this-escape" })
    // deprecation: minSdk 21, Display.getRealMetrics() has no API-21 replacement.
    // this-escape: getHolder().addCallback(this) is the constructor handing the
    // view to its own SurfaceHolder, which only ever appends to a list and
    // dispatches from a later layout pass — there is no path by which it can call
    // back into a half-built object.
    public EclipseSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                return reportWindowInsets(insets);
            }
        });
        mScaleDetector = new ScaleGestureDetector(context, this);

        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = (windowManager != null) ? windowManager.getDefaultDisplay() : null;
    }

    // --- attachment ---------------------------------------------------------

    /**
     * Registers the view with {@link EclipseSDL}, which looks for it by name whenever SDL wants
     * something a view can do: show or hide the keyboard, set a cursor, capture the pointer,
     * re-read the insets. Registration waits for attachment rather than running in the
     * constructor because a view that is not on a window yet has no token to do any of that with.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EclipseSDL.registerSurface(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        // Why: the insets are dispatched once on their own schedule, and SDL would otherwise
        // start drawing before it knows where the system bars are.
        requestApplyInsets();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Why: the order is the mirror of registration — SDL stops finding this view before the
        // window it would have found it through goes away.
        EclipseSDL.unregisterSurface(this);
        super.onDetachedFromWindow();
    }

    // --- surface ------------------------------------------------------------

    /**
     * SDL's window exists from here. The surface is published before it is announced because
     * native answers {@code getNativeSurface()} from inside the announcement, and an answer of
     * "there is no surface" at that moment would leave SDL building an EGL surface against
     * nothing.
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        EclipseSDL.setNativeSurface(holder.getSurface());
        EclipseSDL.onNativeSurfaceCreated();
    }

    /**
     * Sizes the window, then tells SDL the surface it already has has changed shape.
     *
     * <p>The order is upstream's and it is not interchangeable: the resolution is what SDL
     * records for the window, the resize is what makes it act on the recording, and the surface
     * change is what rebuilds the EGL surface from the window those two have just settled.
     */
    @Override
    @SuppressWarnings("deprecation") // minSdk 21: Display.getRealMetrics() has no API-21 replacement
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width <= 0 || height <= 0) {
            // Why: a zero here would reach native as a width SDL draws nothing into, and the
            // division the normalisation does later would divide by it.
            return;
        }
        mWidth = width;
        mHeight = height;

        int deviceWidth = width;
        int deviceHeight = height;
        float density = 1.0f;
        float refreshRate = 60.0f;
        try {
            Display display = mDisplay;
            if (display != null) {
                DisplayMetrics realMetrics = new DisplayMetrics();
                display.getRealMetrics(realMetrics);
                deviceWidth = realMetrics.widthPixels;
                deviceHeight = realMetrics.heightPixels;
                // Why: density is reported as a multiple of 160dpi so that SDL's idea of a
                // device pixel matches the one the UI was laid out with, not the raw dpi.
                density = (float) realMetrics.densityDpi / 160.0f;
                refreshRate = display.getRefreshRate();
            }
        } catch (RuntimeException e) {
            // Why: the display can be gone by the time a teardown runs through here, and a
            // number that never arrived would reach native as zero.
            Log.w(TAG, "display metrics unavailable: " + e);
        }

        EclipseSDL.nativeSetScreenResolution(width, height, deviceWidth, deviceHeight,
                density, refreshRate);
        EclipseSDL.onNativeResize();
        // Why: a rotation is only interesting once Android has laid the surface out the new way
        // round, and this is where that has just happened; both halves dedupe, so saying it on
        // every size change costs nothing.
        EclipseSDL.publishDisplayState();
        EclipseSDL.onNativeSurfaceChanged();
    }

    /**
     * The mirror of surfaceCreated(): the surface stops being findable before native is told to
     * let go of it, for the same reason — nothing may hand EGL a buffer that is already gone.
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        EclipseSDL.clearNativeSurface();
        EclipseSDL.onNativeSurfaceDestroyed();
    }

    // --- window state -------------------------------------------------------

    /**
     * Reports the insets SDL draws inside and whether the keyboard is up.
     *
     * <p>Both readings are API 30's: before it there is no way to ask which kind of inset a
     * value is, and no way at all to ask whether the keyboard is showing — the platform answers
     * that one only by not telling you. Below 30 SDL therefore gets no inset numbers, matching
     * upstream, and the keyboard's own show/hide calls (which always arrive in pairs) are the
     * only signal it receives.
     */
    private WindowInsets reportWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets combined = insets.getInsets(
                    WindowInsets.Type.systemBars()
                    | WindowInsets.Type.systemGestures()
                    | WindowInsets.Type.mandatorySystemGestures()
                    | WindowInsets.Type.tappableElement()
                    | WindowInsets.Type.displayCutout());
            EclipseSDL.onNativeInsetsChanged(combined.left, combined.right, combined.top,
                    combined.bottom);
            setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()));
        }
        // Why: returned unchanged so that any child view inherits the same insets — SDL is the
        // only consumer here, but it is not the only thing that may ever be inside this window.
        return insets;
    }

    /**
     * A rotation that Android reports without touching the surface: the resources were laid out
     * again and the display is turning, which is exactly what
     * {@link EclipseSDL#publishDisplayState()} exists to pass on. It dedupes, so a rotation that
     * a surface change already reported is not sent twice.
     */
    @Override
    protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        EclipseSDL.publishDisplayState();
    }

    /**
     * SDL learns of focus through the host Activity, which owns the window and sees every change
     * to it; this view reports none of that, deliberately, so that one event cannot arrive twice.
     *
     * <p>What it does renew is pointer capture: Android releases capture when the window loses
     * focus and never hands it back on its own, so without this SDL's relative mode would quietly
     * stop working after the first dialog stole focus.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= 26 && EclipseSDL.isRelativeMouse()) {
            try {
                requestPointerCapture();
            } catch (RuntimeException e) {
                Log.w(TAG, "pointer capture would not renew: " + e);
            }
        }
    }

    // --- keys ---------------------------------------------------------------

    /**
     * Everything Android has to say to SDL, in the order SDL has to hear it:
     * device keys that are not the app's to eat, then whether SDL is listening at all, then the
     * controller, then the keyboard.
     */
    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        // Why: these belong to the device, not to the application — upstream SDL steps over them
        // in exactly the same place, and a launcher that swallowed the volume rocker would be
        // broken on every phone it ran on.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_ZOOM_IN
                || keyCode == KeyEvent.KEYCODE_ZOOM_OUT) {
            return false;
        }
        // Why: outside startMain() SDL has no input subsystem to receive anything, so Android
        // keeps its keys — the back key above all, which would otherwise be consumed by
        // something with nobody left to give it to.
        if (!EclipseSDL.isMainStarted()) {
            return false;
        }

        // Controllers first: a pad's buttons arrive as key events, and SDL's joystick is a
        // different device from its keyboard — the same press must not become both. Whether it
        // maps to an SDL button is native's answer to give, so a pad key that maps to nothing
        // falls through and is handled as the key it also is.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (EclipseControllerManager.onKeyDown(event)) {
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (EclipseControllerManager.onKeyUp(event)) {
                return true;
            }
        }

        final int source = resolveSource(event);
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_FORWARD)) {
            // Why: mice send their back and forward buttons as key events, and letting them
            // through would run Android's back handling — a second back, alongside the one SDL
            // decides about itself.
            return true;
        }

        // Why: the bracket tells the input connection that the character arriving with this key
        // is already on its way into SDL as a key, so it must not be turned into a second copy
        // of itself; see EclipseSDL.isDispatchingKey().
        EclipseSDL.beginKeyDispatch();
        try {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                EclipseSDL.onNativeKeyDown(keyCode);
                if (isTextInputEvent(event)) {
                    commitKeyText(event);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                EclipseSDL.onNativeKeyUp(keyCode);
                return true;
            }
            return false;
        } finally {
            EclipseSDL.endKeyDispatch();
        }
    }

    /**
     * The keyboard's own back never reaches the window as a key — it is spent closing the
     * keyboard — so this is the one place SDL can be told that the IME went away, and SDL is what
     * has to stop text input when it does. Mirrors upstream's SDLDummyEdit, including not
     * consuming the event: the back itself still belongs to whoever else wants it.
     */
    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                && mKeyboardVisible) {
            EclipseSDL.onNativeKeyboardFocusLost();
        }
        return super.onKeyPreIme(keyCode, event);
    }

    /**
     * Whether a key is a character rather than a command. Ctrl turns it into a shortcut — SDL
     * wants the combination as key events and not as text — and everything else printable,
     * spaces included, is text.
     */
    private static boolean isTextInputEvent(KeyEvent event) {
        if (event.isCtrlPressed()) {
            return false;
        }
        return event.isPrintingKey() || event.getKeyCode() == KeyEvent.KEYCODE_SPACE;
    }

    /**
     * Hands a printable key's character to SDL. It goes through the connection when the IME has
     * built one, because that path also updates the buffer the next diff is measured against;
     * straight to native when it has not, because then there is no buffer to keep in step.
     */
    private void commitKeyText(KeyEvent event) {
        String text = String.valueOf((char) event.getUnicodeChar());
        EclipseInputConnection connection = mInputConnection;
        if (connection != null) {
            connection.commitText(text, 1);
        } else {
            EclipseInputConnection.nativeCommitText(text, 1);
        }
    }

    /**
     * The source the event claims, falling back to the device's own sources: a controller that
     * reports itself as a keyboard is the usual reason the event's source is not enough.
     */
    private static int resolveSource(KeyEvent event) {
        int source = event.getSource();
        if (source == InputDevice.SOURCE_UNKNOWN) {
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            if (device != null) {
                source = device.getSources();
            }
        }
        return source;
    }

    // --- pointers -----------------------------------------------------------

    /**
     * Touch, mouse clicks and pen contact — everything the platform dispatches as a pointer event
     * and hands to the view rather than to a listener elsewhere. SDL is listening from
     * {@link #startGuard()} on, and the controller has first refusal, in both cases for the same
     * reason as the key path.
     */
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!EclipseSDL.isMainStarted()) {
            return false;
        }
        if (EclipseControllerManager.onMotionEvent(event)) {
            return true;
        }
        reportPointers(event);
        mScaleDetector.onTouchEvent(event);
        return true;
    }

    /**
     * Hover, wheel, and the captured pointer — the stream the platform uses for anything that is
     * not a touch event. Two of its three callers are worth naming:
     *
     * <ul>
     *   <li>While the pointer is captured, the platform relabels mouse events
     *       {@code SOURCE_MOUSE_RELATIVE} and reports the distance in X/Y itself; those are read
     *       here rather than through onTouch, because a captured event is no longer a touch
     *       event.</li>
     *   <li>The same stream carries joystick axes, which the controller has just had first
     *       refusal on, and finger hover, which SDL has no event for. Only the three tools SDL
     *       draws with are reported, so neither can arrive as an invented contact.</li>
     * </ul>
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!EclipseSDL.isMainStarted()) {
            return false;
        }
        if (EclipseControllerManager.onMotionEvent(event)) {
            return true;
        }
        final int pointerCount = event.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE
                    || toolType == MotionEvent.TOOL_TYPE_STYLUS
                    || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                reportPointers(event);
                return true;
            }
        }
        return false;
    }

    /**
     * Pointer capture's own path, for the platforms that deliver captured motion here rather
     * than through the generic stream. The events are already distances — see
     * {@link #isRelativeEvent(MotionEvent, int)} — so they need no special reading, only the
     * same reporting any other pointer event gets.
     */
    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (!EclipseSDL.isMainStarted()) {
            return false;
        }
        if (EclipseControllerManager.onMotionEvent(event)) {
            return true;
        }
        reportPointers(event);
        return true;
    }

    /**
     * Turns a pointer event into SDL's touch, mouse or pen input — whichever tool produced it.
     *
     * <p>The second and later fingers of a gesture arrive as their own event naming the one
     * pointer that changed, so only that pointer is reported; reporting the others at the same
     * moment would replay them as though they had moved.
     */
    private void reportPointers(MotionEvent event) {
        final int action = event.getActionMasked();
        final int pointerCount = event.getPointerCount();
        final boolean singlePointer = action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP;
        int index = singlePointer ? event.getActionIndex() : 0;
        do {
            reportTool(event, index, action);
            if (singlePointer) {
                break;
            }
        } while (++index < pointerCount);
    }

    /** Reports one pointer to SDL under whichever of the three devices it is. */
    private void reportTool(MotionEvent event, int index, int action) {
        final int toolType = event.getToolType(index);
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            if (action == MotionEvent.ACTION_SCROLL) {
                // Why: the wheel is two amounts of travel, not two positions — SDL reads them as
                // horizontal and vertical notches, and coordinates here would be meaningless.
                EclipseSDL.onNativeMouse(0, action,
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL, index),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL, index), false);
            } else {
                EclipseSDL.onNativeMouse(event.getButtonState(), action, eventX(event, index),
                        eventY(event, index), isRelativeEvent(event, index));
            }
        } else if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            EclipseSDL.onNativePen(event.getPointerId(index), penDeviceType(event.getDevice()),
                    penButtons(event, toolType), action, event.getX(index), event.getY(index),
                    clampPressure(event.getPressure(index)));
        } else {
            EclipseSDL.onNativeTouch(event.getDeviceId(), event.getPointerId(index), action,
                    normalise(event.getX(index), mWidth), normalise(event.getY(index), mHeight),
                    clampPressure(event.getPressure(index)));
        }
    }

    /**
     * Turns a pixel into SDL's 0..1 touch space, against the last pixel rather than past it, so
     * that a finger on the far edge lands exactly on 1. A surface SDL has not measured yet reads
     * as the centre: there is no correct answer, and a division by zero is worse than a wrong
     * one.
     */
    private static float normalise(float coordinate, float extent) {
        if (extent <= 1.0f) {
            return 0.5f;
        }
        return coordinate / (extent - 1.0f);
    }

    /**
     * Pressure as SDL wants it. Android documents that this can come back above 1 on hardware
     * that has no idea how hard it is being pressed, and SDL reads the value as 0..1 — an
     * unclamped one would be a harder press than the device can make.
     */
    private static float clampPressure(float pressure) {
        return (pressure > 1.0f) ? 1.0f : pressure;
    }

    /**
     * True when a mouse event carries a distance instead of a position, which is also the signal
     * that {@link #eventX(MotionEvent, int)} has to read the relative axes.
     *
     * <p>Two regimes, told apart by whether capture exists yet. From API 26 the platform says so
     * itself: a captured pointer — and any relative mouse it labels as one — arrives as
     * {@code SOURCE_MOUSE_RELATIVE} with the distance already in X/Y, and believing SDL's flag
     * instead while capture was refused would send positions off as movement and throw the
     * cursor across the screen. Below 26 there is no capture to ask for, so the flag SDL set is
     * the only evidence there is, and the axes, not X/Y, carry the distance.
     */
    private static boolean isRelativeEvent(MotionEvent event, int index) {
        if (Build.VERSION.SDK_INT >= 26) {
            return event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE;
        }
        return EclipseSDL.isRelativeMouse()
                && event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    /**
     * A mouse event's X: its own coordinate, or the distance the relative axes carry. The
     * relative axes exist from API 24, but so does the only flag that reaches this branch —
     * {@code supportsRelativeMouse()} refuses below 24, so there is no path that asks for an
     * axis the platform does not have.
     */
    private static float eventX(MotionEvent event, int index) {
        if (Build.VERSION.SDK_INT < 26 && isRelativeEvent(event, index)) {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, index);
        }
        return event.getX(index);
    }

    /** @see #eventX(MotionEvent, int) */
    private static float eventY(MotionEvent event, int index) {
        if (Build.VERSION.SDK_INT < 26 && isRelativeEvent(event, index)) {
            return event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, index);
        }
        return event.getY(index);
    }

    /** SDL_PenDeviceType for a device; only API 29 can tell a built-in pen from an outside one. */
    private static int penDeviceType(InputDevice device) {
        if (Build.VERSION.SDK_INT >= 29 && device != null) {
            return device.isExternal() ? PEN_DEVICE_INDIRECT : PEN_DEVICE_DIRECT;
        }
        return PEN_DEVICE_UNKNOWN;
    }

    /**
     * The pen's buttons in SDL's terms. Android packs the stylus buttons at bits 5 and 6 and SDL
     * reads them at 1 and 2, the contact tip has no button bit of its own in Android's view of
     * the world, and the eraser is bit 30 — SDL decides from the action which of tip and eraser
     * is down, so both flags travel every time rather than only when they are true.
     */
    private static int penButtons(MotionEvent event, int toolType) {
        int buttons = (event.getButtonState() >> 4) | (1 << 0);
        if (toolType == MotionEvent.TOOL_TYPE_ERASER) {
            buttons |= (1 << 30);
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
            buttons |= 0x08;
        }
        return buttons;
    }

    /**
     * SDL has no pointer icon of its own to answer with, and the platform asks this question on
     * a path where an exception would take down the dispatch — upstream guards it the same way.
     */
    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        try {
            return super.onResolvePointerIcon(event, pointerIndex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- pinch --------------------------------------------------------------

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        EclipseSDL.onNativePinchUpdate(detector.getScaleFactor());
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        EclipseSDL.onNativePinchStart();
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        EclipseSDL.onNativePinchEnd();
    }

    // --- the keyboard -------------------------------------------------------

    /**
     * SDL has no text widget: this view is the editor the IME attaches to, and without this
     * answer Android never asks for a connection in the first place.
     */
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * Builds the connection the IME will drive. A new one each time is deliberate — the input
     * type SDL last asked for has to take effect, and a connection carries the input type it was
     * built with — which also means every session starts from an empty buffer. SDL does not mind:
     * its text input is a stream with no buffer to fall out of step with.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = mInputType;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        EclipseInputConnection connection = new EclipseInputConnection(this, true);
        mInputConnection = connection;
        return connection;
    }

    /**
     * Brings up the keyboard SDL asked for, on this view.
     *
     * <p>Called on the main looper by {@link EclipseSDL#showTextInput(int, int, int, int, int)}.
     * The rectangle SDL sends is where a detached editor widget would have been pinned on screen;
     * there is no detached widget here, so the input type is what survives the trip and the
     * rectangle is accepted only because the signature is fixed. Focus is taken first because the
     * IME attaches to whichever view currently holds it.
     *
     * @param inputType an {@code InputType.TYPE_*} value, carried into the next EditorInfo
     */
    public void showKeyboard(int inputType) {
        mInputType = inputType;
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        InputMethodManager manager = inputMethodManager();
        if (manager == null) {
            return;
        }
        try {
            // Why: an IME already up against the old input type keeps the connection it has, so
            // a restart is what makes it read the new one.
            if (mInputConnection != null) {
                manager.restartInput(this);
            }
            boolean shown = manager.showSoftInput(this, 0);
            if (!shown) {
                shown = manager.isAcceptingText();
            }
            if (Build.VERSION.SDK_INT < 30) {
                // Why: from 30 the insets listener reports the real visibility and this would
                // only ever race it; before 30 there is no other signal that one exists.
                setKeyboardVisible(shown);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "the keyboard would not show: " + e);
        }
    }

    /**
     * Drops the keyboard. This is what {@code COMMAND_TEXTEDIT_HIDE} arrives as, which is how SDL
     * answers a return key it consumed or a text input it stopped.
     */
    public void hideKeyboard() {
        InputMethodManager manager = inputMethodManager();
        if (manager != null) {
            try {
                manager.hideSoftInputFromWindow(getWindowToken(), 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "the keyboard would not hide: " + e);
            }
        }
        if (Build.VERSION.SDK_INT < 30) {
            setKeyboardVisible(false);
        }
    }

    /**
     * The one place SDL hears about the keyboard's presence, so the two ways of finding out
     * cannot disagree by reporting the same transition twice: the flag is what the next caller
     * is measured against, and native is told only when it actually changes.
     */
    private void setKeyboardVisible(boolean visible) {
        if (mKeyboardVisible == visible) {
            return;
        }
        mKeyboardVisible = visible;
        if (visible) {
            EclipseSDL.onNativeScreenKeyboardShown();
        } else {
            EclipseSDL.onNativeScreenKeyboardHidden();
        }
    }

    private InputMethodManager inputMethodManager() {
        Object service = getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        return (service instanceof InputMethodManager) ? (InputMethodManager) service : null;
    }
}
