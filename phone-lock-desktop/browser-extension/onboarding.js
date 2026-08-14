fetchAndApplyTheme();

document.getElementById("openSettings").addEventListener("click", () => {
  chrome.tabs.create({ url: `chrome://extensions/?id=${chrome.runtime.id}` });
});
