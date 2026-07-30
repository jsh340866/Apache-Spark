document.addEventListener('DOMContentLoaded', async () => {
  initHeader('list');
  initFooter();
  await loadFavoriteState();

  let stocks = [];
  let sortKey = null;
  let sortDir = 'desc';
  let filters = {};
  let currentPage = 0;
  const PAGE_SIZE = 10;
  let _totalPages = 1;
  let _totalCount = 0;

  const params = new URLSearchParams(window.location.search);
  const searchQuery = params.get('q')?.toLowerCase() || '';
  if (searchQuery) {
    document.getElementById('headerSearch').value = searchQuery;
  }

  const sortParam = params.get('sort');
  const dirParam  = params.get('dir');
  if (sortParam) {
    sortKey = sortParam;
    sortDir = dirParam === 'asc' ? 'asc' : 'desc';
  }

  const countEl = document.getElementById('stockCount');
  const tableBody = document.getElementById('tableBody');
  const cardList = document.getElementById('cardList');
  const filterBtn = document.getElementById('filterBtn');
  const filterPanel = document.getElementById('filterPanel');
  const resetFilterBtn = document.getElementById('resetFilterBtn');

  // API에서 종목 목록 가져오기 (keyword, market 파라미터 지원)
  async function loadStocks({ market, background } = {}) {
    if (!background) {
      tableBody.innerHTML = `<tr><td colspan="8"><div class="empty-state">종목 데이터를 불러오는 중...</div></td></tr>`;
    }
    try {
      const apiParams = { page: currentPage, size: PAGE_SIZE };
      if (searchQuery) apiParams.keyword = searchQuery;
      if (market) apiParams.market = market;
      if (sortKey) { apiParams.sort = sortKey; apiParams.dir = sortDir; }
      if (filters.perMin != null) apiParams.perMin = filters.perMin;
      if (filters.perMax != null) apiParams.perMax = filters.perMax;
      if (filters.pbrMin != null) apiParams.pbrMin = filters.pbrMin;
      if (filters.pbrMax != null) apiParams.pbrMax = filters.pbrMax;
      if (filters.roeMin != null) apiParams.roeMin = filters.roeMin;
      if (filters.roeMax != null) apiParams.roeMax = filters.roeMax;
      if (filters.divMin != null) apiParams.divMin = filters.divMin;
      if (filters.divMax != null) apiParams.divMax = filters.divMax;
      const { stocks: fetched, totalCount, totalPages } = await fetchStocks(apiParams);
      stocks = fetched;
      _totalCount = totalCount;
      _totalPages = totalPages;
    } catch (e) {
      console.error('API 호출 실패:', e);
      stocks = [];
      _totalCount = 0;
      _totalPages = 1;
    }
  }

  function applyFilters() {
    let result = [...stocks];

    const ranges = [
      { key: 'per', min: 'perMin', max: 'perMax' },
      { key: 'pbr', min: 'pbrMin', max: 'pbrMax' },
      { key: 'roe', min: 'roeMin', max: 'roeMax' },
      { key: 'dividendYield', min: 'divMin', max: 'divMax' },
      { key: 'marketCap', min: 'capMin', max: 'capMax' },
    ];

    ranges.forEach(({ key, min, max }) => {
      if (filters[min] != null) result = result.filter((s) => s[key] >= filters[min]);
      if (filters[max] != null) result = result.filter((s) => s[key] <= filters[max]);
    });

    return result;
  }

  function activeFilterCount() {
    return Object.values(filters).filter((v) => v != null && v !== '').length;
  }

  function updateFilterBtn() {
    const count = activeFilterCount();
    filterBtn.innerHTML = count > 0
      ? `필터 (${count}) <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>`
      : `필터 <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>`;
    resetFilterBtn.style.display = count > 0 ? 'inline-block' : 'none';
  }

  function renderTable(data) {
    countEl.textContent = `총 ${data.length}개 종목`;

    if (data.length === 0) {
      tableBody.innerHTML = `<tr><td colspan="8"><div class="empty-state">조건에 맞는 종목이 없습니다.</div></td></tr>`;
      cardList.innerHTML = `<div class="empty-state">조건에 맞는 종목이 없습니다.</div>`;
      return;
    }

    tableBody.innerHTML = data.map((s) => {
      const cls = changeClass(s.changeRate);
      return `
        <tr data-code="${s.code}">
          <td>
            <div class="td-name">${s.name}</div>
            <div class="td-code">${s.code}</div>
          </td>
          <td>${formatPrice(s.price)}</td>
          <td class="${cls}">${formatChange(s.changeRate)}</td>
          <td>${formatMarketCap(s.marketCap)}</td>
          <td>${fmt2(s.per)}</td>
          <td>${fmt2(s.pbr)}</td>
          <td>${fmt2(s.roe, '%')}</td>
          <td>${fmt2(s.dividendYield, '%')}</td>
        </tr>
      `;
    }).join('');

    cardList.innerHTML = data.map(renderStockCard).join('');

    tableBody.querySelectorAll('tr[data-code]').forEach((row) => {
      row.addEventListener('click', () => goToDetail(row.dataset.code));
    });
    bindStockCards(cardList);
  }

  function renderPagination(totalPages, activePage) {
    const el = document.getElementById('pagination');
    if (!el) return;
    if (totalPages <= 1) { el.innerHTML = ''; return; }

    const GROUP = 10;
    const groupStart = Math.floor(activePage / GROUP) * GROUP;
    const groupEnd = Math.min(groupStart + GROUP, totalPages);

    let html = '';
    if (activePage > 0) html += `<button class="page-btn" data-page="${activePage - 1}">이전</button>`;
    for (let i = groupStart; i < groupEnd; i++) {
      html += `<button class="page-btn${i === activePage ? ' active' : ''}" data-page="${i}">${i + 1}</button>`;
    }
    if (activePage < totalPages - 1) html += `<button class="page-btn" data-page="${activePage + 1}">다음</button>`;

    el.innerHTML = html;
    el.querySelectorAll('.page-btn[data-page]').forEach(btn => {
      btn.addEventListener('click', async () => {
        currentPage = parseInt(btn.dataset.page);
        await loadStocks();
        render();
      });
    });
  }

  function render() {
    const filtered = applyFilters();
    renderTable(filtered);
    updateFilterBtn();
    renderPagination(_totalPages, currentPage);
  }

  // 현재가/등락률만 in-place로 갱신 (테이블 전체를 다시 그리지 않아 깜빡임 없음)
  // 대상 행/카드가 하나라도 없으면(페이지 구성이 바뀐 경우) false를 반환해 전체 재렌더링으로 폴백
  function patchPrices(data) {
    for (const s of data) {
      const row = tableBody.querySelector(`tr[data-code="${s.code}"]`);
      const card = cardList.querySelector(`.stock-card[data-code="${s.code}"]`);
      if (!row || !card) return false;

      const cls = changeClass(s.changeRate);
      row.children[1].textContent = formatPrice(s.price);
      row.children[2].textContent = formatChange(s.changeRate);
      row.children[2].className = cls;

      const priceEl = card.querySelector('.stock-price');
      const changeEl = card.querySelector('.stock-change');
      if (priceEl) priceEl.textContent = formatPrice(s.price);
      if (changeEl) {
        changeEl.textContent = formatChange(s.changeRate);
        changeEl.className = `stock-change ${cls}`;
      }
    }
    return true;
  }

  // 필터 토글
  filterBtn.addEventListener('click', () => {
    filterPanel.classList.toggle('open');
    filterBtn.classList.toggle('open');
  });

  document.getElementById('applyFilter').addEventListener('click', async () => {
    filters = {};
    ['perMin', 'perMax', 'pbrMin', 'pbrMax', 'roeMin', 'roeMax', 'divMin', 'divMax', 'capMin', 'capMax'].forEach((id) => {
      const el = document.getElementById(id);
      const val = parseFloat(el.value);
      if (!isNaN(val)) filters[id] = val;
    });

    // market 필터 확인 (필터 패널에 market 선택 요소가 있는 경우 반영)
    const marketEl = document.getElementById('marketFilter');
    const marketFilter = marketEl ? marketEl.value : '';

    filterPanel.classList.remove('open');
    filterBtn.classList.remove('open');
    currentPage = 0;

    await loadStocks({ market: marketFilter || undefined });
    render();
  });

  document.getElementById('cancelFilter').addEventListener('click', () => {
    filterPanel.classList.remove('open');
    filterBtn.classList.remove('open');
  });

  resetFilterBtn.addEventListener('click', async () => {
    filters = {};
    document.querySelectorAll('.filter-panel input').forEach((el) => { el.value = ''; });
    const marketEl = document.getElementById('marketFilter');
    if (marketEl) marketEl.value = '';

    currentPage = 0;
    await loadStocks();
    render();
  });

  // 정렬 (서버 재조회)
  document.querySelectorAll('.stock-table th[data-sort]').forEach((th) => {
    th.addEventListener('click', async () => {
      const key = th.dataset.sort;
      if (sortKey === key) {
        sortDir = sortDir === 'asc' ? 'desc' : 'asc';
      } else {
        sortKey = key;
        sortDir = 'desc';
      }
      document.querySelectorAll('.stock-table th').forEach((h) => h.classList.remove('sorted'));
      th.classList.add('sorted');
      currentPage = 0;
      await loadStocks();
      render();
    });
  });

  // 초기 데이터 로드 후 렌더링
  await loadStocks();
  render();

  if (sortKey) {
    const th = document.querySelector(`.stock-table th[data-sort="${sortKey}"]`);
    if (th) th.classList.add('sorted');
  }

  // 10초마다 현재가/등락률 갱신을 위해 재조회 (배경 재조회 — 로딩 표시/전체 재렌더링 없이 패치)
  setInterval(async () => {
    await loadStocks({ background: true });
    const filtered = applyFilters();
    if (!patchPrices(filtered)) render();
  }, 10000);
});
