// JNI shim between com.mtajchert.flocket.core.terminal.ghostty.GhosttyNative and libghostty-vt.
// Design: one Engine per terminal; no JNI calls from inside libghostty callbacks; bulk data moves in
// primitive arrays supplied by Kotlin. Not thread-safe: the owner confines an Engine to one thread.
// Documentation: android/docs/TERMINAL_ENGINE.md.
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
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
  GhosttyMouseEncoder mouse_encoder = nullptr;
  GhosttyMouseEvent mouse_event = nullptr;
  int cols = 0;
  int row_count = 0;
  std::vector<int32_t> graphemes;  // [cellIndex, n, cp0..cpN-1]*
  std::vector<uint8_t> events;     // see drainEvents
  bool theme_dirty = false;
  int32_t title_gen = 0;
  // historyBase (nHistoryBase): a tracked ref on the primary screen's last row and its screen y.
  GhosttyTrackedGridRef pin = nullptr;
  uint32_t pin_y = 0;
  int64_t trimmed = 0;
  // nReadRow scratch, reused between calls.
  std::vector<int32_t> row_cp, row_fg, row_bg, row_fl, row_g;
  std::vector<uint32_t> row_buf = std::vector<uint32_t>(16);
};

// What a vertical swipe scrolls (Kotlin ScrollInput ordinal), like a desktop terminal's wheel:
// 0 scrollback, 1 mouse wheel events (the program tracks the mouse, any screen), 2 Up/Down keys
// (alternate screen with alternate scroll mode ?1007, on by default), 3 nothing (?1007 off).
int32_t scroll_input(Engine* e) {
  bool tracking = false;
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &tracking);
  if (tracking) return 1;
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  if (screen != GHOSTTY_TERMINAL_SCREEN_ALTERNATE) return 0;
  GhosttyTerminalModeConfig m{GHOSTTY_MODE_ALT_SCROLL, false};
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_MODE, &m);
  return m.value ? 2 : 3;
}

// Upper bound for undrained events: the remote program controls titles, OSC 52 payloads and
// notifications, so an owner that drains rarely must not let it grow native memory without limit.
// Events that would exceed the cap are dropped.
constexpr size_t kMaxEventBytes = 8u << 20;
// Scrollback byte cap per terminal. libghostty applies a small default that cut history to ~1400 rows at
// 52 columns. 16 MB already holds the 10,000-line default of plain text; 32 MB leaves room for styled
// output while bounding memory when several terminals are open.
constexpr size_t kScrollbackMaxBytes = size_t{32} << 20;

void put_u32(std::vector<uint8_t>& out, uint32_t v) {
  for (int i = 0; i < 4; i++) out.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

void put_str(std::vector<uint8_t>& out, const uint8_t* p, size_t n) {
  put_u32(out, static_cast<uint32_t>(n));
  if (n) out.insert(out.end(), p, p + n);
}

// Starts an event whose strings total `payload` bytes (lengths excluded). False = dropped (cap).
bool begin_event(Engine* e, EventType t, size_t payload = 0, size_t strings = 0) {
  size_t need = 1 + payload + 4 * strings;
  if (payload > kMaxEventBytes || e->events.size() + need > kMaxEventBytes) return false;
  e->events.push_back(t);
  return true;
}

void throw_java(JNIEnv* env, const char* cls, const char* msg) {
  if (env->ExceptionCheck()) return;
  jclass c = env->FindClass(cls);
  if (c) {
    env->ThrowNew(c, msg);
    env->DeleteLocalRef(c);
  }
}

// Holds a primitive array critical region; released on every path by the destructor.
class CriticalInts {
 public:
  CriticalInts(JNIEnv* env, jintArray a)
      : env_(env), a_(a), p_(static_cast<int32_t*>(env->GetPrimitiveArrayCritical(a, nullptr))) {}
  ~CriticalInts() {
    if (p_) env_->ReleasePrimitiveArrayCritical(a_, p_, 0);
  }
  CriticalInts(const CriticalInts&) = delete;
  CriticalInts& operator=(const CriticalInts&) = delete;
  int32_t* get() const { return p_; }

 private:
  JNIEnv* env_;
  jintArray a_;
  int32_t* p_;
};

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
  if (begin_event(e, kTitle, s.len, 1)) put_str(e->events, s.ptr, s.len);
}

