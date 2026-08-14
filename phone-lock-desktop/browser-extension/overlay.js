// API_BASE, fetchWithTimeout? config.js(manifest.json??content_scripts?먯꽌 ???ㅽ겕由쏀듃蹂대떎 癒쇱?
// 濡쒕뱶?????뺤쓽?섏뼱 ?덈떎.
const OVERLAY_POLL_MS = 2000;
const OVERLAY_FETCH_TIMEOUT_MS = 1500;
// ?덈꺼 0????湲곕낯 ?щ챸????쓣?섎줉 ???щ챸), 理쒕?移섎? ?섏? ?딅뒗?? ?덈꺼???ㅻ? ?뚮쭏???ㅻⅤ?????
// 怨좎젙媛믪씠 ?꾨땲???쒕쾭媛 ?대젮二쇰뒗 levelStepsToMax(洹몃９蹂?"紐?踰덉㎏ ?ы솗??留뚯뿉 理쒓퀬 諛앷린 ?꾨떖")濡쒕???// 留ㅻ쾲 怨꾩궛?쒕떎 ??GroupEditScreen "?ㅽ뻾 ?뺤씤"?먯꽌 洹몃９蹂꾨줈 ?ㅼ젙.
const OVERLAY_BASE_OPACITY = 0.1;
const OVERLAY_MAX_OPACITY = 0.85;
// 戮紐⑤룄濡??댁떇 ?꾩떆 ?댁젣 ?ㅻ쾭?덉씠????湲곕낯 遺덊닾紐낅룄(OVERLAY_BASE_OPACITY)蹂대떎 ?덉뿉 ?꾧쾶 吏꾪븯寃? 怨좎젙媛?
const OVERLAY_POMODORO_OPACITY = 0.28;
const THEME_POLL_TICKS = 15; // OVERLAY_POLL_MS(2초) * 15 = 30초마다 테마 재확인(자주 안 바뀌므로 상태 폴링보다 느슨하게)

