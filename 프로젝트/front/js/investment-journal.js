/** 투자일지 — localStorage 제거, 백엔드 API 연동 */

const PAGE_SIZE = 10;
const RECORD_PAGE_SIZE = 5;

let activeCategory = 'all';
let query = '';
let currentPage = 0;
let fromDate = '';
let toDate = '';

let listData = { content: [], totalPages: 1, totalElements: 0 };

let currentDetail = null; // { kind: 'buy'|'sell'|'position', id }
let detailStack = [];
let positionDetailData = null; // JournalDetailDto from API
let buyRecordsPage = 0;
let sellRecordsPage = 0;
let lastPositionDetailId = null;

function getDefaultDateRange() {
  const today = new Date();
  const from = new Date(today.getFullYear(), today.getMonth() - 1, today.getDate());
  return { from: formatDateStr(from), to: formatDateStr(today) };
}

function formatDateStr(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function formatDateTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function formatDate(value) {
  if (!value) return '-';
  const d = new Date(value);
  if (isNaN(d.getTime())) return '-';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function formatShortDate(value) {
  if (!value) return '-';
  const d = new Date(value);
  if (isNaN(d.getTime())) return '-';
  return `${String(d.getFullYear()).slice(-2)}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`;
}

function getNowDateTimeLocal() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function formatWon(value) {
  return Number(value || 0).toLocaleString('ko-KR') + '원';
}

function formatShares(value) {
  return Number(value || 0).toLocaleString('ko-KR') + '주';
}

function changeClass(rate) {
  if (rate > 0) return 'positive';
  if (rate < 0) return 'negative';
  return '';
}

// ─── API 호출 ──────────────────────────────────────────────────────────────────

async function apiFetch(url, options = {}) {
  try {
    const res = await authFetch(url, options);
    if (!res) return null;
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.message || body.error || `요청 실패 (${res.status})`);
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  } catch (e) {
    window.alert(e.message || '서버 오류가 발생했습니다.');
    return null;
  }
}

async function fetchList() {
  const params = new URLSearchParams({ category: activeCategory, q: query, page: currentPage });
  if (fromDate) params.set('from', fromDate);
  if (toDate) params.set('to', toDate);
  const data = await apiFetch(`/api/journal?${params}`);
  if (data) {
    listData = data;
    render();
  }
}

async function fetchPositionDetail(journalId) {
  return await apiFetch(`/api/journal/position/${journalId}`);
}

// 배경 갱신용 — 실패해도 alert 띄우지 않고 조용히 무시
async function apiFetchSilent(url) {
  try {
    const res = await authFetch(url);
    if (!res || !res.ok) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  } catch (e) {
    return null;
  }
}

// 현재가/평가금액/손익 등 시세 관련 값만 in-place로 갱신 (전체 재렌더링 없이 깜빡임 방지)
// 화면에 없는 항목이 있으면 스킵할 뿐, 전체 실패로 취급하지 않음
function patchListPrices(items) {
  const columns = COLUMN_SETS[activeCategory] || COLUMN_SETS.all;
  const priceCols = columns.filter(col => ['현재시세', '평가금액', '손익'].includes(col));
  if (priceCols.length === 0) return;

  const tableBodyEl = document.getElementById('journalTableBody');
  const listEl = document.getElementById('journalList');

  items.forEach(item => {
    const f = getFields(item);
    const row = tableBodyEl.querySelector(`tr[data-id="${f.id}"][data-kind="${f.type}"]`);
    const card = listEl.querySelector(`.journal-card[data-id="${f.id}"][data-kind="${f.type}"]`);

    priceCols.forEach(col => {
      const value = getCellValue(col, f);
      const cell = row && row.querySelector(`td[data-col="${col}"]`);
      if (cell) cell.innerHTML = value;
      const metricValueEl = card && card.querySelector(`.metric-item[data-col="${col}"] .metric-value`);
      if (metricValueEl) metricValueEl.innerHTML = value;
    });
  });
}

async function refreshListPrices() {
  const params = new URLSearchParams({ category: activeCategory, q: query, page: currentPage });
  if (fromDate) params.set('from', fromDate);
  if (toDate) params.set('to', toDate);
  const data = await apiFetchSilent(`/api/journal?${params}`);
  if (!data) return;
  listData = data;
  patchListPrices(data.content || []);
}

// ─── 렌더링 ────────────────────────────────────────────────────────────────────

const COLUMN_SETS = {
  all:  ['제목', '종목', '상태', '시간'],
  buy:  ['종목', '매수일', '매수주', '매수가'],
  sell: ['종목', '매도일', '매도주', '매도가'],
  hold: ['제목', '종목', '기간', '보유주', '현재시세', '평가금액'],
  deal: ['제목', '종목', '기간', '손익'],
};

function getFields(item) {
  if (item.type === 'buy') {
    return {
      type: 'buy', id: item.id, journalId: item.journalId,
      title: item.title, stockCode: item.stockCode, corpName: item.corpName,
      stateLabel: '매수', stateClass: 'category',
      dateValue: formatDate(item.buyAt), periodValue: null,
      quantity: item.quantity, price: item.price,
      currentPrice: null, valuation: null, pnl: null,
      isShared: item.isShared,
    };
  }
  if (item.type === 'sell') {
    return {
      type: 'sell', id: item.id, journalId: item.journalId,
      title: item.title, stockCode: item.stockCode, corpName: item.corpName,
      stateLabel: '매도', stateClass: 'retrospect',
      dateValue: formatDate(item.sellAt), periodValue: null,
      quantity: item.quantity, price: item.price,
      currentPrice: null, valuation: null, pnl: null,
      isShared: item.isShared,
    };
  }
  // position
  const closed = item.state === '완료';
  const periodEnd = item.finalSellAt || new Date().toISOString();
  return {
    type: 'position', id: item.id, journalId: item.id,
    title: item.title, stockCode: item.stockCode, corpName: item.corpName,
    stateLabel: closed ? '거래완료' : '보유',
    stateClass: closed ? 'closed' : 'holding',
    dateValue: null,
    periodValue: `${formatShortDate(item.firstBuyAt)} ~ ${formatShortDate(periodEnd)}`,
    quantity: item.holdingQty,
    price: null,
    currentPrice: item.currentPrice,
    valuation: item.holdingQty != null && item.currentPrice != null ? item.holdingQty * item.currentPrice : null,
    pnl: item.pnl,
    isShared: item.isShared,
  };
}

function getCellValue(column, f) {
  switch (column) {
    case '제목': {
      if (f.type === 'position') return `<div class="td-name">${f.title}</div>`;
      const action = f.type === 'buy' ? '매수' : '매도';
      return `<div class="td-name">${f.dateValue} ${f.corpName} ${action}</div>`;
    }
    case '종목':    return `<div class="td-name">${f.corpName}</div><div class="td-code">${f.stockCode || '-'}</div>`;
    case '상태':    return `<span class="chip ${f.stateClass}">${f.stateLabel}</span>`;
    case '시간':    return f.periodValue || f.dateValue || '-';
    case '매수일':
    case '매도일':  return f.dateValue;
    case '매수주':
    case '보유주':
    case '매도주':  return formatShares(f.quantity);
    case '매수가':
    case '매도가':  return formatWon(f.price);
    case '기간':    return f.periodValue;
    case '현재시세': return f.currentPrice != null ? formatWon(f.currentPrice) : '-';
    case '평가금액': return f.valuation != null ? formatWon(f.valuation) : '-';
    case '손익':    return f.pnl != null ? `${f.pnl >= 0 ? '+' : ''}${formatWon(f.pnl)}` : '-';
    default:       return '';
  }
}

function render() {
  const columns = COLUMN_SETS[activeCategory] || COLUMN_SETS.all;
  const headRowEl = document.getElementById('journalTableHeadRow');
  headRowEl.innerHTML = columns.map(col => `<th>${col}</th>`).join('');

  const rows = listData.content || [];
  const tableBodyEl = document.getElementById('journalTableBody');
  const listEl = document.getElementById('journalList');

  if (rows.length === 0) {
    tableBodyEl.innerHTML = `<tr><td colspan="${columns.length}"><div class="empty">조건에 맞는 투자일지가 없습니다.</div></td></tr>`;
    listEl.innerHTML = '<div class="empty">조건에 맞는 투자일지가 없습니다.</div>';
    document.getElementById('journalPagination').innerHTML = '';
    return;
  }

  tableBodyEl.innerHTML = rows.map(item => {
    const f = getFields(item);
    const cells = columns.map(col => `<td data-col="${col}">${getCellValue(col, f)}</td>`).join('');
    return `<tr data-id="${f.id}" data-kind="${f.type}">${cells}</tr>`;
  }).join('');

  listEl.innerHTML = rows.map(item => {
    const f = getFields(item);
    const metricCols = columns.filter(c => !['제목', '종목', '상태'].includes(c));
    const metrics = metricCols.map(col => `
      <div class="metric-item" data-col="${col}">
        <div class="metric-label">${col}</div>
        <div class="metric-value">${getCellValue(col, f)}</div>
      </div>`).join('');
    const headTitle = f.type === 'position'
      ? `<h2 class="journal-title">${f.title}</h2>`
      : `<h2 class="journal-title">${f.corpName}</h2>`;
    return `
      <article class="journal-card" data-id="${f.id}" data-kind="${f.type}" role="button" tabindex="0" aria-label="${f.corpName} 상세 보기">
        <div class="journal-card-head">
          <div>
            ${headTitle}
            <p class="meta">${f.corpName}(${f.stockCode || '-'})</p>
          </div>
          <div class="chip-group">
            <span class="chip ${f.stateClass}">${f.stateLabel}</span>
          </div>
        </div>
        <div class="journal-metrics">${metrics}</div>
      </article>`;
  }).join('');

  tableBodyEl.querySelectorAll('tr[data-id]').forEach(row => {
    row.addEventListener('click', () => openDetailRoot(row.dataset.kind, row.dataset.id));
  });
  listEl.querySelectorAll('.journal-card').forEach(card => {
    const open = () => openDetailRoot(card.dataset.kind, card.dataset.id);
    card.addEventListener('click', open);
    card.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    });
  });

  renderPagination();
}