void on_pwd(GhosttyTerminal t, void* ud) {
  auto* e = static_cast<Engine*>(ud);
  GhosttyString s{};
  if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_PWD, &s) != GHOSTTY_SUCCESS) return;
  if (begin_event(e, kPwd, s.len, 1)) put_str(e->events, s.ptr, s.len);
}

void on_clipboard_write(GhosttyTerminal, void* ud, const GhosttyClipboardWrite* w) {
  auto* e = static_cast<Engine*>(ud);
  const GhosttyClipboardContent* pick = nullptr;
  for (size_t i = 0; i < w->contents_len; i++) {
    const auto& c = w->contents[i];
    if (c.mime.len >= 10 && memcmp(c.mime.ptr, "text/plain", 10) == 0) { pick = &c; break; }
    if (!pick) pick = &c;
  }
  if (pick && begin_event(e, kClipboard, pick->data.len, 1)) put_str(e->events, pick->data.ptr, pick->data.len);
  // Acknowledge; whether the UI honors the write is decided above this layer.
  GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
  reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  reply.remember = false;
  if (w->reply) w->reply(w, &reply);
}

void on_notification(GhosttyTerminal, void* ud, const GhosttyTerminalDesktopNotification* n) {
  auto* e = static_cast<Engine*>(ud);
  if (n->title.len > kMaxEventBytes || n->body.len > kMaxEventBytes) return;
  if (!begin_event(e, kNotification, n->title.len + n->body.len, 2)) return;
  put_str(e->events, n->title.ptr, n->title.len);
  put_str(e->events, n->body.ptr, n->body.len);
}

Engine* H(jlong h) { return reinterpret_cast<Engine*>(h); }

// libghostty sizes are uint16; Kotlin arrays hold cols*rows ints.
bool valid_size(jint cols, jint rows) {
  return cols > 0 && rows > 0 && cols <= 0xFFFF && rows <= 0xFFFF &&
         static_cast<int64_t>(cols) * rows <= INT32_MAX;
}

jlong nCreate(JNIEnv*, jclass, jint cols, jint rows, jint scrollbackLines) {
  if (!valid_size(cols, rows) || scrollbackLines < 0) return 0;
  auto* e = new Engine();
  e->cols = cols;
  e->row_count = rows;
  if (ghostty_terminal_new(nullptr, &e->terminal, static_cast<uint16_t>(cols), static_cast<uint16_t>(rows)) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_new(nullptr, &e->render) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(nullptr, &e->rows) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(nullptr, &e->cells) != GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(nullptr, &e->key_encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(nullptr, &e->key_event) != GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_new(nullptr, &e->mouse_encoder) != GHOSTTY_SUCCESS ||
      ghostty_mouse_event_new(nullptr, &e->mouse_event) != GHOSTTY_SUCCESS) {
    ghostty_mouse_event_free(e->mouse_event);
    ghostty_mouse_encoder_free(e->mouse_encoder);
    ghostty_key_event_free(e->key_event);
    ghostty_key_encoder_free(e->key_encoder);
    ghostty_render_state_row_cells_free(e->cells);
    ghostty_render_state_row_iterator_free(e->rows);
    ghostty_render_state_free(e->render);
    ghostty_terminal_free(e->terminal);
    delete e;
    return 0;
  }
  // Grapheme clustering (mode 2027) on by default, like the Ghostty app. MODE_DEFAULT also sets the
  // value that RIS/DECSTR restore, so a reset does not turn it off. Programs may still toggle it.
  GhosttyTerminalModeConfig grapheme_mode{GHOSTTY_MODE_GRAPHEME_CLUSTER, true};
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &grapheme_mode);
  size_t lines = static_cast<size_t>(scrollbackLines);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
  // libghostty also applies its own default byte cap, which stops history long before the line cap.
  size_t bytes = kScrollbackMaxBytes;
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &bytes);
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
  ghostty_mouse_event_free(e->mouse_event);
  ghostty_mouse_encoder_free(e->mouse_encoder);
  ghostty_key_event_free(e->key_event);
  ghostty_key_encoder_free(e->key_encoder);
  ghostty_render_state_row_cells_free(e->cells);
  ghostty_render_state_row_iterator_free(e->rows);
  ghostty_render_state_free(e->render);
  ghostty_tracked_grid_ref_free(e->pin);
  ghostty_terminal_free(e->terminal);
  delete e;
}

