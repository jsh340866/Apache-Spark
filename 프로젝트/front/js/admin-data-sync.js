// 투자일지 실제 저장 테이블 — investment-journal.js와 동일한 키를 그대로 읽고 쓴다.
const JOURNAL_POSITION_KEY = 'valuepick.positions.v3';
const JOURNAL_BUY_KEY = 'valuepick.buys.v3';
const JOURNAL_SELL_KEY = 'valuepick.sells.v3';

function loadJsonTable(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : fallback;
  } catch (e) {
    return fallback;
  }
}

// 실제 백엔드라면 이 페이지를 열람하는(RESTful 요청) 시점에 서버가 자동으로 갱신할 값이다.
// 지금은 로컬스토리지 기반이라 이 버튼으로 그 갱신을 수동 트리거한다.
function refreshHoldingJournals() {
  const positions = loadJsonTable(JOURNAL_POSITION_KEY, []);
  const buys = loadJsonTable(JOURNAL_BUY_KEY, []);
  const sells = loadJsonTable(JOURNAL_SELL_KEY, []);

  const boughtQty = (positionId) => buys.filter((b) => b.positionId === positionId).reduce((s, b) => s + Number(b.quantity || 0), 0);
  const soldQty = (positionId) => sells.filter((s) => s.positionId === positionId).reduce((s, x) => s + Number(x.quantity || 0), 0);
  const isClosed = (position) => boughtQty(position.id) > 0 && boughtQty(position.id) - soldQty(position.id) <= 0;

  const now = new Date().toISOString();
  let updatedCount = 0;
  positions.forEach((position) => {
    if (!isClosed(position)) {
      position.finalSellAt = now;
      updatedCount += 1;
    }
  });

  localStorage.setItem(JOURNAL_POSITION_KEY, JSON.stringify(positions));
  return updatedCount;
}

document.addEventListener('DOMContentLoaded', () => {
  initHeader('admin');
  initFooter();

  const logEl = document.getElementById('syncLog');

  function pushLog(text) {
    const now = new Date().toLocaleTimeString('ko-KR');
    const item = document.createElement('div');
    item.className = 'log-item';
    item.textContent = `[${now}] ${text}`;
    logEl.prepend(item);
  }

  document.querySelectorAll('button[data-sync]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const type = btn.dataset.sync;
      btn.disabled = true;
      btn.textContent = '실행 중...';
      pushLog(`${type} 데이터 수집 시작`);

      await new Promise((resolve) => setTimeout(resolve, 900));

      if (type === 'journal-refresh') {
        const count = refreshHoldingJournals();
        pushLog(`보유 내역 ${count}건의 최종 매도 시간을 현재 시각으로 갱신했습니다.`);
      }

      btn.disabled = false;
      btn.textContent = '실행';
      pushLog(`${type} 데이터 수집 완료`);
    });
  });
});