function renderPagination() {
  const journalPaginationEl = document.getElementById('journalPagination');
  const totalPages = listData.totalPages || 1;
  if (totalPages <= 1) { journalPaginationEl.innerHTML = ''; return; }

  const GROUP = 10;
  const groupStart = Math.floor(currentPage / GROUP) * GROUP;
  const groupEnd = Math.min(groupStart + GROUP, totalPages);
  let html = '';
  if (currentPage > 0) html += `<button class="page-btn" data-page="${currentPage - 1}">이전</button>`;
  for (let i = groupStart; i < groupEnd; i++) {
    html += `<button class="page-btn${i === currentPage ? ' active' : ''}" data-page="${i}">${i + 1}</button>`;
  }
  if (currentPage < totalPages - 1) html += `<button class="page-btn" data-page="${currentPage + 1}">다음</button>`;
  journalPaginationEl.innerHTML = html;
  journalPaginationEl.querySelectorAll('.page-btn[data-page]').forEach(btn => {
    btn.addEventListener('click', () => { currentPage = parseInt(btn.dataset.page, 10); fetchList(); });
  });
}

// ─── 상세 모달 ─────────────────────────────────────────────────────────────────

const actionModalEl = document.getElementById('journalActionModal');

function openDetailRoot(kind, id) {
  const modalKind = kind === 'hold' || kind === 'deal' ? 'position' : kind;
  detailStack = [];
  currentDetail = { kind: modalKind, id: String(id) };
  renderDetailModal();
  actionModalEl.classList.add('open');
  actionModalEl.setAttribute('aria-hidden', 'false');
}

