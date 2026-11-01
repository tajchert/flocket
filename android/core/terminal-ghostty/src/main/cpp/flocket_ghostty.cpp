// JNI shim between com.mtajchert.flocket.core.terminal.ghostty.GhosttyNative and libghostty-vt.
// Design: one Engine per terminal; no JNI calls from inside libghostty callbacks; bulk data moves in
// primitive arrays supplied by Kotlin. Not thread-safe: the owner confines an Engine to one thread.
// Documentation: android/docs/TERMINAL_ENGINE.md.
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <ghostty/vt.h>

namespace {

enum EventType : uint8_t { kTitle = 1, kBell = 2, kClipboard = 3, kPwd = 4, kNotification = 5 };

struct Engine {
  GhosttyTerminal terminal = nullptr;
  GhosttyRenderState render = nullptr;
  GhosttyRenderStateRowIterator rows = nullptr;
  GhosttyRenderStateRowCells cells = nullptr;
  GhosttyKeyEncoder key_encoder = nullptr;
  GhosttyKeyEvent key_event = nullptr;
  int cols = 0;
  int row_count = 0;
  std::vector<int32_t> graphemes;  // [cellIndex, n, cp0..cpN-1]*
  std::vector<uint8_t> events;     // see drainEvents
  bool theme_dirty = false;
  int32_t title_gen = 0;
};

void put_u32(std::vector<uint8_t>& out, uint32_t v) {
  for (int i = 0; i < 4; i++) out.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

void put_str(std::vector<uint8_t>& out, const uint8_t* p, size_t n) {
  put_u32(out, static_cast<uint32_t>(n));
  if (n) out.insert(out.end(), p, p + n);
}

void begin_event(Engine* e, EventType t) { e->events.push_back(t); }

// ---- libghostty callbacks (userdata = Engine*). WRITE_PTY is deliberately never installed:
// the host core answers terminal queries, replies are dropped.

void on_bell(GhosttyTerminal, void* ud) {
  auto* e = static_cast<Engine*>(ud);
  begin_event(e, kBell);
}

void on_title(GhosttyTerminal t, void* ud) {
  auto* e = static_cast<Engine*>(ud);
  GhosttyString s{};
  if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_TITLE, &s) != GHOSTTY_SUCCESS) return;
  e->title_gen++;
  begin_event(e, kTitle);
  put_str(e->events, s.ptr, s.len);
}

void on_pwd(GhosttyTerminal t, void* ud) {
  auto* e = static_cast<Engine*>(ud);
  GhosttyString s{};
  if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_PWD, &s) != GHOSTTY_SUCCESS) return;
  begin_event(e, kPwd);
  put_str(e->events, s.ptr, s.len);
}

void on_clipboard_write(GhosttyTerminal, void* ud, const GhosttyClipboardWrite* w) {
  auto* e = static_cast<Engine*>(ud);
  const GhosttyClipboardContent* pick = nullptr;
  for (size_t i = 0; i < w->contents_len; i++) {
    const auto& c = w->contents[i];
    if (c.mime.len >= 10 && memcmp(c.mime.ptr, "text/plain", 10) == 0) { pick = &c; break; }
    if (!pick) pick = &c;
  }
  if (pick) {
    begin_event(e, kClipboard);
    put_str(e->events, pick->data.ptr, pick->data.len);
  }
  // Acknowledge; whether the UI honors the write is decided above this layer.
  GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
  reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  reply.remember = false;
  if (w->reply) w->reply(w, &reply);
}

void on_notification(GhosttyTerminal, void* ud, const GhosttyTerminalDesktopNotification* n) {
  auto* e = static_cast<Engine*>(ud);
  begin_event(e, kNotification);
  put_str(e->events, n->title.ptr, n->title.len);
  put_str(e->events, n->body.ptr, n->body.len);
}

Engine* H(jlong h) { return reinterpret_cast<Engine*>(h); }

