// API_BASE, fetchWithTimeout은 config.js(confirm.html에서 이 스크립트보다 먼저 로드됨)에 정의되어 있다.
// applyTheme/fetchAndApplyTheme은 theme.js에 정의되어 있다.
const CONFIRM_FETCH_TIMEOUT_MS = 3000;
fetchAndApplyTheme();

/** background.js와 동일한 토큰 발급/캐싱 로직. 이 페이지는 확장프로그램 자체 페이지(chrome-extension://)로
 *  열려 있어 chrome.storage에 직접 접근할 수 있다. */
async function getApiToken() {
  try {
    const stored = await chrome.storage.local.get("apiToken");
    if (stored.apiToken) return stored.apiToken;
  } catch (e) {
    // 무시하고 /pair로 계속 시도
  }
  try {
    const res = await fetchWithTimeout(`${API_BASE}/pair`, {}, CONFIRM_FETCH_TIMEOUT_MS);
    const data = await res.json();
    if (data.token) {
      await chrome.storage.local.set({ apiToken: data.token });
      return data.token;
    }
  } catch (e) {
    // 데스크탑 앱과 통신이 안 되면 토큰 없이 진행 (서버가 401/403을 주더라도 이동 자체는 허용)
  }
  return null;
}

const params = new URLSearchParams(location.search);
const domain = params.get("domain") || "";
const groupId = params.get("groupId") || "";
const waitSeconds = parseInt(params.get("wait") || "0", 10);
const level = parseInt(params.get("level") || "0", 10);
const returnUrl = params.get("returnUrl") || "https://www.google.com";

document.getElementById("title").textContent = quoteForTier(confirmQuoteTier(level));

const yesButton = document.getElementById("yes");
const noButton = document.getElementById("no");
const hint = document.getElementById("hint");

// 데스크탑 프로그램 실행확인(WatchAndWaitScreen)/모바일 실행확인(InterstitialScreen)과 동일한 방식:
// "잠겨있다가 풀리면 누르는" 게 아니라 "누르면 그때부터 대기시간이 시작되고, 다 지나면 자동으로
// 진행되는" 방식이다. 게다가 대기 도중 무작위 지점(들)에서 한 번씩 멈춰서 사용자가 다시 눌러야
// 이어지게 한다(불시 재확인). 이 탭을 벗어나면(포커스/가시성 상실) 처음부터 다시 눌러야 한다.
// 중간 체크포인트와는 별개로, 0(대기시간이 다 지난 시점)은 항상 추가로 붙는 체크포인트다 — 타이머가
// 끝나도 자동으로 진행되지 않고 마지막으로 한 번 더 눌러야 실제로 proceed()가 호출된다.
function pickCheckpoints(total) {
  const mids = [];
  if (total >= 4) {
    // 대기시간이 길어질수록(실행확인 레벨이 오를수록 대기시간도 늘어나므로) 중간에 다시 눌러야
    // 하는 횟수도 계단식으로 늘어난다 — 30초마다 1번씩 추가.
    const baseCount = 1 + Math.floor(total / 30) + Math.floor(Math.random() * 2);
    const count = Math.min(Math.max(baseCount, 1), total - 1);
    const candidates = [];
    for (let s = 1; s < total; s++) candidates.push(s);
    for (let i = candidates.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
    }
    mids.push(...candidates.slice(0, count));
  }
  return [...mids, 0];
}

const originalCheckpoints = pickCheckpoints(waitSeconds);
let pendingCheckpoints = [...originalCheckpoints];
let started = false;
let paused = false;
let remaining = waitSeconds;
let timer = null;

function isTabActive() {
  return document.visibilityState === "visible" && document.hasFocus();
}

function renderYesLabel() {
  if (started && !paused) {
    yesButton.textContent = remaining > 0 ? `진행 (${remaining}초)` : "진행";
    yesButton.disabled = true;
  } else {
    yesButton.textContent = "진행";
    yesButton.disabled = false;
  }
}

function renderHint() {
  if (!hint) return;
  if (started && paused) {
    hint.textContent = "이게 의무입니까?";
    hint.style.display = "block";
  } else if (started && !isTabActive()) {
    hint.textContent = "이 탭을 벗어나서 다시 눌러야 합니다.";
    hint.style.display = "block";
  } else {
    hint.style.display = "none";
  }
}

async function proceed() {
  try {
    const token = await getApiToken();
    await fetchWithTimeout(
      `${API_BASE}/confirm?domain=${encodeURIComponent(domain)}&groupId=${encodeURIComponent(groupId)}`,
      { method: "POST", headers: token ? { "X-PhoneLock-Token": token } : {} },
      CONFIRM_FETCH_TIMEOUT_MS
    );
  } catch (e) {
    // 데스크탑 앱과 통신이 안 되거나(꺼져있음) 타임아웃이 나도 일단 이동은 허용 —
    // 여기서 무한 대기하면 사용자가 대기시간을 다 채우고도 페이지를 못 벗어나게 된다.
  }
  location.href = returnUrl;
}

function resetProgress() {
  started = false;
  paused = false;
  remaining = waitSeconds;
  pendingCheckpoints = [...originalCheckpoints];
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  renderYesLabel();
  renderHint();
}

function startTimer() {
  if (remaining <= 0) {
    // 타이머가 다 끝났어도 0은 항상 체크포인트로 남아있으므로 여기서 걸린다 — 자동으로
    // 진행되지 않고 마지막으로 한 번 더 눌러야 한다.
    if (pendingCheckpoints.includes(0)) {
      pendingCheckpoints = pendingCheckpoints.filter((s) => s !== 0);
      paused = true;
      renderYesLabel();
      renderHint();
      return;
    }
    proceed();
    return;
  }
  timer = setInterval(() => {
    if (!isTabActive()) {
      resetProgress();
      return;
    }
    remaining -= 1;
    if (pendingCheckpoints.includes(remaining)) {
      pendingCheckpoints = pendingCheckpoints.filter((s) => s !== remaining);
      paused = true;
      clearInterval(timer);
      timer = null;
      renderYesLabel();
      renderHint();
      return;
    }
    if (remaining <= 0) {
      clearInterval(timer);
      timer = null;
      proceed();
      return;
    }
    renderYesLabel();
  }, 1000);
}

yesButton.addEventListener("click", () => {
  if (!started) {
    started = true;
    remaining = waitSeconds;
    renderYesLabel();
    renderHint();
    startTimer();
  } else if (paused) {
    paused = false;
    renderYesLabel();
    renderHint();
    startTimer();
  }
});

document.addEventListener("visibilitychange", renderHint);
window.addEventListener("focus", renderHint);
window.addEventListener("blur", renderHint);

noButton.addEventListener("click", () => {
  location.href = "https://www.google.com";
});

renderYesLabel();
renderHint();