function openDetailChild(kind, id) {
  if (currentDetail) detailStack.push(currentDetail);
  currentDetail = { kind, id: String(id) };
  renderDetailModal();
}

function goBackDetail() {
  while (detailStack.length) {
    const prev = detailStack.pop();
    currentDetail = prev;
    renderDetailModal();
    return;
  }
  closeActionModal();
}

function closeActionModal() {
  detailStack = [];
  currentDetail = null;
  positionDetailData = null;
  actionModalEl.classList.remove('open');
  actionModalEl.setAttribute('aria-hidden', 'true');
  document.getElementById('sellForm').classList.add('hidden');
  document.getElementById('sellForm').reset();
  document.getElementById('addBuyForm').classList.add('hidden');
  document.getElementById('addBuyForm').reset();
}

async function renderDetailModal() {
  document.getElementById('detailBackBtn').style.display = detailStack.length ? 'inline-block' : 'none';
  document.querySelector('.detail-title-edit').style.display = '';
  document.getElementById('sellForm').classList.add('hidden');
  document.getElementById('sellForm').reset();
  document.getElementById('addBuyForm').classList.add('hidden');
  document.getElementById('addBuyForm').reset();

  if (currentDetail.kind === 'buy') return renderBuyDetail();
  if (currentDetail.kind === 'sell') return renderSellDetail();
  await renderPositionDetail();
}

function findListItem(kind, id) {
  const sid = String(id);
  const fromList = (listData.content || []).find(item => item.type === kind && String(item.id) === sid);
  if (fromList) return fromList;

  if (positionDetailData) {
    const records = kind === 'buy' ? positionDetailData.buys : kind === 'sell' ? positionDetailData.sells : null;
    const fromDetail = records && records.find(r => String(r.id) === sid);
    if (fromDetail) return { ...fromDetail, type: kind };
  }
  return undefined;
}

