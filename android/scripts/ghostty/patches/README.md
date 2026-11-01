# Ghostty patches

Applied by `scripts/build-ghostty.sh` on top of the pinned `GHOSTTY_COMMIT`.

- `0001-link-android-libvt-with-libc.patch`: from herdr-mobile (`modules/t3-terminal`, MIT). Without it
  libghostty-vt has no `DT_NEEDED libc.so` on Android and `dlopen` fails with `cannot locate symbol "getauxval"`.
  It also silences the stderr logger on Android. Re-check whether upstream fixed this before dropping it.
- Not needed at the pinned commit: herdr-mobile's 16 KB patch (`link_z_max_page_size = 16384` is upstream now).