void nFeed(JNIEnv* env, jclass, jlong h, jbyteArray bytes, jint off, jint len) {
  Engine* e = H(h);
  if (len <= 0) return;
  if (off < 0 || static_cast<int64_t>(off) + len > env->GetArrayLength(bytes)) {
    throw_java(env, "java/lang/IndexOutOfBoundsException", "feed range out of bounds");
    return;
  }
  // Critical region: vt_write and its callbacks never call back into JNI.
  void* p = env->GetPrimitiveArrayCritical(bytes, nullptr);
  if (!p) return;  // OutOfMemoryError pending
  ghostty_terminal_vt_write(e->terminal, static_cast<const uint8_t*>(p) + off, static_cast<size_t>(len));
  env->ReleasePrimitiveArrayCritical(bytes, p, JNI_ABORT);
}

void nResize(JNIEnv* env, jclass, jlong h, jint cols, jint rows) {
  Engine* e = H(h);
  if (!valid_size(cols, rows)) {
    throw_java(env, "java/lang/IllegalArgumentException", "terminal size out of range");
    return;
  }
  if (ghostty_terminal_resize(e->terminal, static_cast<uint16_t>(cols), static_cast<uint16_t>(rows), 8, 16) !=
      GHOSTTY_SUCCESS) {
    throw_java(env, "java/lang/IllegalStateException", "libghostty-vt resize failed");
    return;
  }
  e->cols = cols;
  e->row_count = rows;
}

GhosttyColorRgb rgb_of(jint argb) {
  return GhosttyColorRgb{static_cast<uint8_t>(argb >> 16), static_cast<uint8_t>(argb >> 8),
                         static_cast<uint8_t>(argb)};
}

