# Rules for shrinking this module's own release build. Release builds of an
# Android library leave minification off (see build.gradle) — renaming members
# here would break the JNI binding above — but these are kept so that flipping
# minifyEnabled on locally still produces a library that works.

-keep class me.shadow.eclipselauncher.sdl.EclipseSDL { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseInputConnection { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseAudioManager { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseControllerManager { *; }
-keep class me.shadow.eclipselauncher.sdl.EclipseHIDDeviceManager { *; }