jlong nCreate(JNIEnv*, jclass, jint cols, jint rows, jint scrollbackLines) {
  auto* e = new Engine();
  e->cols = cols;
  e->row_count = rows;
  if (ghostty_terminal_new(nullptr, &e->terminal, static_cast<uint16_t>(cols), static_cast<uint16_t>(rows)) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_new(nullptr, &e->render) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(nullptr, &e->rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(nullptr, &e->cells) != GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(nullptr, &e->key_encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(nullptr, &e->key_event) != GHOSTTY_SUCCESS) {
    ghostty_key_event_free(e->key_event);
    ghostty_key_encoder_free(e->key_encoder);
    ghostty_render_state_row_cells_free(e->cells);
    ghostty_render_state_row_iterator_free(e->rows);
    ghostty_render_state_free(e->render);
    ghostty_terminal_free(e->terminal);
    delete e;
    return 0;
  }
  size_t lines = static_cast<size_t>(scrollbackLines);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, e);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_BELL, reinterpret_cast<const void*>(&on_bell));
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, reinterpret_cast<const void*>(&on_title));
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_PWD_CHANGED, reinterpret_cast<const void*>(&on_pwd));
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                       reinterpret_cast<const void*>(&on_clipboard_write));
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION,
                       reinterpret_cast<const void*>(&on_notification));
  return reinterpret_cast<jlong>(e);
}

void nFree(JNIEnv*, jclass, jlong h) {
  Engine* e = H(h);
  if (!e) return;
  ghostty_key_event_free(e->key_event);
  ghostty_key_encoder_free(e->key_encoder);
  ghostty_render_state_row_cells_free(e->cells);
  ghostty_render_state_row_iterator_free(e->rows);
  ghostty_render_state_free(e->render);
  ghostty_terminal_free(e->terminal);
  delete e;
}

void nFeed(JNIEnv* env, jclass, jlong h, jbyteArray bytes, jint off, jint len) {
  Engine* e = H(h);
  if (len <= 0) return;
  void* p = env->GetPrimitiveArrayCritical(bytes, nullptr);
  if (!p) return;
  ghostty_terminal_vt_write(e->terminal, static_cast<const uint8_t*>(p) + off, static_cast<size_t>(len));
  env->ReleasePrimitiveArrayCritical(bytes, p, JNI_ABORT);
}

void nResize(JNIEnv*, jclass, jlong h, jint cols, jint rows) {
  Engine* e = H(h);
  ghostty_terminal_resize(e->terminal, static_cast<uint16_t>(cols), static_cast<uint16_t>(rows), 8, 16);
  e->cols = cols;
  e->row_count = rows;
}

GhosttyColorRgb rgb_of(jint argb) {
  return GhosttyColorRgb{static_cast<uint8_t>(argb >> 16), static_cast<uint8_t>(argb >> 8),
                         static_cast<uint8_t>(argb)};
}

void nSetTheme(JNIEnv* env, jclass, jlong h, jint fg, jint bg, jint cursor, jintArray palette16) {
  Engine* e = H(h);
  GhosttyColorRgb c = rgb_of(fg);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &c);
  c = rgb_of(bg);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &c);
  c = rgb_of(cursor);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c);
  GhosttyColorRgb pal[256];
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, pal) != GHOSTTY_SUCCESS) return;
  jint p16[16];
  env->GetIntArrayRegion(palette16, 0, 16, p16);
  for (int i = 0; i < 16; i++) pal[i] = rgb_of(p16[i]);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
  // Theme changes recolor every cell without marking rows dirty.
  e->theme_dirty = true;
}

inline int32_t pack(GhosttyColorRgb c) {
  return static_cast<int32_t>(0xFF000000u | (uint32_t(c.r) << 16) | (uint32_t(c.g) << 8) | c.b);
}

inline GhosttyColorRgb mix(GhosttyColorRgb a, GhosttyColorRgb b, int wa, int wb) {  // (a*wa + b*wb)/(wa+wb)
  int t = wa + wb;
  return GhosttyColorRgb{static_cast<uint8_t>((a.r * wa + b.r * wb) / t), static_cast<uint8_t>((a.g * wa + b.g * wb) / t),
                         static_cast<uint8_t>((a.b * wa + b.b * wb) / t)};
}

// Flags mirror CellFlags in TerminalEngine.kt.
enum : int32_t {
  F_BOLD = 1, F_ITALIC = 2, F_UNDERLINE = 4, F_STRIKE = 8, F_FAINT = 16, F_INVISIBLE = 32, F_BLINK = 64,
  F_WIDE = 128, F_SPACER = 256, F_LINK = 512
};