void nSetTheme(JNIEnv* env, jclass, jlong h, jint fg, jint bg, jint cursor, jintArray palette16) {
  Engine* e = H(h);
  jint p16[16];
  if (env->GetArrayLength(palette16) < 16) {
    throw_java(env, "java/lang/IllegalArgumentException", "palette must have 16 entries");
    return;
  }
  env->GetIntArrayRegion(palette16, 0, 16, p16);
  if (env->ExceptionCheck()) return;
  // Theme changes recolor every cell without marking rows dirty.
  e->theme_dirty = true;
  GhosttyColorRgb c = rgb_of(fg);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &c);
  c = rgb_of(bg);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &c);
  c = rgb_of(cursor);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c);
  GhosttyColorRgb pal[256];
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, pal) != GHOSTTY_SUCCESS) return;
  for (int i = 0; i < 16; i++) pal[i] = rgb_of(p16[i]);
  ghostty_terminal_set(e->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
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
  // Validate every array before entering the critical regions: indices below are y*cols+col with
  // y < rows, col < cols, so cols*rows elements per cell array and rows for `dirty` suffice.
  const int64_t cells = static_cast<int64_t>(cols) * rows;
  if (!valid_size(cols, rows) || env->GetArrayLength(jcp) < cells || env->GetArrayLength(jfg) < cells ||
      env->GetArrayLength(jbg) < cells || env->GetArrayLength(jflags) < cells ||
      env->GetArrayLength(jdirty) < rows || env->GetArrayLength(jhdr) < 12) {
    throw_java(env, "java/lang/IllegalArgumentException", "snapshot arrays smaller than cols x rows");
    return;
  }
  if (ghostty_render_state_update(e->render, e->terminal) != GHOSTTY_SUCCESS) {
    throw_java(env, "java/lang/IllegalStateException", "libghostty-vt render state update failed");
    return;
  }
  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  bool all = full || e->theme_dirty || dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;
  e->theme_dirty = false;

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(e->render, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  // No JNI calls from here until the guards go out of scope (they release on every return path).
  CriticalInts gcp(env, jcp), gfg(env, jfg), gbg(env, jbg), gfl(env, jflags), gdirty(env, jdirty), ghdr(env, jhdr);
  int32_t* cp = gcp.get();
  int32_t* fgA = gfg.get();
  int32_t* bgA = gbg.get();
  int32_t* flA = gfl.get();
  int32_t* dirtyA = gdirty.get();
  int32_t* hdr = ghdr.get();
  if (!cp || !fgA || !bgA || !flA || !dirtyA || !hdr) return;  // OutOfMemoryError pending

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
  // Off-viewport cursor (scrolled back): coordinates are meaningless, keep them in range for indexing.
  const bool in_view = cur.viewport_has_value && cur.viewport_x < cols && cur.viewport_y < rows;
  hdr[0] = in_view ? cur.viewport_x : 0;
  hdr[1] = in_view ? cur.viewport_y : 0;
  hdr[2] = (cur.visible && in_view) ? 1 : 0;
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
  hdr[12] = scroll_input(e);
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

// Returns null with an exception pending on failure (oversized or OOM).
jbyteArray to_java(JNIEnv* env, const uint8_t* p, size_t n) {
  if (n > static_cast<size_t>(INT32_MAX)) {
    throw_java(env, "java/lang/OutOfMemoryError", "native result exceeds 2 GiB");
    return nullptr;
  }
  jbyteArray r = env->NewByteArray(static_cast<jsize>(n));
  if (!r) return nullptr;
  if (n) env->SetByteArrayRegion(r, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(p));
  return r;
}

// First code point of a UTF-8 string (U+FFFD when malformed); only used to derive a physical key.
uint32_t first_code_point(const uint8_t* s, size_t n) {
  if (n == 0) return 0;
  uint8_t b = s[0];
  if (b < 0x80) return b;
  size_t len = (b & 0xE0) == 0xC0 ? 2 : (b & 0xF0) == 0xE0 ? 3 : (b & 0xF8) == 0xF0 ? 4 : 0;
  if (len == 0 || n < len) return 0xFFFD;
  uint32_t cp = b & (0x7F >> len);
  for (size_t i = 1; i < len; i++) {
    if ((s[i] & 0xC0) != 0x80) return 0xFFFD;
    cp = (cp << 6) | (s[i] & 0x3F);
  }
  return cp;
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
  if (key == 5) *unshifted = ' ';  // Key.Space: the encoder needs the codepoint for Ctrl/Alt+Space
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
  // Whole text: truncating would split UTF-8 sequences (e.g. ZWJ emoji from an IME commit).
  std::vector<uint8_t> text;
  if (jtext && textLen > 0) {
    text.resize(static_cast<size_t>(std::min(textLen, env->GetArrayLength(jtext))));
    env->GetByteArrayRegion(jtext, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
    if (env->ExceptionCheck()) return nullptr;
  }
  const size_t tl = text.size();
  int32_t first = static_cast<int32_t>(first_code_point(text.data(), tl));
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
  static const uint8_t kSpace = ' ';
  if (key == 5) {  // Key.Space always carries its text " " (Ghostty derives NUL/ESC-space from it)
    ghostty_key_event_set_utf8(e->key_event, reinterpret_cast<const char*>(&kSpace), 1);
  } else if (tl && !control_text && key == 0) {
    ghostty_key_event_set_utf8(e->key_event, reinterpret_cast<const char*>(text.data()), tl);
  } else {
    ghostty_key_event_set_utf8(e->key_event, nullptr, 0);
  }
  std::vector<char> buf(128 + tl * 2);
  size_t written = 0;
  GhosttyResult r = ghostty_key_encoder_encode(e->key_encoder, e->key_event, buf.data(), buf.size(), &written);
  if (r == GHOSTTY_OUT_OF_SPACE) {  // written = required size
    buf.resize(written);
    r = ghostty_key_encoder_encode(e->key_encoder, e->key_event, buf.data(), buf.size(), &written);
  }
  // The event borrows `text`; do not leave a dangling pointer in it.
  ghostty_key_event_set_utf8(e->key_event, nullptr, 0);
  if (r != GHOSTTY_SUCCESS) written = 0;
  return to_java(env, reinterpret_cast<uint8_t*>(buf.data()), written);
}

jint nScrollInput(JNIEnv*, jclass, jlong h) { return scroll_input(H(h)); }

// |lines| wheel events (button 4 = up when lines < 0, 5 = down) at viewport cell (col, row), in the
// tracking mode and format the program asked for. Empty when it does not track the mouse.
jbyteArray nEncodeWheel(JNIEnv* env, jclass, jlong h, jint lines, jint col, jint row) {
  Engine* e = H(h);
  ghostty_mouse_encoder_setopt_from_terminal(e->mouse_encoder, e->terminal);
  // Positions in cell units: a 1x1 px cell makes the surface position the cell coordinate.
  GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
  size.screen_width = static_cast<uint32_t>(e->cols);
  size.screen_height = static_cast<uint32_t>(e->row_count);
  size.cell_width = 1;
  size.cell_height = 1;
  ghostty_mouse_encoder_setopt(e->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
  ghostty_mouse_event_set_action(e->mouse_event, GHOSTTY_MOUSE_ACTION_PRESS);
  ghostty_mouse_event_set_button(e->mouse_event, lines < 0 ? GHOSTTY_MOUSE_BUTTON_FOUR : GHOSTTY_MOUSE_BUTTON_FIVE);
  ghostty_mouse_event_set_mods(e->mouse_event, 0);
  const int c = std::clamp(static_cast<int>(col), 0, std::max(e->cols - 1, 0));
  const int r = std::clamp(static_cast<int>(row), 0, std::max(e->row_count - 1, 0));
  ghostty_mouse_event_set_position(e->mouse_event, GhosttyMousePosition{c + 0.5f, r + 0.5f});
  char one[64];
  size_t written = 0;
  if (ghostty_mouse_encoder_encode(e->mouse_encoder, e->mouse_event, one, sizeof(one), &written) != GHOSTTY_SUCCESS) {
    written = 0;
  }
  const int n = written ? std::min(std::abs(static_cast<int>(lines)), 1000) : 0;
  std::vector<uint8_t> out;
  out.reserve(written * static_cast<size_t>(n));
  for (int i = 0; i < n; i++) out.insert(out.end(), one, one + written);
  return to_java(env, out.data(), out.size());
}

jbyteArray nEncodePaste(JNIEnv* env, jclass, jlong h, jbyteArray jtext) {
  Engine* e = H(h);
  jsize n = env->GetArrayLength(jtext);
  std::vector<char> data(static_cast<size_t>(n));
  if (n) env->GetByteArrayRegion(jtext, 0, n, reinterpret_cast<jbyte*>(data.data()));
  if (env->ExceptionCheck()) return nullptr;
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
  if (r) e->events.clear();  // on OOM keep them for the next drain
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

// ---- Smooth scrolling and selection: TerminalEngine.readRow / textRange, ScreenSnapshot.historyBase.
// Read-only grid-ref and formatter lookups: they never move the viewport or touch the render state.

bool screen_ref(Engine* e, int col, int row, GhosttyGridRef* out) {
  GhosttyPoint p = {GHOSTTY_POINT_TAG_SCREEN, {}};
  p.value.coordinate = {static_cast<uint16_t>(col), static_cast<uint32_t>(row)};
  *out = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  return ghostty_terminal_grid_ref(e->terminal, p, out) == GHOSTTY_SUCCESS;
}

GhosttyColorRgb style_rgb(const GhosttyStyleColor& c, const GhosttyColorRgb* pal, GhosttyColorRgb dflt) {
  if (c.tag == GHOSTTY_STYLE_COLOR_PALETTE) return pal[c.value.palette];
  if (c.tag == GHOSTTY_STYLE_COLOR_RGB) return c.value.rgb;
  return dflt;
}

// Copies screen row `row` (0 = oldest scrollback row) into the first `cols` entries of the arrays,
// resolved like nSnapshot. Multi-code-point graphemes go to `jg` as [col, n, cp0..]*. Returns the
// grapheme ints needed (call again with a bigger `jg` if that exceeds its length) or -1 if the row
// does not exist.
jint nReadRow(JNIEnv* env, jclass, jlong h, jint row, jint cols, jintArray jcp, jintArray jfg, jintArray jbg,
              jintArray jflags, jintArray jg) {
  Engine* e = H(h);
  if (cols != e->cols || env->GetArrayLength(jcp) < cols || env->GetArrayLength(jfg) < cols ||
      env->GetArrayLength(jbg) < cols || env->GetArrayLength(jflags) < cols) {
    throw_java(env, "java/lang/IllegalArgumentException", "row arrays smaller than cols");
    return -1;
  }
  // One (possibly page-list-walking) SCREEN lookup for the row; columns reuse its node and y.
  GhosttyGridRef ref;
  if (row < 0 || !screen_ref(e, 0, row, &ref)) return -1;
  GhosttyColorRgb pal[256];
  GhosttyColorRgb dfg{}, dbg{};
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, pal);
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, &dfg);
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, &dbg);
  std::vector<int32_t>&cp = e->row_cp, &fgA = e->row_fg, &bgA = e->row_bg, &flA = e->row_fl, &g = e->row_g;
  std::vector<uint32_t>& buf = e->row_buf;
  cp.assign(cols, 0);
  fgA.assign(cols, pack(dfg));
  bgA.assign(cols, pack(dbg));
  flA.assign(cols, 0);
  g.clear();
  for (int col = 0; col < cols; col++) {
    ref.x = static_cast<uint16_t>(col);
    size_t n = 0;
    GhosttyResult r = ghostty_grid_ref_graphemes(&ref, buf.data(), buf.size(), &n);
    if (r == GHOSTTY_OUT_OF_SPACE) {
      buf.resize(n);
      r = ghostty_grid_ref_graphemes(&ref, buf.data(), buf.size(), &n);
    }
    if (r != GHOSTTY_SUCCESS) n = 0;
    if (n > 0) cp[col] = static_cast<int32_t>(buf[0]);
    if (n > 1) {
      g.push_back(col);
      g.push_back(static_cast<int32_t>(n));
      for (size_t i = 0; i < n; i++) g.push_back(static_cast<int32_t>(buf[i]));
    }
    GhosttyCell cell = 0;
    if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) continue;
    GhosttyStyle st = GHOSTTY_INIT_SIZED(GhosttyStyle);
    ghostty_grid_ref_style(&ref, &st);
    GhosttyColorRgb fg = style_rgb(st.fg_color, pal, dfg), bg = style_rgb(st.bg_color, pal, dbg);
    GhosttyCellContentTag tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag);
    if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
      GhosttyColorPaletteIndex i = 0;
      if (ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &i) == GHOSTTY_SUCCESS) bg = pal[i];
    } else if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) {
      ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &bg);
    }
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
    bool link = false;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &link);
    int32_t flags = 0;
    if (st.bold) flags |= F_BOLD;
    if (st.italic) flags |= F_ITALIC;
    if (st.underline) flags |= F_UNDERLINE;
    if (st.strikethrough) flags |= F_STRIKE;
    if (st.faint) flags |= F_FAINT;
    if (st.invisible) flags |= F_INVISIBLE;
    if (st.blink) flags |= F_BLINK;
    if (st.inverse) std::swap(fg, bg);
    if (st.faint) fg = mix(fg, bg, 2, 1);
    if (wide == GHOSTTY_CELL_WIDE_WIDE) flags |= F_WIDE;
    if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) flags |= F_SPACER;
    if (link) flags |= F_LINK;
    fgA[col] = pack(fg);
    bgA[col] = pack(bg);
    flA[col] = flags;
  }
  env->SetIntArrayRegion(jcp, 0, cols, cp.data());
  env->SetIntArrayRegion(jfg, 0, cols, fgA.data());
  env->SetIntArrayRegion(jbg, 0, cols, bgA.data());
  env->SetIntArrayRegion(jflags, 0, cols, flA.data());
  if (!g.empty() && g.size() <= static_cast<size_t>(env->GetArrayLength(jg))) {
    env->SetIntArrayRegion(jg, 0, static_cast<jsize>(g.size()), g.data());
  }
  return static_cast<jint>(g.size());
}

