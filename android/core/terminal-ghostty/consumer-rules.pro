# JNI_OnLoad binds every method below with RegisterNatives by name and signature; R8 must not
# rename or drop any of them (an unused, removed one makes RegisterNatives and the library load fail).
-keep class com.mtajchert.flocket.core.terminal.ghostty.GhosttyNative {
    native <methods>;
}