// hdr layout (IntArray(16)): 0 cursorCol, 1 cursorRow, 2 cursorVisible, 3 cursorStyle (0 block, 1 underline,
// 2 bar), 4 scrollbackRows, 5 viewportOffset, 6 graphemeInts, 7 cols, 8 rows, 9 altScreen, 10 anyDirty,
// 11 titleGeneration.
void nSnapshot(JNIEnv* env, jclass, jlong h, jboolean full, jint cols, jint rows, jintArray jcp, jintArray jfg,
               jintArray jbg, jintArray jflags, jintArray jdirty, jintArray jhdr) {
  Engine* e = H(h);
  e->graphemes.clear();
  if (ghostty_render_state_update(e->render, e->terminal) != GHOSTTY_SUCCESS) return;
  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  bool all = full || e->theme_dirty || dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;
  e->theme_dirty = false;

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  auto* cp = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jcp, nullptr));
  auto* fgA = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jfg, nullptr));
  auto* bgA = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jbg, nullptr));
  auto* flA = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jflags, nullptr));
  auto* dirtyA = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jdirty, nullptr));
  auto* hdr = static_cast<int32_t*>(env->GetPrimitiveArrayCritical(jhdr, nullptr));

  std::fill(dirtyA, dirtyA + rows, 0);
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &e->rows);
  int y = -1;
  uint16_t dy = 0;
  bool any = false;
  while (all ? ghostty_render_state_row_iterator_next(e->rows)
             : ghostty_render_state_row_iterator_next_dirty(e->rows, &dy)) {
    y = all ? y + 1 : dy;
    if (y >= rows) break;
    any = true;
    dirtyA[y] = 1;
    if (ghostty_render_state_row_get(e->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &e->cells) != GHOSTTY_SUCCESS) {
      continue;
    }
    int col = 0;
    while (col < cols && ghostty_render_state_row_cells_next(e->cells)) {
      int idx = y * cols + col;
      col++;
      uint32_t n = 0;
      ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &n);
      int32_t first = 0;
      if (n > 0) {
        uint32_t buf[16];
        uint32_t m = n < 16 ? n : 16;
        if (n <= 16) {
          ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, buf);
        } else {
          std::vector<uint32_t> big(n);
          ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, big.data());
          memcpy(buf, big.data(), sizeof(buf));
        }
        first = static_cast<int32_t>(buf[0]);
        if (n > 1) {
          e->graphemes.push_back(idx);
          e->graphemes.push_back(static_cast<int32_t>(m));
          for (uint32_t i = 0; i < m; i++) e->graphemes.push_back(static_cast<int32_t>(buf[i]));
        }
      }
      GhosttyCell raw = 0;
      ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      bool link = false;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &link);
      bool styled = false;
      ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &styled);

      int32_t flags = 0;
      GhosttyColorRgb fg = colors.foreground, bg = colors.background, c;
      if (ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &c) ==
          GHOSTTY_SUCCESS) {
        fg = c;
      }
      if (ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &c) ==
          GHOSTTY_SUCCESS) {
        bg = c;
      }
      if (styled) {
        GhosttyStyle st = GHOSTTY_INIT_SIZED(GhosttyStyle);
        ghostty_render_state_row_cells_get(e->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st);
        if (st.bold) flags |= F_BOLD;
        if (st.italic) flags |= F_ITALIC;
        if (st.underline) flags |= F_UNDERLINE;
        if (st.strikethrough) flags |= F_STRIKE;
        if (st.faint) flags |= F_FAINT;
        if (st.invisible) flags |= F_INVISIBLE;
        if (st.blink) flags |= F_BLINK;
        if (st.inverse) std::swap(fg, bg);
        if (st.faint) fg = mix(fg, bg, 2, 1);
      }
      if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= F_WIDE;
      if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) flags |= F_SPACER;
      if (link) flags |= F_LINK;
      cp[idx] = first;
      fgA[idx] = pack(fg);
      bgA[idx] = pack(bg);
      flA[idx] = flags;
    }
  }
  ghostty_render_state_clean(e->render);

  GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cur);
  hdr[0] = cur.viewport_x;
  hdr[1] = cur.viewport_y;
  hdr[2] = (cur.visible && cur.viewport_has_value) ? 1 : 0;
  switch (cur.visual_style) {
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE: hdr[3] = 1; break;
    case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR: hdr[3] = 2; break;
    default: hdr[3] = 0; break;
  }
  GhosttyTerminalScrollbar sb{};
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) == GHOSTTY_SUCCESS) {
    int64_t above = static_cast<int64_t>(sb.total) - static_cast<int64_t>(sb.len);
    hdr[4] = static_cast<int32_t>(above < 0 ? 0 : above);
    int64_t off = above - static_cast<int64_t>(sb.offset);
    hdr[5] = static_cast<int32_t>(off < 0 ? 0 : off);
  } else {
    hdr[4] = 0;
    hdr[5] = 0;
  }
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  hdr[6] = static_cast<int32_t>(e->graphemes.size());
  hdr[7] = e->cols;
  hdr[8] = e->row_count;
  hdr[9] = screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE ? 1 : 0;
  hdr[10] = any ? 1 : 0;
  hdr[11] = e->title_gen;

  env->ReleasePrimitiveArrayCritical(jhdr, hdr, 0);
  env->ReleasePrimitiveArrayCritical(jdirty, dirtyA, 0);
  env->ReleasePrimitiveArrayCritical(jflags, flA, 0);
  env->ReleasePrimitiveArrayCritical(jbg, bgA, 0);
  env->ReleasePrimitiveArrayCritical(jfg, fgA, 0);
  env->ReleasePrimitiveArrayCritical(jcp, cp, 0);
}

