importScripts("config.js"); // API_BASE, fetchWithTimeout 정의

const TICK_PERIOD_MINUTES = 1;
const REELS_SHORTS_CONFIG_REFRESH_MINUTES = 1;
// 절전/유휴 등으로 알람이 지연되더라도, 한 번의 tick으로 보고되는 사용시간이 비정상적으로 커지지
// 않도록 상한을 둔다(서버 쪽 clamp와는 별개로 클라이언트에서도 방어).
const MAX_TICK_ELAPSED_SECONDS = TICK_PERIOD_MINUTES * 60 * 5;

let reelsShortsConfig = { blockReels: false, blockShorts: false };
let apiToken = null;
let lastTickAt = Date.now();

/**
 * 데스크탑 앱의 로컬 API는 인증이 없으면 다른 로컬 프로세스나 웹페이지의 스크립트가 /confirm,
 * /tick을 직접 호출해 확인 절차를 통째로 건너뛸 수 있어서, 설치마다 발급되는 토큰을 헤더에 실어
 * 보내야 상태 변경 요청이 통과되도록 바뀌었다. 토큰은 최초 1회 /pair로 받아 chrome.storage.local에
 * 캐싱해두고, 서버가 403을 주면(토큰 파일이 재생성된 경우 등) 캐시를 지우고 다음 시도에서 다시 받는다.
 */
async function ensureApiToken() {
  if (apiToken) return apiToken;
  try {
    const stored = await chrome.storage.local.get("apiToken");
    if (stored.apiToken) {
      apiToken = stored.apiToken;
      return apiToken;
    }
  } catch (e) {
    // storage 접근 실패는 무시하고 /pair로 계속 시도
  }
  try {
    const res = await fetchWithTimeout(`${API_BASE}/pair`);
    const data = await res.json();
    if (data.token) {
      apiToken = data.token;
      await chrome.storage.local.set({ apiToken });
    }
  } catch (e) {
    // 데스크탑 앱이 꺼져 있으면 다음 시도 때 다시 페어링
  }
  return apiToken;
}

async function invalidateApiToken() {
  apiToken = null;
  try {
    await chrome.storage.local.remove("apiToken");
  } catch (e) {
    // 무시
  }
}

async function authorizedHeaders() {
  const token = await ensureApiToken();
  return token ? { "X-PhoneLock-Token": token } : {};
}

function extractHostname(urlString) {
  try {
    const url = new URL(urlString);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    return url.hostname;
  } catch (e) {
    return null;
  }
}

function normalizeHost(hostname) {
  return hostname.replace(/^www\./, "");
}

/** youtube.com/shorts/... 형태의 URL인지 확인한다. */
function isShortsUrl(urlString) {
  try {
    const url = new URL(urlString);
    return normalizeHost(url.hostname) === "youtube.com" && url.pathname.startsWith("/shorts");
  } catch (e) {
    return false;
  }
}

/** instagram.com/reels/... 또는 /reel/... 형태의 URL인지 확인한다. */
function isReelsUrl(urlString) {
  try {
    const url = new URL(urlString);
    return (
      normalizeHost(url.hostname) === "instagram.com" &&
      (url.pathname.startsWith("/reels") || url.pathname.startsWith("/reel/"))
    );
  } catch (e) {
    return false;
  }
}

async function refreshReelsShortsConfig() {
  try {
    const response = await fetchWithTimeout(`${API_BASE}/reels-shorts-config`);
    reelsShortsConfig = await response.json();
  } catch (e) {
    // 데스크탑 앱이 꺼져 있으면 마지막으로 알던 설정을 그대로 유지한다.
  }
}

function blockedUrlFor(reason, attempts) {
  return chrome.runtime.getURL(
    `blocked.html?reason=${encodeURIComponent(reason)}&attempts=${encodeURIComponent(attempts || 0)}`
  );
}

/** 릴스/쇼츠 URL이고 해당 차단 설정이 켜져 있으면 즉시 차단 페이지로 보낸다. */
function applyReelsShortsBlockIfNeeded(tabId, url) {
  if (reelsShortsConfig.blockShorts && isShortsUrl(url)) {
    chrome.tabs.update(tabId, { url: blockedUrlFor("SHORTS") });
    return true;
  }
  if (reelsShortsConfig.blockReels && isReelsUrl(url)) {
    chrome.tabs.update(tabId, { url: blockedUrlFor("REELS") });
    return true;
  }
  return false;
}

