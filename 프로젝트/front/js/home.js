document.addEventListener('DOMContentLoaded', async () => {
  initHeader('home');
  initFooter();
  await loadFavoriteState();

  // 시장 지표
  const marketGrid = document.getElementById('marketGrid');
  marketGrid.innerHTML = '<div>로딩 중...</div>';
  try {
    const indices = await fetchMarketIndices();
    marketGrid.innerHTML = indices.map((idx) => {
      const cls = changeClass(idx.changeRate);
      const sign = idx.changeRate >= 0 ? '+' : '';
      return `
        <div class="market-card">
          <div class="market-name">${idx.name}</div>
          <div class="market-value">${idx.value}</div>
          <div class="market-change ${cls}">${sign}${idx.changeRate}% (${sign}${idx.changeAmount})</div>
        </div>
      `;
    }).join('');
  } catch (err) {
    console.error('fetchMarketIndices 실패:', err);
    marketGrid.innerHTML = '';
  }

  // 주요 종목 (TOP10) — 10초마다 현재가/등락률 갱신
  const stocksGrid = document.getElementById('stocksGrid');

  async function loadFeaturedStocks(isFirst) {
    if (isFirst) stocksGrid.innerHTML = '<div>로딩 중...</div>';
    try {
      const stocks = await fetchFeaturedStocks();
      stocksGrid.innerHTML = stocks.map((s, i) => renderStockCard(s, i + 1)).join('');
      bindStockCards(stocksGrid);
    } catch (err) {
      console.error('fetchFeaturedStocks 실패:', err);
      if (isFirst) stocksGrid.innerHTML = '';
    }
  }

  await loadFeaturedStocks(true);
  setInterval(() => loadFeaturedStocks(false), 10000);

  // 랭킹
  const rankings = [
    { id: 'rankPer', key: 'per',           title: '📈 저PER 순위',     type: 'lowPer',        sortParam: 'sort=per&dir=asc'            },
    { id: 'rankPbr', key: 'pbr',           title: '📊 저PBR 순위',     type: 'lowPbr',        sortParam: 'sort=pbr&dir=asc'            },
    { id: 'rankRoe', key: 'roe',           title: '💰 고ROE 순위',     type: 'highRoe',       sortParam: 'sort=roe&dir=desc'           },
    { id: 'rankDiv', key: 'dividendYield', title: '💵 배당수익률 순위', type: 'dividendYield', sortParam: 'sort=dividendYield&dir=desc' },
  ];

  // 랭킹 섹션 로딩 표시
  rankings.forEach(({ id, title }) => {
    const el = document.getElementById(id);
    if (el) {
      el.innerHTML = `
        <div class="ranking-card">
          <h3>${title}</h3>
          <div>로딩 중...</div>
        </div>
      `;
    }
  });

  function buildRankingItems(stocks, key) {
    return stocks.slice(0, 5).map((s, i) => {
      const value = key === 'roe' || key === 'dividendYield'
        ? fmt2(s[key], '%')
        : fmt2(s[key]);
      return `
        <div class="rank-item" data-code="${s.code}" role="button" tabindex="0">
          <div class="rank-left">
            <span class="rank-num${i < 3 ? ' top3' : ''}">${i + 1}</span>
            <div>
              <div class="rank-name">${s.name}</div>
              <div class="rank-code">${s.code}</div>
            </div>
          </div>
          <div class="rank-right">
            <div class="rank-value">${value}</div>
          </div>
        </div>
      `;
    }).join('');
  }

  try {
    const [perStocks, pbrStocks, roeStocks, divStocks] = await Promise.all([
      fetchTop('lowPer'),
      fetchTop('lowPbr'),
      fetchTop('highRoe'),
      fetchTop('dividendYield'),
    ]);

    const apiResults = [perStocks, pbrStocks, roeStocks, divStocks];

    rankings.forEach(({ id, key, title, sortParam }, idx) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.innerHTML = `
        <div class="ranking-card">
          <h3>${title}</h3>
          ${buildRankingItems(apiResults[idx], key)}
          <div class="rank-footer">
            <a class="rank-more" href="list.html?${sortParam}">더보기+</a>
          </div>
        </div>
      `;
      bindStockCards(el);
    });
  } catch (err) {
    console.error('fetchTop 실패:', err);
    rankings.forEach(({ id, title }) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.innerHTML = `<div class="ranking-card"><h3>${title}</h3></div>`;
    });
  }
});
