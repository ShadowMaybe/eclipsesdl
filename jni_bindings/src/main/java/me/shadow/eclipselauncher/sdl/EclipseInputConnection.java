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

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

/**
 * The editor the soft keyboard types into, and the only place a character turns into SDL text.
 *
 * <p>SDL keeps no widget of its own: {@link EclipseSDL#showTextInput(int, int, int, int, int)}
 * asks for the keyboard, the keyboard edits the buffer behind {@link #getEditable()}, and the two
 * natives at the bottom of this file carry the difference between that buffer and what SDL already
 * has across the JNI boundary. Everything above them is the bookkeeping that keeps the difference
 * exact; see {@link #updateText()}.
 *
 * <p>Nothing here may throw. The calls arrive from the IME on a path where an exception would land
 * in a process SDL believes is healthy, and native reads its own arguments without checking them —
 * {@code nativeCommitText} hands its string straight to {@code GetStringUTFChars}, so a null it did
 * not expect would be a crash rather than an error.
 */
public class EclipseInputConnection extends BaseInputConnection {

    /**
     * The document the IME edits. BaseInputConnection routes every edit it performs — commit,
     * composing, delete — through {@link #getEditable()}, so supplying one here is what makes the
     * base class's behaviour this class's buffer rather than a private scratch copy.
     */
    private final Editable mEditable = Editable.Factory.getInstance().newEditable("");

    /** Guards the buffer and the diff against an IME that edits from more than one thread. */
    private final Object mEditLock = new Object();

    /** What SDL has been told so far, so only the difference between it and the buffer is sent. */
    private String mCommittedText = "";

    public EclipseInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
        // Why: a buffer with no selection range reports -1 for the cursor, and the delete path
        // in BaseInputConnection refuses to act on -1 — the first backspace from an IME would be
        // dropped, and the buffer would drift away from SDL from there on.
        Selection.setSelection(mEditable, 0);
    }

    @Override
    public Editable getEditable() {
        return mEditable;
    }

    /**
     * The return key is the one key an IME still delivers as a key event. SDL is asked first
     * because it owns the decision: with {@code SDL_HINT_RETURN_KEY_HIDES_IME} set, it stops text
     * input and the key is consumed here so it cannot also arrive in the game as a second event.
     */
    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
            if (EclipseSDL.onNativeSoftReturnKey()) {
                return true;
            }
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        synchronized (mEditLock) {
            if (!super.commitText(text, newCursorPosition)) {
                return false;
            }
            updateText();
            return true;
        }
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        synchronized (mEditLock) {
            if (!super.setComposingText(text, newCursorPosition)) {
                return false;
            }
            updateText();
            return true;
        }
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        synchronized (mEditLock) {
            if (!super.deleteSurroundingText(beforeLength, afterLength)) {
                return false;
            }
            updateText();
            return true;
        }
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        synchronized (mEditLock) {
            if (!super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)) {
                return false;
            }
            updateText();
            return true;
        }
    }

    /**
     * Tell SDL what changed since the last time it was told.
     *
     * <p>SDL's text input is a stream with no document and no cursor: characters arrive, and
     * backspace takes the last one away. The IME, meanwhile, edits a document. The translation
     * between the two is therefore a diff, and the diff is exact rather than approximate —
     * agreement between the buffer and SDL is a shared prefix, and "keep the prefix, backspace the
     * rest of the old text, send the rest of the new text" reproduces any single edit in a
     * cursorless stream, however far apart the two texts are.
     *
     * <p>ASCII additions also produce a scancode, so a typed letter reaches SDL as a key *and* as
     * text the way a physical keyboard would. That copy is skipped while the key that carried it
     * is still travelling into SDL (see {@link EclipseSDL#isDispatchingKey()}), because SDL is
     * about to receive the same character from the key itself and would show it twice.
     */
    private void updateText() {
        final Editable content = getEditable();
        if (content == null) {
            return;
        }
        final String text = content.toString();
        final int compareLength = Math.min(text.length(), mCommittedText.length());
        int matchLength;
        int offset;

        for (matchLength = 0; matchLength < compareLength; ) {
            int codePoint = mCommittedText.codePointAt(matchLength);
            if (codePoint != text.codePointAt(matchLength)) {
                break;
            }
            matchLength += Character.charCount(codePoint);
        }

        for (offset = matchLength; offset < mCommittedText.length(); ) {
            int codePoint = mCommittedText.codePointAt(offset);
            offset += Character.charCount(codePoint);
            // Why: one backspace per code point, not per char — a character outside the basic
            // plane has to leave as a pair, or SDL is left holding half of one.
            nativeGenerateScancodeForUnichar('\b');
        }

        if (matchLength < text.length()) {
            String pendingText = text.substring(matchLength);
            if (!EclipseSDL.isDispatchingKey()) {
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n' && EclipseSDL.onNativeSoftReturnKey()) {
                        // SDL has just stopped text input: the newline was the submit key, not
                        // text, and there is nowhere left to send it. The marker still moves on —
                        // leaving it behind would make the next diff replay this newline forever,
                        // which is the one thing this loop must never do.
                        mCommittedText = text;
                        return;
                    }
                    // SDL has a scancode only for what ASCII can name. Above that the character
                    // exists as text and nothing else, which is what nativeCommitText carries.
                    if (codePoint > 0 && codePoint < 128) {
                        nativeGenerateScancodeForUnichar((char) codePoint);
                    }
                    offset += Character.charCount(codePoint);
                }
            }
            // Why: never null — native passes the string to GetStringUTFChars without a check,
            // and substring() of a non-null text cannot be one either.
            nativeCommitText(pendingText, 0);
        }
        mCommittedText = text;
    }

    /**
     * Hands committed text to SDL, where it becomes an SDL_TEXTINPUT event.
     *
     * @param text             the characters to send, never null
     * @param newCursorPosition the IME's cursor hint; native ignores it, and the signature is
     *                          fixed by the interface
     */
    public static native void nativeCommitText(String text, int newCursorPosition);

    /**
     * Makes SDL see a character as a key press and release, which is what a physical keyboard
     * does and what {@code '\b'} uses to take one back.
     *
     * @param c the character to synthesize; {@code 0} is not a character and is never sent
     */
    public static native void nativeGenerateScancodeForUnichar(char c);
}
