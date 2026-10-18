(() => {
  if (globalThis.FTSiteStartup) return;

  // 网页尚未触发 load 时不修改 DOM，避免和站点初始化 / SPA hydration 抢资源。
  // 若某网站本身始终无法完成加载，则自动翻译保持待命，用户仍可在扩展菜单控制。
  const stable = new Promise(resolve => {
    let started = false;
    const markReady = () => {
      if (started) return;
      started = true;
      setTimeout(resolve, 1500);
    };
    if (document.readyState === "complete") markReady();
    else window.addEventListener("load", markReady, { once: true });
  });

  globalThis.FTSiteStartup = Object.freeze({
    wait: () => stable
  });
})();