function renderBuyDetail() {
  const item = findListItem('buy', currentDetail.id);
  if (!item) { closeActionModal(); return; }
  document.querySelector('.detail-title-edit').style.display = 'none';
  document.getElementById('actionMeta').innerHTML = `
    <div class="detail-block">
      <p class="meta">종목: ${item.corpName}(${item.stockCode || '-'})</p>
      <p class="meta">매수시간: ${formatDateTime(item.buyAt)}</p>
      <p class="meta">매수주: ${formatShares(item.quantity)}</p>
      <p class="meta">매수 당시 주가: ${formatWon(item.price)}</p>
    </div>`;
  document.getElementById('openBuyFormBtn').style.display = 'none';
  document.getElementById('openSellFormBtn').style.display = 'none';
  document.getElementById('deleteJournalBtn').style.display = 'inline-block';
}

function renderSellDetail() {
  const item = findListItem('sell', currentDetail.id);
  if (!item) { closeActionModal(); return; }
  document.querySelector('.detail-title-edit').style.display = 'none';
  document.getElementById('actionMeta').innerHTML = `
    <div class="detail-block">
      <p class="meta">종목: ${item.corpName}(${item.stockCode || '-'})</p>
      <p class="meta">매도시간: ${formatDateTime(item.sellAt)}</p>
      <p class="meta">매도주: ${formatShares(item.quantity)}</p>
      <p class="meta">매도 당시 주가: ${formatWon(item.price)}</p>
    </div>`;
  document.getElementById('openBuyFormBtn').style.display = 'none';
  document.getElementById('openSellFormBtn').style.display = 'none';
  document.getElementById('deleteJournalBtn').style.display = 'inline-block';
}

async function renderPositionDetail() {
  const journalId = currentDetail.id;
  if (lastPositionDetailId !== journalId) {
    buyRecordsPage = 0;
    sellRecordsPage = 0;
    lastPositionDetailId = journalId;
    positionDetailData = null;
  }

  if (!positionDetailData) {
    positionDetailData = await fetchPositionDetail(journalId);
    if (!positionDetailData) { closeActionModal(); return; }
  }

  const d = positionDetailData;
  document.getElementById('detailTitleInput').value = d.title;

  const closed = d.state === '완료';
  const qty = d.holdingQty || 0;
  const currentPrice = d.currentPrice;

  let statusHtml;
  if (!closed) {
    const sellAllNow = qty * (currentPrice || 0);
    const currentPnl = (d.soldAmount || 0) + sellAllNow - (d.usedAmount || 0);
    statusHtml = `
      <div class="detail-block">
        <h3>보유 현황</h3>
        <p class="meta">보유주 수: ${formatShares(qty)}</p>
        <p class="meta" id="detailCurrentPrice">현재가: ${currentPrice != null ? formatWon(currentPrice) : '-'}</p>
        <p class="meta" id="detailSellAllNow">현재 보유주 전량 매도가: ${currentPrice != null ? formatWon(sellAllNow) : '-'}</p>
        <p class="meta" id="detailCurrentPnl">현재 손익: ${currentPnl >= 0 ? '+' : ''}${formatWon(currentPnl)}</p>
        <p class="meta">사용 금액: ${formatWon(d.usedAmount)}</p>
        <p class="meta">최초 매수 기록: ${formatDateTime(d.firstBuyAt)}</p>
      </div>`;
  } else {
    const pnl = d.pnl || 0;
    const pnlRate = d.usedAmount > 0 ? (pnl / d.usedAmount) * 100 : 0;
    statusHtml = `
      <div class="detail-block">
        <h3>거래결과</h3>
        <p class="meta">사용 금액: ${formatWon(d.usedAmount)}</p>
        <p class="meta">손익: ${pnl >= 0 ? '+' : ''}${formatWon(pnl)}</p>
        <p class="meta">손익률: ${pnlRate >= 0 ? '+' : ''}${pnlRate.toFixed(2)}%</p>
        <p class="meta">최초 매수 시간: ${formatDateTime(d.firstBuyAt)}</p>
        <p class="meta">최종 매도 시간: ${formatDateTime(d.finalSellAt)}</p>
      </div>`;
  }

  const buyRows = renderRecordSection('buy', d.buys || []);
  const sellRows = renderRecordSection('sell', d.sells || []);

  document.getElementById('actionMeta').innerHTML = `
    <p class="meta">종목: ${d.corpName}(${d.stockCode || '-'})</p>
    ${statusHtml}
    <div class="detail-block"><h3>매수 기록</h3>${buyRows}</div>
    <div class="detail-block"><h3>매도 기록</h3>${sellRows}</div>
    <div class="detail-block">
      <h3>메모</h3>
      <textarea id="positionNoteInput" class="memo-textarea" rows="3" placeholder="메모를 입력하세요.">${d.note || ''}</textarea>
      <button class="btn-outline" id="positionNoteSaveBtn" type="button">메모 저장</button>
    </div>`;

  document.getElementById('openBuyFormBtn').style.display = !closed ? 'inline-block' : 'none';
  document.getElementById('openSellFormBtn').style.display = !closed && qty > 0 ? 'inline-block' : 'none';
  document.getElementById('deleteJournalBtn').style.display = 'inline-block';

  document.getElementById('actionMeta').querySelectorAll('.record-row').forEach(rowEl => {
    rowEl.addEventListener('click', () => openDetailChild(rowEl.dataset.kind, rowEl.dataset.id));
  });
  document.getElementById('positionNoteSaveBtn').addEventListener('click', async () => {
    const note = document.getElementById('positionNoteInput').value;
    const ok = await apiFetch(`/api/journal/position/${journalId}/note`, {
      method: 'PATCH',
      body: JSON.stringify({ note }),
    });
    if (ok !== undefined) {
      positionDetailData.note = note;
      window.alert('메모가 저장되었습니다.');
    }
  });
}

