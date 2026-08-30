fetchAndApplyTheme();

const params = new URLSearchParams(location.search);
const reason = params.get("reason") || "";

const messages = {
  SCHEDULE: "지정된 시간대에는 이 그룹의 사이트를 사용할 수 없습니다.",
  LIMIT: "오늘 이 그룹의 사용 시간 한도를 모두 사용했습니다.",
  SCHEDULE_AND_LIMIT: "지정된 시간대이면서 오늘 사용 시간 한도도 모두 사용해서 잠겼습니다.",
  REELS: "릴스는 차단되어 있습니다.",
  SHORTS: "쇼츠는 차단되어 있습니다.",
  STUDY_LOCK: "공부 중에는 허용된 사이트만 이용할 수 있습니다."
};

const attempts = parseInt(params.get("attempts") || "0", 10);

document.getElementById("message").textContent = messages[reason] || "이 그룹은 현재 잠겨 있습니다.";
document.getElementById("title").textContent = quoteForTier(blockQuoteTier(attempts));

// 실행확인(confirm.js)과 같은 톤: "확인"이라는 탈출구를 주지 않기 위해 "진행" 버튼은 눌러도
// 아무 동작을 하지 않는 장식용 버튼이고, 실제로 페이지를 벗어나는 동작은 "중단"에만 걸려있다.
document.getElementById("no").addEventListener("click", () => {
  location.href = "https://www.google.com";
});
