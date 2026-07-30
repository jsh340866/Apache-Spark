document.addEventListener('DOMContentLoaded', async () => {
  initHeader('');
  initFooter();

  const params = new URLSearchParams(window.location.search);
  const code = params.get('code');
  const main = document.getElementById('detailMain');

  // 로딩 표시
  main.innerHTML = `
    <div class="not-found" id="loadingIndicator">
      <p style="color:var(--text-sub)">데이터를 불러오는 중입니다...</p>
    </div>
  `;

  let stock;
  try {
    await loadFavoriteState();
    stock = await fetchStockFull(code);
  } catch (e) {
    stock = null;
  }

  if (!stock) {
    main.innerHTML = `
      <div class="not-found">
        <h2>종목을 찾을 수 없습니다</h2>
        <p style="color:var(--text-sub);margin-bottom:20px">요청하신 종목 코드(${code || '없음'})가 존재하지 않습니다.</p>
        <a class="btn-primary" href="list.html">종목 리스트로 이동</a>
      </div>
    `;
    return;
  }

  document.title = `${stock.name} — ValuePick`;
  const cls = changeClass(stock.changeRate);
  const sign = stock.changeRate >= 0 ? '+' : '';
  const yearLabels = (stock.years || YEARS).map((y) => y + '년');

  main.innerHTML = `
    <button class="back-btn" id="backBtn" type="button">
      <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      돌아가기
    </button>

    <section class="summary-card">
      <div class="summary-top">
        <div class="summary-title">
          <h1>${stock.name}</h1>
          <div class="code">${stock.code}</div>
          <button class="favorite-btn${isFavorite(stock.code) ? ' active' : ''}" data-favorite-code="${stock.code}" type="button" aria-label="관심종목 ${isFavorite(stock.code) ? '해제' : '추가'}">★</button>
        </div>
        <div class="summary-price">
          <div class="price">${formatPrice(stock.price)}</div>
          <div class="change-row ${cls}">
            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
              ${stock.changeRate >= 0
                ? '<polyline points="18 15 12 9 6 15"/>'
                : '<polyline points="6 9 12 15 18 9"/>'}
            </svg>
            ${formatChange(stock.changeRate)} (${sign}${stock.changeAmount.toLocaleString()}원)
          </div>
        </div>
      </div>
      <div class="summary-metrics">
        <div class="summary-metric"><div class="label">시가총액</div><div class="value">${formatMarketCap(stock.marketCap)}</div></div>
        <div class="summary-metric"><div class="label">PER</div><div class="value">${fmt2(stock.per)}</div></div>
        <div class="summary-metric"><div class="label">PBR</div><div class="value">${fmt2(stock.pbr)}</div></div>
        <div class="summary-metric"><div class="label">ROE</div><div class="value">${fmt2(stock.roe, '%')}</div></div>
        <div class="summary-metric"><div class="label">배당수익률</div><div class="value">${fmt2(stock.dividendYield, '%')}</div></div>
        <div class="summary-metric"><div class="label">부채비율</div><div class="value">${fmt2(stock.debtRatio, '%')}</div></div>
      </div>
    </section>

    <section class="financial-section">
      <h2>재무제표 상세</h2>

      <div class="financial-block">
        <h3>회사 정보</h3>
        <div class="info-grid">
          <div class="info-card"><div class="label">회사이름</div><div class="value">${stock.name}</div></div>
          <div class="info-card"><div class="label">대표이름</div><div class="value">${stock.ceoNm}</div></div>
          <div class="info-card"><div class="label">업종구분</div><div class="value">${stock.indutyNm}</div></div>
          <div class="info-card"><div class="label">시장구분</div><div class="value">${stock.market}</div></div>
        </div>
      </div>

      <div class="financial-block">
        <h3>투자지표</h3>
        <div class="indicator-grid">
          <div class="indicator-card"><div class="label">PER (주가수익비율)</div><div class="value">${fmt2(stock.per)}</div><div class="hint">낮을수록 저평가</div></div>
          <div class="indicator-card"><div class="label">PBR (주가순자산비율)</div><div class="value">${fmt2(stock.pbr)}</div><div class="hint">낮을수록 저평가</div></div>
          <div class="indicator-card"><div class="label">ROE (자기자본이익률)</div><div class="value">${fmt2(stock.roe, '%')}</div><div class="hint">높을수록 좋음</div></div>
          <div class="indicator-card"><div class="label">배당수익률</div><div class="value">${fmt2(stock.dividendYield, '%')}</div><div class="hint">배당금/주가</div></div>
          <div class="indicator-card"><div class="label">EPS (주당순이익)</div><div class="value">${stock.eps != null ? stock.eps.toLocaleString() : '-'}원</div><div class="hint">높을수록 좋음</div></div>
          <div class="indicator-card"><div class="label">BPS (주당순자산)</div><div class="value">${stock.bps != null ? stock.bps.toLocaleString() : '-'}원</div><div class="hint">높을수록 좋음</div></div>
          <div class="indicator-card"><div class="label">부채비율</div><div class="value">${fmt2(stock.debtRatio, '%')}</div><div class="hint">낮을수록 안정적</div></div>
          <div class="indicator-card"><div class="label">영업이익</div><div class="value">${stock.operatingProfit.toLocaleString()}억</div><div class="hint">최근 연도 기준</div></div>
        </div>
      </div>

      <div class="financial-block">
        <h3>연도별 재무 데이터</h3>
        <div class="tab-header" role="tablist">
          <button class="tab-btn active" role="tab" data-tab="income">손익계산서</button>
          <button class="tab-btn" role="tab" data-tab="balance">재무상태표</button>
        </div>

        <div class="tab-panel active" id="tab-income" role="tabpanel">
          ${renderTable(['매출액', '영업이익', '순이익'], [
            { label: '매출액', data: stock.revenueHistory },
            { label: '영업이익', data: stock.operatingHistory },
            { label: '순이익', data: stock.netIncomeHistory },
          ], stock.years || YEARS)}
        </div>
        <div class="tab-panel" id="tab-balance" role="tabpanel">
          ${renderTable(['자산', '부채', '자본', '부채비율'], [
            { label: '자산', data: stock.assetHistory },
            { label: '부채', data: stock.debtHistory },
            { label: '자본', data: stock.equityHistory },
            { label: '부채비율(%)', data: stock.debtRatioHistory, suffix: '%' },
          ], stock.years || YEARS)}
        </div>
      </div>
    </section>

    <section class="chart-section">
      <h2>수익 및 자산 추이</h2>
      <div class="tab-header" id="chartTabHeader" role="tablist">
        <button class="tab-btn active" role="tab" data-chart="revenue">수익 추이</button>
        <button class="tab-btn" role="tab" data-chart="asset">자산 추이</button>
      </div>
      <div id="chart-revenue">
        <div class="chart-legend" id="revenueLegend">
          <button type="button" class="legend-item toggle active" data-legend-index="0"><span class="legend-dot" style="background:#3182f6"></span>매출액</button>
          <button type="button" class="legend-item toggle active" data-legend-index="1"><span class="legend-dot" style="background:#00c471"></span>영업이익</button>
          <button type="button" class="legend-item toggle active" data-legend-index="2"><span class="legend-dot" style="background:#f04452"></span>순이익</button>
          <button type="button" class="legend-item toggle" id="revenueLegendAll"><span class="legend-dot" style="background:#4e5968"></span>전체보기</button>
        </div>
        <canvas class="chart-canvas" id="revenueChart"></canvas>
      </div>
      <div id="chart-asset" style="display:none">
        <div class="chart-legend">
          <span class="legend-item"><span class="legend-dot" style="background:#3182f6"></span>자산</span>
          <span class="legend-item"><span class="legend-dot" style="background:#f04452"></span>부채</span>
          <span class="legend-item"><span class="legend-dot" style="background:#00c471"></span>자본</span>
        </div>
        <canvas class="chart-canvas" id="balanceChart"></canvas>
      </div>
    </section>

    <section class="news-section">
      <h2>관련 뉴스</h2>
      <div id="newsContainer">${renderNews(stock.news, 0, stock.newsTotalPages)}</div>
    </section>

  `;

  document.getElementById('backBtn').addEventListener('click', () => history.back());

  bindFavoriteButtons(main);
  bindNewsPagination(code);

  // 재무 탭 (data-tab)
  document.querySelectorAll('.tab-btn[data-tab]').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn[data-tab]').forEach((b) => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
    });
  });

  const revenueCanvas = document.getElementById('revenueChart');
  const balanceCanvas = document.getElementById('balanceChart');
  const revenueDatasets = [
    { label: '매출액', data: stock.revenueHistory, color: '#3182f6', suffix: '억' },
    { label: '영업이익', data: stock.operatingHistory, color: '#00c471', suffix: '억' },
    { label: '순이익', data: stock.netIncomeHistory, color: '#f04452', suffix: '억' },
  ];
  const balanceDatasets = [
    { label: '자산', data: stock.assetHistory, color: '#3182f6', suffix: '억' },
    { label: '부채', data: stock.debtHistory, color: '#f04452', suffix: '억' },
    { label: '자본', data: stock.equityHistory, color: '#00c471', suffix: '억' },
  ];

  // 범례 클릭 시 해당 항목만 단독 표시 (null이면 전체 표시)
  const revenueLegend = document.getElementById('revenueLegend');
  const revenueLegendAll = document.getElementById('revenueLegendAll');
  let selectedRevenueIndex = null;
  function visibleRevenueDatasets() {
    return selectedRevenueIndex == null ? revenueDatasets : [revenueDatasets[selectedRevenueIndex]];
  }
  function renderRevenueChart() {
    drawLineChart(revenueCanvas, visibleRevenueDatasets(), yearLabels);
  }
  function updateRevenueLegendActive() {
    revenueLegend.querySelectorAll('.legend-item[data-legend-index]').forEach((btn) => {
      btn.classList.toggle('active', selectedRevenueIndex == null || Number(btn.dataset.legendIndex) === selectedRevenueIndex);
    });
    revenueLegendAll.classList.toggle('active', selectedRevenueIndex == null);
  }

  renderRevenueChart();
  updateRevenueLegendActive();

  revenueLegend.querySelectorAll('.legend-item[data-legend-index]').forEach((btn) => {
    btn.addEventListener('click', () => {
      selectedRevenueIndex = Number(btn.dataset.legendIndex);
      updateRevenueLegendActive();
      renderRevenueChart();
    });
  });

  revenueLegendAll.addEventListener('click', () => {
    selectedRevenueIndex = null;
    updateRevenueLegendActive();
    renderRevenueChart();
  });

  // 차트 탭 (data-chart)
  document.querySelectorAll('.tab-btn[data-chart]').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn[data-chart]').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      const isRevenue = btn.dataset.chart === 'revenue';
      document.getElementById('chart-revenue').style.display = isRevenue ? '' : 'none';
      document.getElementById('chart-asset').style.display = isRevenue ? 'none' : '';
      if (!isRevenue) drawBarChart(balanceCanvas, balanceDatasets, yearLabels);
    });
  });

  window.addEventListener('resize', () => {
    const active = document.querySelector('.tab-btn[data-chart].active')?.dataset.chart;
    if (active === 'revenue') renderRevenueChart();
    else drawBarChart(balanceCanvas, balanceDatasets, yearLabels);
  });
});

