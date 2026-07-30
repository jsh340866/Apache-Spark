document.addEventListener('DOMContentLoaded', async () => {
  initHeader('ranking');
  initFooter();
  await loadFavoriteState();

  const tableBody       = document.getElementById('tableBody');
  const cardList        = document.getElementById('cardList');
  const countEl         = document.getElementById('stockCount');
  const loadingEl       = document.getElementById('loadingIndicator');
  const sentinel        = document.getElementById('scrollSentinel');

  const PAGE_SIZE = 20;
  let currentPage = 0;
  let hasNext = true;
  let loading = false;
  let totalLoaded = 0;
  const rowRefs = new Map(); // code -> { priceCell, changeCell, cardPriceEl, cardChangeEl }

  function rankClass(rank) {
    if (rank <= 3) return 'rank-num top3';
    if (rank <= 10) return 'rank-num top10';
    return 'rank-num';
  }

  function appendItems(stocks, startRank) {
    stocks.forEach((s, i) => {
      const rank = startRank + i;
      const cls = changeClass(s.changeRate);

      const tr = document.createElement('tr');
      tr.dataset.code = s.code;
      tr.innerHTML = `
        <td><span class="${rankClass(rank)}">${rank}</span></td>
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
        <td><span class="score-badge">${s.score ?? '-'}</span></td>
      `;
      tr.addEventListener('click', () => goToDetail(s.code));
      tableBody.appendChild(tr);
      const priceCell  = tr.children[2];
      const changeCell = tr.children[3];

      const card = document.createElement('article');
      card.className = 'rank-card';
      card.dataset.code = s.code;
      card.setAttribute('role', 'button');
      card.setAttribute('tabindex', '0');
      card.setAttribute('aria-label', `${s.name} 상세 보기`);
      card.innerHTML = `
        <button class="favorite-btn${isFavorite(s.code) ? ' active' : ''}" data-favorite-code="${s.code}" type="button" aria-label="관심종목 ${isFavorite(s.code) ? '해제' : '추가'}">★</button>
        <div class="rank-card-header">
          <span class="${rankClass(rank)}">${rank}</span>
          <div class="rank-card-info">
            <h3 class="stock-name">${s.name}</h3>
            <p class="stock-code">${s.code}</p>
          </div>
          <div class="rank-card-score">
            <span class="score-badge">${s.score ?? '-'}</span>
            <div class="score-label">스코어</div>
          </div>
        </div>
        <div class="rank-card-price">
          <span class="stock-price">${formatPrice(s.price)}</span>
          <span class="stock-change ${cls}">${formatChange(s.changeRate)}</span>
        </div>
        <div class="stock-metrics">
          <div class="metric-item"><div class="metric-label">시가총액</div><div class="metric-value">${formatMarketCap(s.marketCap)}</div></div>
          <div class="metric-item"><div class="metric-label">PER</div><div class="metric-value">${fmt2(s.per)}</div></div>
          <div class="metric-item"><div class="metric-label">PBR</div><div class="metric-value">${fmt2(s.pbr)}</div></div>
          <div class="metric-item"><div class="metric-label">ROE</div><div class="metric-value">${fmt2(s.roe, '%')}</div></div>
          <div class="metric-item"><div class="metric-label">배당수익률</div><div class="metric-value">${fmt2(s.dividendYield, '%')}</div></div>
        </div>
      `;
      card.addEventListener('click', () => goToDetail(s.code));
      card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); goToDetail(s.code); }
      });
      cardList.appendChild(card);


      rowRefs.set(s.code, {
        priceCell,
        changeCell,
        cardPriceEl:  card.querySelector('.stock-price'),
        cardChangeEl: card.querySelector('.stock-change'),
      });

      bindFavoriteButtons(card);
      
    });
  }

  // 이미 로드된 페이지들의 현재가/등락률만 10초마다 갱신 (스크롤 위치 유지)
  async function refreshPrices() {
    if (currentPage === 0) return;
    try {
      const pages = await Promise.all(
        Array.from({ length: currentPage }, (_, p) =>
          fetch(`${API_BASE}/info/top100?page=${p}&size=${PAGE_SIZE}`).then((r) => (r.ok ? r.json() : null))
        )
      );
      pages.forEach((body) => {
        if (!body) return;
        (body.list || []).forEach((raw) => {
          const s = normalizeStock(raw);
          const refs = rowRefs.get(s.code);
          if (!refs) return;
          const cls = changeClass(s.changeRate);
          refs.priceCell.textContent = formatPrice(s.price);
          refs.changeCell.textContent = formatChange(s.changeRate);
          refs.changeCell.className = cls;
          refs.cardPriceEl.textContent = formatPrice(s.price);
          refs.cardChangeEl.textContent = formatChange(s.changeRate);
          refs.cardChangeEl.className = `stock-change ${cls}`;
        });
      });
    } catch (e) {
      console.error('가격 갱신 실패:', e);
    }
  }

  async function loadMore() {
    if (loading || !hasNext) return;
    loading = true;
    loadingEl.style.display = 'block';

    try {
      const res = await fetch(`${API_BASE}/info/top100?page=${currentPage}&size=${PAGE_SIZE}`);
      if (!res.ok) throw new Error(`top100 failed: ${res.status}`);
      const body = await res.json();

      const stocks = (body.list || []).map((s) => ({
        ...normalizeStock(s),
        score: s.score != null ? (Math.round(Number(s.score)*1000))/10 : null,
      }));

      const startRank = currentPage * PAGE_SIZE + 1;
      appendItems(stocks, startRank);

      totalLoaded += stocks.length;
      hasNext = body.hasNext;
      currentPage++;

      countEl.textContent = hasNext
        ? `${totalLoaded}개 로드됨 (스크롤하면 더 불러옵니다)`
        : `총 ${totalLoaded}개 종목 (스코어 순)`;
    } catch (e) {
      console.error('top100 로드 실패:', e);
      const msg = '<tr><td colspan="10"><div class="empty-state">데이터를 불러올 수 없습니다.</div></td></tr>';
      if (totalLoaded === 0) tableBody.innerHTML = msg;
    } finally {
      loading = false;
      loadingEl.style.display = 'none';
    }
  }

  // IntersectionObserver로 무한스크롤
  const observer = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) loadMore();
  }, { rootMargin: '200px' });

  observer.observe(sentinel);

  setInterval(refreshPrices, 10000);
});