// 상세모달이 보유(position) 상세를 띄우고 있을 때 현재가 관련 값만 in-place로 갱신
async function refreshDetailPrice() {
  if (!currentDetail || currentDetail.kind !== 'position' || !positionDetailData) return;
  const fresh = await apiFetchSilent(`/api/journal/position/${currentDetail.id}`);
  if (!fresh) return;

  positionDetailData.currentPrice = fresh.currentPrice;
  positionDetailData.holdingQty = fresh.holdingQty;
  positionDetailData.usedAmount = fresh.usedAmount;
  positionDetailData.soldAmount = fresh.soldAmount;
  positionDetailData.pnl = fresh.pnl;
  if (positionDetailData.state === '완료') return;

  const qty = positionDetailData.holdingQty || 0;
  const currentPrice = positionDetailData.currentPrice;
  const sellAllNow = qty * (currentPrice || 0);
  const currentPnl = (positionDetailData.soldAmount || 0) + sellAllNow - (positionDetailData.usedAmount || 0);

  const priceEl = document.getElementById('detailCurrentPrice');
  const sellAllEl = document.getElementById('detailSellAllNow');
  const pnlEl = document.getElementById('detailCurrentPnl');
  if (priceEl) priceEl.textContent = `현재가: ${currentPrice != null ? formatWon(currentPrice) : '-'}`;
  if (sellAllEl) sellAllEl.textContent = `현재 보유주 전량 매도가: ${currentPrice != null ? formatWon(sellAllNow) : '-'}`;
  if (pnlEl) pnlEl.textContent = `현재 손익: ${currentPnl >= 0 ? '+' : ''}${formatWon(currentPnl)}`;
}

async function refreshPrices() {
  await refreshListPrices();
  await refreshDetailPrice();
}

function renderRecordSection(kind, records) {
  const sorted = [...records].sort((a, b) => {
    const da = kind === 'buy' ? a.buyAt : a.sellAt;
    const db = kind === 'buy' ? b.buyAt : b.sellAt;
    return new Date(db) - new Date(da);
  });

  const totalPages = Math.max(1, Math.ceil(sorted.length / RECORD_PAGE_SIZE));
  let page = kind === 'buy' ? buyRecordsPage : sellRecordsPage;
  page = Math.min(Math.max(0, page), totalPages - 1);
  if (kind === 'buy') buyRecordsPage = page;
  else sellRecordsPage = page;

  const pageItems = sorted.slice(page * RECORD_PAGE_SIZE, (page + 1) * RECORD_PAGE_SIZE);
  const itemsHtml = pageItems.map(item => {
    const dateVal = kind === 'buy' ? item.buyAt : item.sellAt;
    return `<div class="record-row" data-kind="${kind}" data-id="${item.id}">
      ${formatWon(item.price)} · ${formatShares(item.quantity)} · 총 ${formatWon(item.price * item.quantity)} · ${formatDateTime(dateVal)}
    </div>`;
  }).join('');

  const paginationHtml = sorted.length ? `
    <div class="record-pagination">
      <button type="button" class="btn-outline" data-record-nav="prev" data-record-kind="${kind}" ${page === 0 ? 'disabled' : ''}>이전</button>
      <span class="record-page-indicator">
        <input type="number" class="record-page-input" data-record-kind="${kind}" min="1" max="${totalPages}" value="${page + 1}" />
        / ${totalPages}
      </span>
      <button type="button" class="btn-outline" data-record-nav="next" data-record-kind="${kind}" ${page >= totalPages - 1 ? 'disabled' : ''}>다음</button>
    </div>` : '';

  return `<div class="record-list">${itemsHtml || `<p class="meta">${kind === 'buy' ? '매수' : '매도'} 기록 없음</p>`}</div>${paginationHtml}`;
}