function renderNews(news, page, totalPages) {
  if (!news || news.length === 0) {
    return '<p class="news-empty">관련 뉴스가 없습니다.</p>';
  }
  return `
    <div class="news-grid">
      ${news.map((n) => `
        <article class="news-card">
          <div class="news-press">${escapeHtml(n.press)}</div>
          <a class="news-title" href="${escapeHtml(n.link)}" target="_blank" rel="noopener noreferrer">${escapeHtml(n.title)}</a>
          <time class="news-date">${formatNewsDate(n.publishedAt)}</time>
        </article>
      `).join('')}
    </div>
    ${renderNewsPagination(page, totalPages)}
  `;
}

function renderNewsPagination(page, totalPages) {
  if (!totalPages || totalPages <= 1) return '';
  return `
    <div class="news-pagination">
      <button class="news-page-btn" data-page="${page - 1}" ${page <= 0 ? 'disabled' : ''}>이전</button>
      <span class="news-page-info">${page + 1} / ${totalPages}</span>
      <button class="news-page-btn" data-page="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>다음</button>
    </div>
  `;
}

function bindNewsPagination(code) {
  const container = document.getElementById('newsContainer');
  container.addEventListener('click', async (e) => {
    const btn = e.target.closest('.news-page-btn');
    if (!btn || btn.disabled) return;
    const page = Number(btn.dataset.page);
    const newsPage = await fetchStockNews(code, page);
    container.innerHTML = renderNews(newsPage.content, newsPage.number, newsPage.totalPages);
  });
}

function renderTable(title, rows, years) {
  const headerCells = years.map((y) => `<th>${y}</th>`).join('');
  const bodyRows = rows.map((row) => {
    const cells = row.data.map((v) => {
      if (v == null) return '<td>-</td>';
      const suffix = row.suffix || (row.label.includes('PER') || row.label.includes('PBR') ? '' : '억');
      const isDecimal = suffix === '%' || suffix === '';
      const display = isDecimal
        ? Number(v).toFixed(2)
        : Math.round(v).toLocaleString('ko-KR');
      const unit = suffix === '%' ? '%' : suffix === '억' ? '억' : '';
      return `<td>${display}${unit}</td>`;
    }).join('');
    return `<tr><td>${row.label}</td>${cells}</tr>`;
  }).join('');

  return `
    <div class="data-table-wrap">
      <table class="data-table">
        <thead><tr><th>항목</th>${headerCells}</tr></thead>
        <tbody>${bodyRows}</tbody>
      </table>
    </div>
  `;
}