void nFetchGraphemes(JNIEnv* env, jclass, jlong h, jintArray out) {
  Engine* e = H(h);
  jsize cap = env->GetArrayLength(out);
  jsize n = static_cast<jsize>(std::min<size_t>(e->graphemes.size(), static_cast<size_t>(cap)));
  env->SetIntArrayRegion(out, 0, n, e->graphemes.data());
}

void nScroll(JNIEnv*, jclass, jlong h, jint delta) {
  GhosttyTerminalScrollViewport b{};
  b.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
  b.value.delta = delta;
  ghostty_terminal_scroll_viewport(H(h)->terminal, b);
}

void nScrollBottom(JNIEnv*, jclass, jlong h) {
  GhosttyTerminalScrollViewport b{};
  b.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
  ghostty_terminal_scroll_viewport(H(h)->terminal, b);
}

jbyteArray to_java(JNIEnv* env, const uint8_t* p, size_t n) {
  jbyteArray r = env->NewByteArray(static_cast<jsize>(n));
  if (n) env->SetByteArrayRegion(r, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(p));
  return r;
}

GhosttyKey key_for(int key, int32_t first_cp, uint32_t* unshifted) {
  // Ordinals follow the Kotlin `Key` enum.
  static const GhosttyKey kFixed[] = {
      GHOSTTY_KEY_UNIDENTIFIED, GHOSTTY_KEY_ENTER, GHOSTTY_KEY_TAB, GHOSTTY_KEY_BACKSPACE, GHOSTTY_KEY_ESCAPE,
      GHOSTTY_KEY_SPACE, GHOSTTY_KEY_ARROW_UP, GHOSTTY_KEY_ARROW_DOWN, GHOSTTY_KEY_ARROW_LEFT,
      GHOSTTY_KEY_ARROW_RIGHT, GHOSTTY_KEY_HOME, GHOSTTY_KEY_END, GHOSTTY_KEY_PAGE_UP, GHOSTTY_KEY_PAGE_DOWN,
      GHOSTTY_KEY_INSERT, GHOSTTY_KEY_DELETE, GHOSTTY_KEY_F1, GHOSTTY_KEY_F2, GHOSTTY_KEY_F3, GHOSTTY_KEY_F4,
      GHOSTTY_KEY_F5, GHOSTTY_KEY_F6, GHOSTTY_KEY_F7, GHOSTTY_KEY_F8, GHOSTTY_KEY_F9, GHOSTTY_KEY_F10,
      GHOSTTY_KEY_F11, GHOSTTY_KEY_F12};
  if (key > 0 && key < static_cast<int>(sizeof(kFixed) / sizeof(kFixed[0]))) return kFixed[key];
  // Key.Character (0): derive the physical key from the produced character (US layout).
  uint32_t c = static_cast<uint32_t>(first_cp);
  *unshifted = c;
  if (c >= 'A' && c <= 'Z') { *unshifted = c + 32; return static_cast<GhosttyKey>(GHOSTTY_KEY_A + (c - 'A')); }
  if (c >= 'a' && c <= 'z') return static_cast<GhosttyKey>(GHOSTTY_KEY_A + (c - 'a'));
  if (c >= '0' && c <= '9') return static_cast<GhosttyKey>(GHOSTTY_KEY_DIGIT_0 + (c - '0'));
  switch (c) {
    case '-': return GHOSTTY_KEY_MINUS;
    case '=': return GHOSTTY_KEY_EQUAL;
    case '[': return GHOSTTY_KEY_BRACKET_LEFT;
    case ']': return GHOSTTY_KEY_BRACKET_RIGHT;
    case '\\': return GHOSTTY_KEY_BACKSLASH;
    case ';': return GHOSTTY_KEY_SEMICOLON;
    case '\'': return GHOSTTY_KEY_QUOTE;
    case '`': return GHOSTTY_KEY_BACKQUOTE;
    case ',': return GHOSTTY_KEY_COMMA;
    case '.': return GHOSTTY_KEY_PERIOD;
    case '/': return GHOSTTY_KEY_SLASH;
    default: return GHOSTTY_KEY_UNIDENTIFIED;
  }
}