// Rows that left the top of the primary screen since creation. A tracked ref (pin) is put on the
// newest row (last active row) at every call; rows removed above it (eviction, clear scrollback)
// lower its screen y. While the pin is still in the active area its screen y is scrollback rows +
// active y (cheap: no page-list walk); only after a screenful of output since the last call is it
// resolved in screen coordinates. Ghostty prunes whole pages (a page holds thousands of narrow
// rows), so a burst can remove the pin itself: because it was the newest row, every row that
// existed at the last call is then gone, and skipping past all of them keeps ids unique (rows
// pruned unseen are simply never numbered). The alternate screen never touches the primary.
jlong nHistoryBase(JNIEnv*, jclass, jlong h) {
  Engine* e = H(h);
  GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
  ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
  GhosttyTerminalScrollbar sb{};
  if (screen != GHOSTTY_TERMINAL_SCREEN_PRIMARY ||
      ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS || sb.total == 0 ||
      sb.len == 0) {
    return static_cast<jlong>(e->trimmed);
  }
  const uint64_t above = sb.total > sb.len ? sb.total - sb.len : 0;
  if (e->pin) {
    GhosttyPointCoordinate c{};
    uint64_t y = 0;
    bool found = true;
    if (ghostty_tracked_grid_ref_point(e->pin, GHOSTTY_POINT_TAG_ACTIVE, &c) == GHOSTTY_SUCCESS) {
      y = above + c.y;
    } else if (ghostty_tracked_grid_ref_point(e->pin, GHOSTTY_POINT_TAG_SCREEN, &c) == GHOSTTY_SUCCESS) {
      y = c.y;
    } else {
      found = false;
    }
    if (found) {
      if (y < e->pin_y) e->trimmed += static_cast<int64_t>(e->pin_y - y);
      e->pin_y = static_cast<uint32_t>(y);
    } else {
      e->trimmed += static_cast<int64_t>(e->pin_y) + 1;
      ghostty_tracked_grid_ref_free(e->pin);
      e->pin = nullptr;
    }
  }
  if (!e->pin || e->pin_y != sb.total - 1) {
    GhosttyPoint p = {GHOSTTY_POINT_TAG_ACTIVE, {}};
    p.value.coordinate = {0, static_cast<uint32_t>(sb.len - 1)};
    bool ok = e->pin ? ghostty_tracked_grid_ref_set(e->pin, e->terminal, p) == GHOSTTY_SUCCESS
                     : ghostty_terminal_grid_ref_track(e->terminal, p, &e->pin) == GHOSTTY_SUCCESS;
    if (ok) e->pin_y = static_cast<uint32_t>(sb.total - 1);
  }
  return static_cast<jlong>(e->trimmed);
}