function applyAction(data, tabId, hostname, currentUrl) {
  if (data.action === "block") {
    chrome.tabs.update(tabId, { url: blockedUrlFor(data.reason || "", data.attempts) });
  } else if (data.action === "confirm") {
    const confirmUrl = chrome.runtime.getURL(
      `confirm.html?domain=${encodeURIComponent(hostname)}` +
        `&groupId=${encodeURIComponent(data.groupId)}` +
        `&wait=${encodeURIComponent(data.waitSeconds)}` +
        `&level=${encodeURIComponent(data.level || 0)}` +
        `&returnUrl=${encodeURIComponent(currentUrl)}`
    );
    chrome.tabs.update(tabId, { url: confirmUrl });
  }
  // action === "allow" 또는 알 수 없는 응답이면 아무것도 하지 않고 그대로 통과
}

async function checkNavigation(details) {
  if (details.frameId !== 0) return;

  // 릴스/쇼츠는 그룹 등록 여부와 무관하게 URL 패턴만으로 판단하므로, 도메인 그룹 확인보다 먼저 처리한다.
  if (applyReelsShortsBlockIfNeeded(details.tabId, details.url)) return;

  const hostname = extractHostname(details.url);
  if (!hostname) return;

  try {
    const response = await fetchWithTimeout(
      `${API_BASE}/check?domain=${encodeURIComponent(hostname)}`
    );
    const data = await response.json();
    applyAction(data, details.tabId, hostname, details.url);
  } catch (e) {
    // 데스크탑 앱이 꺼져 있거나 응답이 없으면 그냥 통과시킨다 (fail-open)
  }
}

chrome.webNavigation.onBeforeNavigate.addListener(checkNavigation);

// overlay.js(콘텐츠 스크립트)는 페이지 안에서 실행되기 때문에 confirm.html 같은 확장프로그램 내부
// 페이지로 직접 location.href 이동을 못 시킨다(web_accessible_resources 없이는 크롬이
// ERR_BLOCKED_BY_CLIENT로 막아버림). 대신 여기(이미 chrome.tabs.update 권한이 있는 배경 스크립트)로
// 메시지를 보내서 대신 이동시켜달라고 요청한다.
chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== "recheckDomain") return;
  const tabId = sender.tab?.id;
  const domain = message.domain;
  if (tabId == null || !domain) return;
  (async () => {
    try {
      const response = await fetchWithTimeout(`${API_BASE}/check?domain=${encodeURIComponent(domain)}`);
      const data = await response.json();
      applyAction(data, tabId, domain, sender.tab.url || "");
    } catch (e) {
      // 데스크탑 앱과 통신이 안 되면 조용히 무시 — 다음 1분 tick이 다시 시도한다.
    }
  })();
});

// 유튜브 쇼츠/인스타 릴스는 같은 페이지 안에서 history.pushState로 다음 영상으로 넘어가는 경우가 많아
// onBeforeNavigate가 발생하지 않는다. 이런 SPA 전환도 잡아내기 위해 별도로 감시한다.
chrome.webNavigation.onHistoryStateUpdated.addListener(checkNavigation);

/** 이미 열려있던 탭으로 전환됐을 때(Alt-Tab, 탭 클릭 등) 즉시 재검사한다. onBeforeNavigate는
 * 새 네비게이션에만 반응하므로, 탭/창 전환만으로는 최대 1분(tick 주기)까지 검사가 늦어지는 문제가
 * 있었다 — 이 리스너들이 그 지연을 없앤다. */
async function checkTabNow(tabId) {
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!tab.url) return;
    if (applyReelsShortsBlockIfNeeded(tab.id, tab.url)) return;

    const hostname = extractHostname(tab.url);
    if (!hostname) return;

    const response = await fetchWithTimeout(`${API_BASE}/check?domain=${encodeURIComponent(hostname)}`);
    const data = await response.json();
    applyAction(data, tab.id, hostname, tab.url);
  } catch (e) {
    // 탭이 이미 닫혔거나 데스크탑 앱이 꺼져 있으면 무시 — 다음 tick이 다시 시도한다.
  }
}

// 같은 창 안에서 다른 탭으로 전환.
chrome.tabs.onActivated.addListener((activeInfo) => {
  checkTabNow(activeInfo.tabId);
});

// 다른 창으로 전환(Alt-Tab 포함). 포커스를 잃을 때(WINDOW_ID_NONE)는 검사할 대상이 없으므로 건너뛴다.
chrome.windows.onFocusChanged.addListener(async (windowId) => {
  if (windowId === chrome.windows.WINDOW_ID_NONE) return;
  try {
    const [tab] = await chrome.tabs.query({ active: true, windowId });
    if (tab) checkTabNow(tab.id);
  } catch (e) {
    // 무시
  }
});