// mods bits: 1 shift, 2 ctrl, 4 alt, 8 meta(super).
jbyteArray nEncodeKey(JNIEnv* env, jclass, jlong h, jint key, jint mods, jbyteArray jtext, jint textLen) {
  Engine* e = H(h);
  uint8_t text[16];
  size_t tl = 0;
  if (jtext && textLen > 0) {
    tl = std::min<size_t>(static_cast<size_t>(textLen), sizeof(text));
    env->GetByteArrayRegion(jtext, 0, static_cast<jsize>(tl), reinterpret_cast<jbyte*>(text));
  }
  // First code point (ASCII fast path is enough for physical-key derivation).
  int32_t first = tl ? text[0] : 0;
  uint32_t unshifted = 0;
  GhosttyKey gk = key_for(key, first, &unshifted);
  GhosttyMods gm = 0;
  if (mods & 1) gm |= GHOSTTY_MODS_SHIFT;
  if (mods & 2) gm |= GHOSTTY_MODS_CTRL;
  if (mods & 4) gm |= GHOSTTY_MODS_ALT;
  if (mods & 8) gm |= GHOSTTY_MODS_SUPER;
  // Honors DECCKM, keypad, kitty flags, modifyOtherKeys of the live terminal. Mode 9001 (win32-input)
  // is unknown to libghostty and therefore ignored.
  ghostty_key_encoder_setopt_from_terminal(e->key_encoder, e->terminal);
  ghostty_key_event_set_action(e->key_event, GHOSTTY_KEY_ACTION_PRESS);
  ghostty_key_event_set_key(e->key_event, gk);
  ghostty_key_event_set_mods(e->key_event, gm);
  ghostty_key_event_set_consumed_mods(e->key_event, (mods & 1) && tl ? GHOSTTY_MODS_SHIFT : 0);
  ghostty_key_event_set_composing(e->key_event, false);
  ghostty_key_event_set_unshifted_codepoint(e->key_event, unshifted);
  bool control_text = tl == 1 && (text[0] < 0x20 || text[0] == 0x7f);
  if (tl && !control_text && key == 0) {
    ghostty_key_event_set_utf8(e->key_event, reinterpret_cast<const char*>(text), tl);
  } else {
    ghostty_key_event_set_utf8(e->key_event, nullptr, 0);
  }
  char buf[128];
  size_t written = 0;
  GhosttyResult r = ghostty_key_encoder_encode(e->key_encoder, e->key_event, buf, sizeof(buf), &written);
  if (r != GHOSTTY_SUCCESS) written = 0;
  return to_java(env, reinterpret_cast<uint8_t*>(buf), written);
}

jbyteArray nEncodePaste(JNIEnv* env, jclass, jlong h, jbyteArray jtext) {
  Engine* e = H(h);
  jsize n = env->GetArrayLength(jtext);
  std::vector<char> data(static_cast<size_t>(n));
  if (n) env->GetByteArrayRegion(jtext, 0, n, reinterpret_cast<jbyte*>(data.data()));
  GhosttyTerminalModeConfig m{GHOSTTY_MODE_BRACKETED_PASTE, false};
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_MODE, &m);
  std::vector<char> out(static_cast<size_t>(n) + 32);
  size_t written = 0;
  GhosttyResult r = ghostty_paste_encode(data.data(), data.size(), m.value, out.data(), out.size(), &written);
  if (r == GHOSTTY_OUT_OF_SPACE) {
    out.resize(written);
    r = ghostty_paste_encode(data.data(), data.size(), m.value, out.data(), out.size(), &written);
  }
  if (r != GHOSTTY_SUCCESS) written = 0;
  return to_java(env, reinterpret_cast<uint8_t*>(out.data()), written);
}