// ─── 종목 검색 모달 ────────────────────────────────────────────────────────────

const stockSearchModalEl = document.getElementById('stockSearchModal');
const stockSearchKeywordEl = document.getElementById('stockSearchKeyword');
const stockSearchListEl = document.getElementById('stockSearchList');

async function renderStockSearchList() {
  const keyword = String(stockSearchKeywordEl.value || '').trim();
  if (!keyword) { stockSearchListEl.innerHTML = ''; return; }

  const results = await apiFetch(`/api/stocks/search?q=${encodeURIComponent(keyword)}`);
  if (!results) return;

  if (results.length === 0) {
    stockSearchListEl.innerHTML = '<div class="empty">검색 결과가 없습니다.</div>';
    return;
  }

  stockSearchListEl.innerHTML = results.map(item => `
    <article class="stock-item">
      <div>
        <strong>${item.corpName}</strong>
        <span>${item.stockCode} · 현재가 ${item.currentPrice != null ? formatWon(item.currentPrice) : '-'}</span>
      </div>
      <button class="btn-outline" type="button" data-code="${item.stockCode}" data-name="${item.corpName}">선택</button>
    </article>`).join('');
}

// ─── DOMContentLoaded ──────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  initHeader('journal');
  initFooter();

  const defaultRange = getDefaultDateRange();
  fromDate = defaultRange.from;
  toDate = defaultRange.to;

  const filterFromDateEl = document.getElementById('filterFromDate');
  const filterToDateEl = document.getElementById('filterToDate');
  filterFromDateEl.value = fromDate;
  filterToDateEl.value = toDate;

  fetchList();

  // 10초마다 현재가 관련 값(현재시세/평가금액/손익, 상세모달 현재가)만 배경 갱신
  setInterval(refreshPrices, 10000);

  // ── 탭 ──
  document.getElementById('categoryTabs').addEventListener('click', e => {
    const target = e.target.closest('button[data-category]');
    if (!target) return;
    document.querySelectorAll('#categoryTabs .tab').forEach(b => b.classList.remove('active'));
    target.classList.add('active');
    activeCategory = target.dataset.category;
    currentPage = 0;
    fetchList();
  });

  // ── 검색 ──
  document.getElementById('journalSearchInput').addEventListener('input', e => {
    query = e.target.value.trim().toLowerCase();
    currentPage = 0;
    fetchList();
  });

  // ── 날짜 필터 ──
  filterFromDateEl.addEventListener('change', () => { fromDate = filterFromDateEl.value; currentPage = 0; fetchList(); });
  filterToDateEl.addEventListener('change', () => { toDate = filterToDateEl.value; currentPage = 0; fetchList(); });

  // ── 종목 추가 모달 ──
  const createModalEl = document.getElementById('journalModal');
  const buyDateInput = document.getElementById('buyDateInput');
  const stockNameInput = document.getElementById('stockNameInput');
  const stockCodeInput = document.getElementById('stockCodeInput');
  const currentPriceDisplay = document.getElementById('currentPriceDisplay');
  const formEl = document.getElementById('journalForm');

  document.getElementById('openJournalModal').addEventListener('click', () => {
    createModalEl.classList.add('open');
    createModalEl.setAttribute('aria-hidden', 'false');
    buyDateInput.value = getNowDateTimeLocal();
    currentPriceDisplay.textContent = '종목 선택 시 표시됩니다.';
    stockCodeInput.value = '';
  });

  const closeCreateModal = () => {
    createModalEl.classList.remove('open');
    createModalEl.setAttribute('aria-hidden', 'true');
    formEl.reset();
    buyDateInput.value = getNowDateTimeLocal();
    currentPriceDisplay.textContent = '종목 선택 시 표시됩니다.';
  };

  document.getElementById('closeJournalModal').addEventListener('click', closeCreateModal);
  document.getElementById('cancelJournal').addEventListener('click', closeCreateModal);
  createModalEl.addEventListener('click', e => { if (e.target === createModalEl) closeCreateModal(); });

  stockNameInput.addEventListener('input', () => {
    stockCodeInput.value = '';
    currentPriceDisplay.textContent = '종목 선택 시 표시됩니다.';
  });

  document.getElementById('openStockSearchBtn').addEventListener('click', () => {
    stockSearchKeywordEl.value = stockNameInput.value || '';
    renderStockSearchList();
    stockSearchModalEl.classList.add('open');
    stockSearchModalEl.setAttribute('aria-hidden', 'false');
  });

  formEl.addEventListener('submit', async e => {
    e.preventDefault();
    const data = new FormData(formEl);
    const title = String(data.get('title') || '').trim();
    const stockCode = String(data.get('stockCode') || '').trim();
    const corpName = String(data.get('stockName') || '').trim();
    const buyAt = String(data.get('buyAt') || '').trim();
    const price = Number(data.get('price') || 0);
    const quantity = Number(data.get('quantity') || 0);

    if (!title || !corpName || !buyAt || price <= 0 || quantity <= 0) {
      window.alert('제목, 종목명, 매수 일시, 매수 당시 주가, 매수 수량을 모두 입력해주세요.');
      return;
    }

    const result = await apiFetch('/api/journal', {
      method: 'POST',
      body: JSON.stringify({ title, stockCode: stockCode || null, corpName, buyAt: new Date(buyAt).toISOString(), price, quantity }),
    });
    if (result !== undefined) {
      closeCreateModal();
      currentPage = 0;
      fetchList();
    }
  });

  // ── 종목 검색 모달 ──
  document.getElementById('closeStockSearchModal').addEventListener('click', () => {
    stockSearchModalEl.classList.remove('open');
    stockSearchModalEl.setAttribute('aria-hidden', 'true');
  });
  stockSearchModalEl.addEventListener('click', e => {
    if (e.target === stockSearchModalEl) {
      stockSearchModalEl.classList.remove('open');
      stockSearchModalEl.setAttribute('aria-hidden', 'true');
    }
  });
  stockSearchKeywordEl.addEventListener('input', renderStockSearchList);
  stockSearchListEl.addEventListener('click', e => {
    const btn = e.target.closest('button[data-code]');
    if (!btn) return;
    stockNameInput.value = btn.dataset.name;
    stockCodeInput.value = btn.dataset.code;
    currentPriceDisplay.textContent = '서버에서 조회 중...';
    apiFetch(`/api/stocks/${btn.dataset.code}`).then(detail => {
      const price = detail?.currentPrice ?? detail?.price;
      currentPriceDisplay.textContent = price != null ? formatWon(price) : '현재가 정보 없음';
    });
    stockSearchModalEl.classList.remove('open');
    stockSearchModalEl.setAttribute('aria-hidden', 'true');
  });

  // ── 액션 모달 공통 ──
  document.getElementById('closeActionModal').addEventListener('click', closeActionModal);
  actionModalEl.addEventListener('click', e => { if (e.target === actionModalEl) closeActionModal(); });
  document.getElementById('detailBackBtn').addEventListener('click', goBackDetail);

  // ── 제목 수정 ──
  document.getElementById('detailTitleSaveBtn').addEventListener('click', async () => {
    if (!currentDetail || currentDetail.kind !== 'position') return;
    const newTitle = document.getElementById('detailTitleInput').value.trim();
    if (!newTitle) { window.alert('제목을 입력해주세요.'); return; }
    const result = await apiFetch(`/api/journal/${currentDetail.kind}/${currentDetail.id}/title`, {
      method: 'PATCH',
      body: JSON.stringify({ title: newTitle }),
    });
    if (result !== undefined) {
      if (currentDetail.kind === 'position' && positionDetailData) positionDetailData.title = newTitle;
      fetchList();
    }
  });

  // ── 삭제 ──
  document.getElementById('deleteJournalBtn').addEventListener('click', async () => {
    if (!currentDetail) return;
    const ok = window.confirm('삭제할까요?');
    if (!ok) return;

    const result = await apiFetch(`/api/journal/${currentDetail.kind}/${currentDetail.id}`, { method: 'DELETE' });
    if (result !== undefined) {
      if (detailStack.length) {
        positionDetailData = null;
        goBackDetail();
      } else {
        closeActionModal();
      }
      fetchList();
    }
  });

  // ── 매수 등록 폼 ──
  document.getElementById('openBuyFormBtn').addEventListener('click', () => {
    document.getElementById('sellForm').classList.add('hidden');
    document.getElementById('sellForm').reset();
    document.getElementById('addBuyForm').classList.remove('hidden');
    document.getElementById('addBuyForm').querySelector('input[name=addBuyAt]').value = getNowDateTimeLocal();
  });
  document.getElementById('cancelAddBuyBtn').addEventListener('click', () => {
    document.getElementById('addBuyForm').classList.add('hidden');
    document.getElementById('addBuyForm').reset();
  });
  document.getElementById('addBuyForm').addEventListener('submit', async e => {
    e.preventDefault();
    if (!currentDetail || currentDetail.kind !== 'position') return;
    const data = new FormData(document.getElementById('addBuyForm'));
    const buyAt = String(data.get('addBuyAt') || '').trim();
    const quantity = Number(data.get('addBuyQuantity') || 0);
    const price = Number(data.get('addBuyPrice') || 0);
    if (!buyAt || !quantity || !price) {
      window.alert('매수 일시, 매수 수량, 매수 당시 주가를 모두 입력해주세요.');
      return;
    }
    const result = await apiFetch(`/api/journal/${currentDetail.id}/buy`, {
      method: 'POST',
      body: JSON.stringify({ buyAt: new Date(buyAt).toISOString(), price, quantity }),
    });
    if (result !== undefined) {
      positionDetailData = null;
      document.getElementById('addBuyForm').classList.add('hidden');
      document.getElementById('addBuyForm').reset();
      await renderPositionDetail();
      fetchList();
    }
  });

  // ── 매도 등록 폼 ──
  document.getElementById('openSellFormBtn').addEventListener('click', () => {
    document.getElementById('addBuyForm').classList.add('hidden');
    document.getElementById('addBuyForm').reset();
    document.getElementById('sellForm').classList.remove('hidden');
  });
  document.getElementById('cancelSellBtn').addEventListener('click', () => {
    document.getElementById('sellForm').classList.add('hidden');
    document.getElementById('sellForm').reset();
  });
  document.getElementById('sellForm').addEventListener('submit', async e => {
    e.preventDefault();
    if (!currentDetail || currentDetail.kind !== 'position') return;
    const data = new FormData(document.getElementById('sellForm'));
    const sellAt = String(data.get('sellAt') || '').trim();
    const quantity = Number(data.get('sellQuantity') || 0);
    const price = Number(data.get('sellPrice') || 0);
    if (!sellAt || !quantity || !price) {
      window.alert('매도 일시, 매도 수량, 매도 당시 주가를 모두 입력해주세요.');
      return;
    }
    const result = await apiFetch(`/api/journal/${currentDetail.id}/sell`, {
      method: 'POST',
      body: JSON.stringify({ sellAt: new Date(sellAt).toISOString(), price, quantity }),
    });
    if (result !== undefined) {
      positionDetailData = null;
      document.getElementById('sellForm').classList.add('hidden');
      document.getElementById('sellForm').reset();
      await renderPositionDetail();
      fetchList();
    }
  });

  // ── 액션 메타 위임 (매수/매도 기록 페이징) ──
  document.getElementById('actionMeta').addEventListener('click', e => {
    const navBtn = e.target.closest('button[data-record-nav]');
    if (!navBtn || !currentDetail || currentDetail.kind !== 'position' || !positionDetailData) return;
    const kind = navBtn.dataset.recordKind;
    const records = kind === 'buy' ? positionDetailData.buys : positionDetailData.sells;
    const totalPages = Math.max(1, Math.ceil(records.length / RECORD_PAGE_SIZE));
    const current = kind === 'buy' ? buyRecordsPage : sellRecordsPage;
    const next = navBtn.dataset.recordNav === 'next' ? current + 1 : current - 1;
    const clamped = Math.min(Math.max(0, next), totalPages - 1);
    if (kind === 'buy') buyRecordsPage = clamped;
    else sellRecordsPage = clamped;
    renderPositionDetail();
  });

  document.getElementById('actionMeta').addEventListener('change', e => {
    const input = e.target.closest('input.record-page-input');
    if (!input || !currentDetail || currentDetail.kind !== 'position' || !positionDetailData) return;
    const kind = input.dataset.recordKind;
    const records = kind === 'buy' ? positionDetailData.buys : positionDetailData.sells;
    const totalPages = Math.max(1, Math.ceil(records.length / RECORD_PAGE_SIZE));
    let p = parseInt(input.value, 10);
    if (isNaN(p)) p = 1;
    p = Math.min(Math.max(1, p), totalPages);
    if (kind === 'buy') buyRecordsPage = p - 1;
    else sellRecordsPage = p - 1;
    renderPositionDetail();
  });
});