let overlayEl = null;
let overlayTimerEl = null;
let overlayRemainingSeconds = 0;
let overlayCountdownInterval = null;
let currentPalette = THEME_PALETTES.LIGHT_GREEN;
let pollTickCount = 0;

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255},${(n >> 8) & 255},${n & 255}`;
}

function formatRemainingTime(totalSeconds) {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

/**
 * ?⑥? ?쒓컙 ??대㉧留??붾㈃ ?뺤쨷?숈뿉 ?ш쾶 ?꾩슫??臾멸뎄 ?놁쓬). pointer-events:none?대씪 ?섏씠吏 ?대┃/
 * ?ㅽ겕濡????ㅼ젣 ?ъ슜?먮뒗 ?꾪? 吏?μ씠 ?녿떎.
 */
function ensureOverlay() {
  if (overlayEl) return;

  const container = document.createElement("div");
  container.id = "phonelock-usage-overlay";
  container.style.cssText = [
    "position:fixed",
    "top:0",
    "left:0",
    "width:100%",
    "height:100%",
    "z-index:2147483647",
    "pointer-events:none",
    "display:flex",
    "align-items:center",
    "justify-content:center",
    `background:rgba(${hexToRgb(currentPalette.background)},${OVERLAY_BASE_OPACITY})`,
    "font-family:sans-serif",
  ].join(";");

  const timer = document.createElement("div");
  timer.style.cssText = `color:rgba(${hexToRgb(currentPalette.accent)},${OVERLAY_BASE_OPACITY});font-size:96px;font-weight:bold;`;

  container.appendChild(timer);
  document.documentElement.appendChild(container);

  overlayEl = container;
  overlayTimerEl = timer;
}

/**
 * ?덈꺼???ㅻ??섎줉(?ы솗?몄쓣 諛섎났?좎닔濡? ?ㅻ쾭?덉씠媛 ?먯젏 遺덊닾紐낇빐吏?꾨줉 諛곌꼍 ?щ챸?꾨? 媛깆떊?쒕떎.
 * ??대㉧ ?レ옄??諛곌꼍怨?媛숈? ?щ챸?꾨줈 洹몃젮??媛숈? opacity 媛믪쓣 ?띿뒪???됱긽?먮룄 ?곸슜) ??대㉧ ?먯껜媛
 * ?ㅻ쾭?덉씠???쇰?濡?蹂댁씠寃??쒕떎 ???덈꺼????쓣 ???レ옄??嫄곗쓽 ??蹂댁씠?ㅺ? ?덈꺼???ㅻ??섎줉 ?쒕졆?댁쭊??
 */
function applyOverlayOpacityForLevel(level, levelStepsToMax) {
  if (!overlayEl) return;
  const opacityPerLevel = (OVERLAY_MAX_OPACITY - OVERLAY_BASE_OPACITY) / Math.max(1, levelStepsToMax || 5);
  const opacity = Math.min(OVERLAY_MAX_OPACITY, OVERLAY_BASE_OPACITY + level * opacityPerLevel);
  overlayEl.style.background = `rgba(${hexToRgb(currentPalette.background)},${opacity})`;
  if (overlayTimerEl) overlayTimerEl.style.color = `rgba(${hexToRgb(currentPalette.accent)},${opacity})`;
}

/** 戮紐⑤룄濡??댁떇 ?꾩떆 ?댁젣 以묒뿏 ?덈꺼怨?臾닿??섍쾶 怨좎젙??湲곕낯蹂대떎 吏꾪븳) 遺덊닾紐낅룄瑜??대떎. */
function applyPomodoroOverlayOpacity() {
  if (!overlayEl) return;
  overlayEl.style.background = `rgba(${hexToRgb(currentPalette.background)},${OVERLAY_POMODORO_OPACITY})`;
  if (overlayTimerEl) overlayTimerEl.style.color = `rgba(${hexToRgb(currentPalette.accent)},${OVERLAY_POMODORO_OPACITY})`;
}

function showOrResyncOverlay(remainingSeconds, level, isPomodoro, levelStepsToMax) {
  ensureOverlay();
  reparentOverlayForFullscreen();
  if (isPomodoro) {
    applyPomodoroOverlayOpacity();
  } else {
    applyOverlayOpacityForLevel(level, levelStepsToMax);
  }
  overlayRemainingSeconds = remainingSeconds;
  overlayTimerEl.textContent = formatRemainingTime(overlayRemainingSeconds);
  if (!overlayCountdownInterval) {
    overlayCountdownInterval = setInterval(() => {
      if (overlayRemainingSeconds > 0) overlayRemainingSeconds -= 1;
      if (overlayTimerEl) overlayTimerEl.textContent = formatRemainingTime(overlayRemainingSeconds);
    }, 1000);
  }
}

/**
 * ?룻뵆由?뒪 ?깆뿉???꾩껜?붾㈃(Fullscreen API)?쇰줈 ?꾪솚?섎㈃, 釉뚮씪?곗????꾩껜?붾㈃ ?붿냼? 洹??먯넀留? * ?붾㈃??洹몃━怨?洹?諛뽰쓽 DOM(?ㅻ쾭?덉씠媛 遺숈뼱?덈뒗 document.documentElement ?ы븿)? ??洹몃┛??
 * 洹몃옒???꾩껜?붾㈃ ?꾪솚 ?뚮쭏???ㅻ쾭?덉씠瑜?吏湲??꾩껜?붾㈃???붿냼 ?덉쑝濡???꺼以???꾩껜?붾㈃???꾨땲硫? * ?ㅼ떆 documentElement濡?.
 */
function reparentOverlayForFullscreen() {
  if (!overlayEl) return;
  const target = document.fullscreenElement || document.webkitFullscreenElement || document.documentElement;
  if (overlayEl.parentElement !== target) {
    target.appendChild(overlayEl);
  }
}

document.addEventListener("fullscreenchange", reparentOverlayForFullscreen);
document.addEventListener("webkitfullscreenchange", reparentOverlayForFullscreen);

function hideOverlay() {
  if (overlayCountdownInterval) {
    clearInterval(overlayCountdownInterval);
    overlayCountdownInterval = null;
  }
  if (overlayEl) {
    overlayEl.remove();
    overlayEl = null;
    overlayTimerEl = null;
  }
}

// 諛곌꼍 ?ㅽ겕由쏀듃???섏씠吏 ?대룞???덉쓣 ?뚮굹 1遺?二쇨린 ?뚮엺?먯꽌留??ы솗?몄쓣 寃?ы빐?? 媛숈? ?섏씠吏??// 怨꾩냽 癒몃Ъ???덉쑝硫??좎삁?쒓컙???앸굹??理쒕? 1遺꾧퉴吏 ?ы솗?몄씠 ??쾶 ?????덉뿀?? ?ㅻ쾭?덉씠??2珥덈쭏??// ?대쭅?섍퀬 ?덉쑝?? ?좎삁?쒓컙??"留??앸궃 ?쒓컙"???ш린??媛먯??댁꽌 1遺꾩쓣 湲곕떎由ъ? ?딄퀬 諛붾줈 ?ы솗?몄쓣
// ?꾩슫??
let wasShowingOverlay = false;

/**
 * 肄섑뀗痢??ㅽ겕由쏀듃???섏씠吏 ?덉뿉???ㅽ뻾?섍린 ?뚮Ц??confirm.html/blocked.html 媛숈? ?뺤옣?꾨줈洹몃옩 ?대?
 * ?섏씠吏濡?吏곸젒 location.href ?대룞???쒗궎硫??щ＼??web_accessible_resources ?놁씠??洹??대룞 ?먯껜瑜? * ERR_BLOCKED_BY_CLIENT濡?留됱븘踰꾨┛????곸씠 "invalid"濡??쒖떆?섎뒗 李⑤떒 ?붾㈃???⑤뒗 ?먯씤?댁뿀??.
 * ????대? ???대룞 沅뚰븳???덈뒗 諛곌꼍 ?ㅽ겕由쏀듃(background.js)??硫붿떆吏濡??붿껌?쒕떎.
 */
function triggerImmediateRecheck() {
  chrome.runtime.sendMessage({ type: "recheckDomain", domain: location.hostname });
}

async function refreshThemeIfDue() {
  if (pollTickCount !== 1 && pollTickCount % THEME_POLL_TICKS !== 0) return;
  try {
    const res = await fetchWithTimeout(`${API_BASE}/theme`, {}, OVERLAY_FETCH_TIMEOUT_MS);
    const data = await res.json();
    currentPalette = getThemePalette(data.themeMode);
  } catch (e) {
    // 통신 실패 시 이전에 확인된 팔레트를 그대로 유지.
  }
}

async function refreshOverlayStatus() {
  pollTickCount += 1;
  await refreshThemeIfDue();
  try {
    const res = await fetchWithTimeout(
      `${API_BASE}/overlay-status?domain=${encodeURIComponent(location.hostname)}`,
      {},
      OVERLAY_FETCH_TIMEOUT_MS
    );
    const data = await res.json();
    const remaining = data.remainingSeconds || 0;
    if (remaining > 0) {
      showOrResyncOverlay(remaining, data.level || 0, !!data.isPomodoro, data.levelStepsToMax || 5);
      wasShowingOverlay = true;
    } else {
      hideOverlay();
      if (wasShowingOverlay) {
        wasShowingOverlay = false;
        triggerImmediateRecheck();
      }
    }
  } catch (e) {
    // ?곗뒪?ы깙 ?깃낵 ?듭떊?????섎㈃ ?ㅻ쾭?덉씠??洹몃?濡??먭굅???대? ???덉쑝硫? 議곗슜???섏뼱媛꾨떎.
  }
}

refreshOverlayStatus();
setInterval(refreshOverlayStatus, OVERLAY_POLL_MS);