// Event stream: repeated [type u8][strings...], each string = u32 LE length + UTF-8 bytes
// (title/clipboard/pwd: 1 string, notification: 2, bell: 0). Returns null when empty.
jbyteArray nDrainEvents(JNIEnv* env, jclass, jlong h) {
  Engine* e = H(h);
  if (e->events.empty()) return nullptr;
  jbyteArray r = to_java(env, e->events.data(), e->events.size());
  e->events.clear();
  return r;
}

jbyteArray nTitle(JNIEnv* env, jclass, jlong h) {
  GhosttyString s{};
  if (ghostty_terminal_get(H(h)->terminal, GHOSTTY_TERMINAL_DATA_TITLE, &s) != GHOSTTY_SUCCESS) return to_java(env, nullptr, 0);
  return to_java(env, s.ptr, s.len);
}

jbyteArray nText(JNIEnv* env, jclass, jlong h, jint history) {
  Engine* e = H(h);
  GhosttyTerminalScrollbar sb{};
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS) return to_java(env, nullptr, 0);
  uint64_t top = sb.offset > static_cast<uint64_t>(history) ? sb.offset - static_cast<uint64_t>(history) : 0;
  GhosttyPoint p0 = {GHOSTTY_POINT_TAG_SCREEN, {}};
  p0.value.coordinate = {0, static_cast<uint32_t>(top)};
  GhosttyPoint p1 = {GHOSTTY_POINT_TAG_VIEWPORT, {}};
  p1.value.coordinate = {static_cast<uint16_t>(e->cols - 1), static_cast<uint32_t>(e->row_count - 1)};
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  sel.start = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  sel.end = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  if (ghostty_terminal_grid_ref(e->terminal, p0, &sel.start) != GHOSTTY_SUCCESS ||
      ghostty_terminal_grid_ref(e->terminal, p1, &sel.end) != GHOSTTY_SUCCESS) {
    return to_java(env, nullptr, 0);
  }
  sel.rectangle = false;
  GhosttyFormatterTerminalOptions opts = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.unwrap = true;
  opts.trim = true;
  opts.extra = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalExtra);
  opts.extra.screen = GHOSTTY_INIT_SIZED(GhosttyFormatterScreenExtra);
  opts.selection = &sel;
  GhosttyFormatter f = nullptr;
  if (ghostty_formatter_terminal_new(nullptr, &f, e->terminal, opts) != GHOSTTY_SUCCESS) return to_java(env, nullptr, 0);
  uint8_t* ptr = nullptr;
  size_t len = 0;
  jbyteArray r;
  if (ghostty_formatter_format_alloc(f, nullptr, &ptr, &len) == GHOSTTY_SUCCESS) {
    r = to_java(env, ptr, len);
    ghostty_free(nullptr, ptr, len);
  } else {
    r = to_java(env, nullptr, 0);
  }
  ghostty_formatter_free(f);
  return r;
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(III)J", reinterpret_cast<void*>(nCreate)},
    {"nativeFree", "(J)V", reinterpret_cast<void*>(nFree)},
    {"nativeFeed", "(J[BII)V", reinterpret_cast<void*>(nFeed)},
    {"nativeResize", "(JII)V", reinterpret_cast<void*>(nResize)},
    {"nativeSetTheme", "(JIII[I)V", reinterpret_cast<void*>(nSetTheme)},
    {"nativeSnapshot", "(JZII[I[I[I[I[I[I)V", reinterpret_cast<void*>(nSnapshot)},
    {"nativeFetchGraphemes", "(J[I)V", reinterpret_cast<void*>(nFetchGraphemes)},
    {"nativeScroll", "(JI)V", reinterpret_cast<void*>(nScroll)},
    {"nativeScrollToBottom", "(J)V", reinterpret_cast<void*>(nScrollBottom)},
    {"nativeEncodeKey", "(JII[BI)[B", reinterpret_cast<void*>(nEncodeKey)},
    {"nativeEncodePaste", "(J[B)[B", reinterpret_cast<void*>(nEncodePaste)},
    {"nativeDrainEvents", "(J)[B", reinterpret_cast<void*>(nDrainEvents)},
    {"nativeTitle", "(J)[B", reinterpret_cast<void*>(nTitle)},
    {"nativeText", "(JI)[B", reinterpret_cast<void*>(nText)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass("com/mtajchert/flocket/core/terminal/ghostty/GhosttyNative");
  if (!cls) return JNI_ERR;
  if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
