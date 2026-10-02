// 데스크탑 앱 설정의 테마(Color.kt PhoneLockPalette와 동일 값)를 확장 페이지/오버레이에도 반영한다.
// API_BASE, fetchWithTimeout은 config.js(이 스크립트보다 먼저 로드됨)에 정의되어 있다.
// 사용자 요청(2026-08-14) — 이전엔 확장 전체가 다크+블루 고정이었다(35차 결정, 범위 밖으로 제외).
// 86차: 85차에 앱 쪽 테마가 LIGHT_GREEN/DARK_BLUE/LIGHT_ORANGE/CUSTOM 4종으로 정리됐는데(라벤더/민트/
// 로즈/미드나잇/포레스트 6종 삭제) 이 파일엔 그 삭제분이 반영 안 돼 있었다 — 죽은 팔레트 제거하고,
// 79차에 신규였던 CUSTOM 테마(당시부터 이 확장엔 아예 없었음)를 buildCustomPalette(Color.kt)와 동일한
// blend 알고리즘으로 새로 추가.
const THEME_FETCH_TIMEOUT_MS = 1500;

// 147차: 144차 리디자인(Ledger)에서 앱 팔레트가 바뀌었는데 확장은 옛 값 그대로였다 — 데스크탑 ui/theme/Color.kt의
// LightGreen/DarkBlue/LightOrange/Mono 팔레트와 같은 값으로 맞췄다(panel=surface, text=onBackground, border=outline,
// accent=primary, onAccent=onPrimary, warnBg=warningContainer, warnText=warning). MINIMAL은 데스크탑이 미니멀(성능)
// 모드일 때 /theme가 돌려주는 값이다.
const THEME_PALETTES = {
  LIGHT_GREEN: {
    background: "#F4F5EF", panel: "#FFFFFF", text: "#141A10", muted: "#5C6656",
    border: "#DCE0D4", accent: "#36701A", onAccent: "#FFFFFF",
    warnBg: "#FDF0D2", warnText: "#B45309"
  },
  DARK_BLUE: {
    background: "#0B0E14", panel: "#141922", text: "#EEF1F6", muted: "#98A2B3",
    border: "#252C38", accent: "#6AA4FF", onAccent: "#07101F",
    warnBg: "#3A2F14", warnText: "#F5B83D"
  },
  LIGHT_ORANGE: {
    background: "#FAF6F0", panel: "#FFFFFF", text: "#1F160C", muted: "#6E5E4E",
    border: "#E6DCCD", accent: "#A84F00", onAccent: "#FFFFFF",
    warnBg: "#FDF0D2", warnText: "#B45309"
  },
  MINIMAL: {
    background: "#F5F5F5", panel: "#FFFFFF", text: "#111111", muted: "#666666",
    border: "#E2E2E2", accent: "#111111", onAccent: "#FFFFFF",
    warnBg: "#EDEDED", warnText: "#555555"
  }
};

function parseHexColor(hex) {
  const clean = (hex || "").trim().replace(/^#/, "");
  if (clean.length !== 6 || !/^[0-9a-fA-F]{6}$/.test(clean)) return null;
  return {
    r: parseInt(clean.substring(0, 2), 16) / 255,
    g: parseInt(clean.substring(2, 4), 16) / 255,
    b: parseInt(clean.substring(4, 6), 16) / 255
  };
}

function toHex(c) {
  const toByte = (v) => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, "0");
  return `#${toByte(c.r)}${toByte(c.g)}${toByte(c.b)}`.toUpperCase();
}

function blend(a, b, t) {
  return { r: a.r + (b.r - a.r) * t, g: a.g + (b.g - a.g) * t, b: a.b + (b.b - a.b) * t };
}

function luminance(c) {
  return 0.299 * c.r + 0.587 * c.g + 0.114 * c.b;
}

const WHITE = { r: 1, g: 1, b: 1 };
const BLACK = { r: 0, g: 0, b: 0 };

/** Color.kt buildCustomPalette와 동일 알고리즘 — 배경/포인트 두 헥스값만으로 확장이 쓰는 필드
 *  (background/panel/text/muted/border/accent/onAccent/warnBg/warnText)를 계산한다. */
function buildCustomPalette(backgroundHex, accentHex) {
  const background = parseHexColor(backgroundHex) || parseHexColor("#FAFBF6");
  const primary = parseHexColor(accentHex) || parseHexColor("#8BC34A");
  const isDark = luminance(background) < 0.5;

  const onBackground = isDark ? blend(background, WHITE, 0.85) : blend(background, BLACK, 0.85);
  const onPrimary = luminance(primary) < 0.5 ? WHITE : BLACK;
  const surface = isDark ? blend(background, WHITE, 0.10) : WHITE;
  const outline = blend(onBackground, background, 0.85);
  const muted = blend(onBackground, background, 0.5);
  const warning = isDark ? parseHexColor("#FBBF24") : parseHexColor("#F59E0B");
  const warningContainer = isDark ? parseHexColor("#3A331A") : parseHexColor("#FEF3C7");

  return {
    background: toHex(background), panel: toHex(surface), text: toHex(onBackground), muted: toHex(muted),
    border: toHex(outline), accent: toHex(primary), onAccent: toHex(onPrimary),
    warnBg: toHex(warningContainer), warnText: toHex(warning)
  };
}

/** CSS 변수(--bg 등)로 반영 — confirm.html/blocked.html/onboarding.html의 <style>이 이 변수를 참조한다. */
function applyTheme(themeMode, customBackgroundHex, customAccentHex) {
  const palette = themeMode === "CUSTOM"
    ? buildCustomPalette(customBackgroundHex, customAccentHex)
    : (THEME_PALETTES[themeMode] || THEME_PALETTES.DARK_BLUE);
  const root = document.documentElement.style;
  root.setProperty("--bg", palette.background);
  root.setProperty("--panel", palette.panel);
  root.setProperty("--text", palette.text);
  root.setProperty("--muted", palette.muted);
  root.setProperty("--border", palette.border);
  root.setProperty("--accent", palette.accent);
  root.setProperty("--on-accent", palette.onAccent);
  root.setProperty("--warn-bg", palette.warnBg);
  root.setProperty("--warn-text", palette.warnText);
}

/** 데스크탑 앱과 통신이 안 되면(꺼져있음 등) 앱 기본 테마(147차부터 다크 · 블루)로 둔다. */
async function fetchAndApplyTheme() {
  try {
    const res = await fetchWithTimeout(`${API_BASE}/theme`, {}, THEME_FETCH_TIMEOUT_MS);
    const data = await res.json();
    applyTheme(data.themeMode || "DARK_BLUE", data.customThemeBackground, data.customThemeAccent);
  } catch (e) {
    applyTheme("DARK_BLUE");
  }
}

/** overlay.js처럼 CSS 변수가 아니라 색상 값 자체가 필요한 곳(캔버스 없는 inline style 오버레이)이 쓴다. */
function getThemePalette(themeMode, customBackgroundHex, customAccentHex) {
  if (themeMode === "CUSTOM") return buildCustomPalette(customBackgroundHex, customAccentHex);
  return THEME_PALETTES[themeMode] || THEME_PALETTES.DARK_BLUE;
}
