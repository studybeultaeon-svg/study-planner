// background.js(서비스워커, importScripts로 로드)와 콘텐츠 스크립트/확장 페이지(overlay.js,
// confirm.js — <script> 태그로 로드) 양쪽에서 공유하는 상수/유틸. 포트를 바꿀 때 이 파일 하나만
// 고치면 되도록 하기 위함이다.
const API_BASE = "http://127.0.0.1:47882";
const DEFAULT_FETCH_TIMEOUT_MS = 800;

function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_FETCH_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...options, signal: controller.signal }).finally(() => clearTimeout(timer));
}
