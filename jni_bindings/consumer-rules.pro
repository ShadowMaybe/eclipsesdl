# Keep rules that ship with this AAR.
#
# SDL reaches these classes from C in two ways: by class name (FindClass with a
# hard-coded string in nativeSetupJNI) and by method name (GetStaticMethodID
# and RegisterNatives, both with hard-coded strings). Rename a class and
# registration fails; rename a method and the callback silently never arrives —
# audio stops, clipboard stops, the gamepad stops, and nothing on screen says
# why. R8 is free to do all of that, because none of the names appear in a way
# it can see.
#
# So every member of every bound class is kept as written.

-keep class me.shadow.eclipselauncher.sdl.EclipseSDL { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseInputConnection { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseAudioManager { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseControllerManager { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseHIDDeviceManager { *; }

# Belt and braces: any other class that grows a native method gets kept by the
# same rule ProGuard itself ships for JNI, rather than needing an entry here.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The launcher-facing surface helper is called by name from app code, so keep
# it intact too.
-keep class me.shadow.eclipselauncher.sdl.EclipseSurfaceView { *; }
