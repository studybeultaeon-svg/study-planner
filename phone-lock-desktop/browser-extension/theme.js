// 데스크탑 앱 설정의 테마 3종(Color.kt PhoneLockPalette와 동일 값)을 확장 페이지/오버레이에도
// 반영한다. API_BASE, fetchWithTimeout은 config.js(이 스크립트보다 먼저 로드됨)에 정의되어 있다.
// 사용자 요청(2026-08-14) — 이전엔 확장 전체가 다크+블루 고정이었다(35차 결정, 범위 밖으로 제외).
const THEME_FETCH_TIMEOUT_MS = 1500;

const THEME_PALETTES = {
  LIGHT_GREEN: {
    background: "#FAFBF6", panel: "#FFFFFF", text: "#20261A", muted: "#6B7566",
    border: "#D9E2CB", accent: "#8BC34A", onAccent: "#20261A",
    warnBg: "#FEF3C7", warnText: "#F59E0B"
  },
  DARK_BLUE: {
    background: "#0F1117", panel: "#1E2333", text: "#E5E7EB", muted: "#9CA3AF",
    border: "#2A3142", accent: "#4F8EF7", onAccent: "#0F1117",
    warnBg: "#3A331A", warnText: "#FBBF24"
  },
  LIGHT_ORANGE: {
    background: "#FFF8F0", panel: "#FFFFFF", text: "#2E2114", muted: "#8A7360",
    border: "#E8D5BE", accent: "#FF9800", onAccent: "#2E1E00",
    warnBg: "#FFE0B2", warnText: "#E65100"
  },
  LAVENDER: {
    background: "#F8F6FC", panel: "#FFFFFF", text: "#2A1B47", muted: "#7C6E94",
    border: "#DCD0EF", accent: "#9575CD", onAccent: "#2A1B47",
    warnBg: "#FEF3C7", warnText: "#F59E0B"
  },
  MINT: {
    background: "#F0FBF9", panel: "#FFFFFF", text: "#143330", muted: "#5C7A76",
    border: "#C5E6E0", accent: "#26A69A", onAccent: "#FFFFFF",
    warnBg: "#FEF3C7", warnText: "#F59E0B"
  },
  ROSE: {
    background: "#FFF5F8", panel: "#FFFFFF", text: "#3D0F20", muted: "#8C6270",
    border: "#F5CEDD", accent: "#EC407A", onAccent: "#FFFFFF",
    warnBg: "#FEF3C7", warnText: "#F59E0B"
  },
  MIDNIGHT: {
    background: "#14101F", panel: "#201A32", text: "#E8E1F5", muted: "#A399BD",
    border: "#332A4D", accent: "#B388FF", onAccent: "#14101F",
    warnBg: "#3A331A", warnText: "#FBBF24"
  },
  FOREST: {
    background: "#0F1712", panel: "#1B2620", text: "#E3EFE5", muted: "#90A896",
    border: "#2A3B2F", accent: "#66BB6A", onAccent: "#0F1712",
    warnBg: "#3A331A", warnText: "#FBBF24"
  }
};

/** CSS 변수(--bg 등)로 반영 — confirm.html/blocked.html/onboarding.html의 <style>이 이 변수를 참조한다. */
function applyTheme(themeMode) {
  const palette = THEME_PALETTES[themeMode] || THEME_PALETTES.LIGHT_GREEN;
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

/** 데스크탑 앱과 통신이 안 되면(꺼져있음 등) 기본값(라이트+그린, 앱 기본 테마와 동일) 유지. */
async function fetchAndApplyTheme() {
  try {
    const res = await fetchWithTimeout(`${API_BASE}/theme`, {}, THEME_FETCH_TIMEOUT_MS);
    const data = await res.json();
    applyTheme(data.themeMode || "LIGHT_GREEN");
  } catch (e) {
    applyTheme("LIGHT_GREEN");
  }
}

/** overlay.js처럼 CSS 변수가 아니라 색상 값 자체가 필요한 곳(캔버스 없는 inline style 오버레이)이 쓴다. */
function getThemePalette(themeMode) {
  return THEME_PALETTES[themeMode] || THEME_PALETTES.LIGHT_GREEN;
}