// Absolute scroll: `row` (screen coordinates) becomes the viewport's top row; at or past the top of
// the active area means the bottom, which follows output.
void nScrollToRow(JNIEnv*, jclass, jlong h, jint row) {
  Engine* e = H(h);
  GhosttyTerminalScrollbar sb{};
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS) return;
  const uint64_t above = sb.total > sb.len ? sb.total - sb.len : 0;
  GhosttyTerminalScrollViewport b{};
  if (row < 0 || static_cast<uint64_t>(row) >= above) {
    b.tag = row < 0 ? GHOSTTY_SCROLL_VIEWPORT_ROW : GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
    b.value.row = 0;
  } else {
    b.tag = GHOSTTY_SCROLL_VIEWPORT_ROW;
    b.value.row = static_cast<size_t>(row);
  }
  ghostty_terminal_scroll_viewport(e->terminal, b);
}

// Plain text between two screen cells (inclusive), unwrapped and trimmed like nText.
jbyteArray nTextRange(JNIEnv* env, jclass, jlong h, jint r0, jint c0, jint r1, jint c1) {
  Engine* e = H(h);
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  // Clamp the end to the last row of the active screen (a stale or alt-screen caller may overshoot).
  GhosttyTerminalScrollbar sb{};
  if (ghostty_terminal_get(e->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS || sb.total == 0) {
    return to_java(env, nullptr, 0);
  }
  const int64_t last = static_cast<int64_t>(sb.total) - 1;
  if (r1 > last) {
    r1 = static_cast<jint>(last);
    c1 = e->cols - 1;
  }
  if (r0 < 0 || r1 < r0 || !screen_ref(e, std::clamp(c0, 0, e->cols - 1), r0, &sel.start) ||
      !screen_ref(e, std::clamp(c1, 0, e->cols - 1), r1, &sel.end)) {
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
    {"nativeScrollInput", "(J)I", reinterpret_cast<void*>(nScrollInput)},
    {"nativeEncodeWheel", "(JIII)[B", reinterpret_cast<void*>(nEncodeWheel)},
    {"nativeDrainEvents", "(J)[B", reinterpret_cast<void*>(nDrainEvents)},
    {"nativeTitle", "(J)[B", reinterpret_cast<void*>(nTitle)},
    {"nativeText", "(JI)[B", reinterpret_cast<void*>(nText)},
    {"nativeReadRow", "(JII[I[I[I[I[I)I", reinterpret_cast<void*>(nReadRow)},
    {"nativeHistoryBase", "(J)J", reinterpret_cast<void*>(nHistoryBase)},
    {"nativeTextRange", "(JIIII)[B", reinterpret_cast<void*>(nTextRange)},
    {"nativeScrollToRow", "(JI)V", reinterpret_cast<void*>(nScrollToRow)},
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