chrome.alarms.create("tick", { periodInMinutes: TICK_PERIOD_MINUTES });
chrome.alarms.create("reelsShortsConfigRefresh", { periodInMinutes: REELS_SHORTS_CONFIG_REFRESH_MINUTES });
refreshReelsShortsConfig();
ensureApiToken();

/**
 * 확장프로그램이 꺼지거나 삭제되거나 시크릿 창으로 옮겨가면 데스크탑 쪽에서는 이를 알 방법이 없어서
 * 사이트 차단이 조용히 무력화된다. 그래서 tick과 같은 주기(1분)로 데스크탑에 "살아있다"는 신호를
 * 보내고, 데스크탑은 이 신호가 일정 시간 끊기면 배너로 경고한다.
 */
async function sendHeartbeat() {
  try {
    const headers = await authorizedHeaders();
    await fetchWithTimeout(`${API_BASE}/heartbeat`, { method: "POST", headers });
  } catch (e) {
    // 데스크탑 앱이 꺼져 있으면 당연히 실패한다 — 그게 정확히 감지하려는 상황이므로 무시.
  }
}

// 설치 직후 안내 페이지를 띄워, 시크릿 모드에서는 확장을 수동으로 허용해야 한다는 것을 알려준다.
// (Chrome 정책상 확장이 스스로 시크릿 모드 접근을 켤 수는 없고, 사용자가 확장 상세 페이지에서 직접
// 토글해야 한다.)
chrome.runtime.onInstalled.addListener((details) => {
  if (details.reason === "install") {
    chrome.tabs.create({ url: chrome.runtime.getURL("onboarding.html") });
  }
});

/** 한 탭에 대해 릴스/쇼츠 차단과 사용시간 tick 보고를 수행한다. */
async function tickTab(tab, elapsedSeconds) {
  if (!tab.url) return;
  if (applyReelsShortsBlockIfNeeded(tab.id, tab.url)) return;

  const hostname = extractHostname(tab.url);
  if (!hostname) return;

  try {
    const headers = await authorizedHeaders();
    const response = await fetchWithTimeout(
      `${API_BASE}/tick?domain=${encodeURIComponent(hostname)}&elapsedSeconds=${elapsedSeconds}`,
      { method: "POST", headers }
    );
    if (response.status === 403) {
      // 토큰이 무효화된 경우(예: 데스크탑 앱 재설치로 토큰 파일이 바뀜) 다음 tick에서 재발급받는다.
      await invalidateApiToken();
      return;
    }
    const data = await response.json();
    applyAction(data, tab.id, hostname, tab.url);
  } catch (e) {
    // 데스크탑 앱이 꺼져 있으면 조용히 무시
  }
}

// 이미 열려있던 탭(확장프로그램 설치/새로고침 전부터 열려있던 탭 등)은 onBeforeNavigate가
// 발생하지 않으므로, 주기적으로 현재 탭을 다시 검사해서 확인/차단을 놓치지 않게 한다.
chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === "reelsShortsConfigRefresh") {
    refreshReelsShortsConfig();
    return;
  }
  if (alarm.name !== "tick") return;

  const now = Date.now();
  // 알람은 주기적으로 1분마다 예약되지만, 시스템 절전/브라우저 유휴 상태에서는 지연되거나 건너뛸 수
  // 있다. 항상 60초가 지난 것으로 가정하면 실제로는 더 오래 지난 경우 사용시간이 실제보다 적게
  // 기록되므로, 마지막 tick 이후 실제 경과시간을 재서 보고한다.
  const elapsedSeconds = Math.min(
    MAX_TICK_ELAPSED_SECONDS,
    Math.max(1, Math.round((now - lastTickAt) / 1000))
  );
  lastTickAt = now;

  // 창이 여러 개(멀티 모니터 등) 열려있을 때 lastFocusedWindow만 보면, 포커스가 없는 창에서 계속
  // 재생 중인 사이트는 전혀 검사되지 않아 사실상 시간 제한이 무력화된다. 모든 창의 활성 탭을 각각
  // 검사한다.
  const tabs = await chrome.tabs.query({ active: true });
  await Promise.all([sendHeartbeat(), ...tabs.map((tab) => tickTab(tab, elapsedSeconds))]);
});
