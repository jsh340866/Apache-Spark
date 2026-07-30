/** 공통 헤더, 검색, 유틸리티 */

function safeStorageGet(key) {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    return null;
  }
}

function safeStorageRemove(key) {
  try {
    localStorage.removeItem(key);
  } catch (e) {
    // ignore storage failures in restricted contexts
  }
}

function renderHeader(activePage) {
  const navItems = [
    { href: 'index.html', label: '홈', key: 'home' },
    { href: 'list.html', label: '종목리스트', key: 'list' },
    { href: 'rank.html', label: '랭킹', key: 'ranking' },
    { href: 'investment-journal.html', label: '투자일지', key: 'journal' },
    // 1차 배포에서는 미노출 - 추후 활성화 예정
    // { href: 'community.html', label: '커뮤니티', key: 'community' },
    // { href: 'admin.html', label: '관리자', key: 'admin' },
    { href: 'favorites.html', label: '관심종목', key: 'watchlist' },
  ];

  const navHtml = navItems
    .map(
      (item) =>
        `<a class="nav-link${activePage === item.key ? ' active' : ''}" href="${item.href}">${item.label}</a>`
    )
    .join('');

  return `
    <header class="header">
      <div class="header-inner">
        <button class="menu-toggle" id="menuToggle" aria-label="메뉴 열기">
          <svg width="24" height="24" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/>
          </svg>
        </button>
        <a class="brand" href="index.html">ValuePick</a>
        <nav class="nav" id="mainNav">${navHtml}</nav>
        <div class="header-right">
          <div class="search-wrap">
            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
            <input type="text" class="search-input" id="headerSearch" placeholder="종목명 또는 종목코드 검색" aria-label="종목 검색" />
          </div>
          ${localStorage.getItem('accessToken')
            ? `<a class="btn-text" href="mypage.html">마이페이지</a><button class="btn-text" id="logoutBtn">로그아웃</button>`
            : `<a class="btn-text" href="login.html">로그인</a><a class="btn-primary" href="register.html">회원가입</a>`
          }
        </div>
      </div>
    </header>
  `;
}

function renderFooter() {
  return `
    <footer class="footer">
      <div class="footer-inner">
        <div class="footer-grid">
          <div class="footer-col">
            <div class="footer-brand">ValuePick</div>
            <p class="footer-desc">가치투자 지표 기반<br />종목 스크리닝 서비스</p>
          </div>

          <div class="footer-col">
            <div class="footer-heading">서비스</div>
            <ul class="footer-list">
              <li><a href="list.html">종목 리스트</a></li>
              <li><a href="investment-journal.html">투자일지</a></li>
              <li><a href="mypage.html">마이페이지</a></li>
            </ul>
          </div>

          <div class="footer-col">
            <div class="footer-heading">데이터 출처</div>
            <ul class="footer-list footer-list--plain">
              <li>DART (금융감독원 전자공시)</li>
              <li>KRX (한국거래소)</li>
              <li>공공데이터포털</li>
              <li>한국수출입은행 (환율)</li>
            </ul>
          </div>
        </div>

        <div class="footer-bottom">
          <p class="footer-disclaimer">
            ⚠ 본 서비스는 투자 참고용 정보만을 제공하며, 제공된 정보는 투자 권유가 아닙니다.
            투자 판단 및 결과에 대한 책임은 전적으로 투자자 본인에게 있습니다.
          </p>
          <p class="footer-copyright">© 2025 ValuePick. All rights reserved.</p>
        </div>
      </div>
    </footer>
  `;
}

function initFooter() {
  const placeholder = document.getElementById('footer-placeholder');
  if (placeholder) {
    placeholder.outerHTML = renderFooter();
  }
}

function initHeader(activePage) {
  const placeholder = document.getElementById('header-placeholder');
  if (placeholder) {
    placeholder.outerHTML = renderHeader(activePage);
  }

  const toggle = document.getElementById('menuToggle');
  const nav = document.getElementById('mainNav');
  toggle?.addEventListener('click', () => nav?.classList.toggle('open'));

  document.getElementById('logoutBtn')?.addEventListener('click', () => {
    safeStorageRemove('accessToken');
    safeStorageRemove('refreshToken');
    window.location.href = 'login.html';
  });

  const searchInput = document.getElementById('headerSearch');
  searchInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      const q = searchInput.value.trim();
      if (q) {
        window.location.href = `list.html?q=${encodeURIComponent(q)}`;
      }
    }
  });

  // 뒤로가기로 bfcache에서 페이지가 복원되면 DOMContentLoaded가 재실행되지 않아
  // 다른 페이지에서 바뀐 관심종목 상태가 별 버튼에 반영되지 않는 문제를 보정
  window.addEventListener('pageshow', async (e) => {
    if (!e.persisted) return;
    await loadFavoriteState();
    document.querySelectorAll('.favorite-btn[data-favorite-code]').forEach((btn) => {
      const active = isFavorite(btn.dataset.favoriteCode);
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-label', `관심종목 ${active ? '해제' : '추가'}`);
    });
  });
}

async function authFetch(url, options = {}) {
  const base = typeof API_BASE !== 'undefined' ? API_BASE : '';
  const token = localStorage.getItem('accessToken');

  const res = await fetch(base + url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
      ...options.headers,
    },
  });

  if (res.status === 401) {
    const body = await res.json().catch(() => ({}));

    if (body.error === 'ACCESS_TOKEN_EXPIRED') {
      const refreshToken = localStorage.getItem('refreshToken');
      if (!refreshToken) {
        localStorage.removeItem('accessToken');
        window.location.href = 'login.html';
        return;
      }

      const refreshRes = await fetch(base + '/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });

      if (!refreshRes.ok) {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        window.location.href = 'login.html';
        return;
      }

      const { accessToken, refreshToken: newRefreshToken } = await refreshRes.json();
      localStorage.setItem('accessToken', accessToken);
      localStorage.setItem('refreshToken', newRefreshToken);

      return fetch(base + url, {
        ...options,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
          ...options.headers,
        },
      });
    }

    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    window.location.href = 'login.html';
    return;
  }

  return res;
}

function goToDetail(code) {
  window.location.href = `detail.html?code=${code}`;
}

// ──────────────────────────────────────────────
// 관심종목 상태 (페이지 로드 시 loadFavoriteState로 한 번 채워서 캐싱)
// ──────────────────────────────────────────────

let _favoriteCodes = new Set();

function isFavorite(code) {
  return _favoriteCodes.has(code);
}

async function loadFavoriteState() {
  if (!localStorage.getItem('accessToken')) {
    _favoriteCodes = new Set();
    return;
  }
  try {
    const favorites = await fetchFavorites();
    _favoriteCodes = new Set(favorites.map((s) => s.code));
  } catch (e) {
    _favoriteCodes = new Set();
  }
}

// onDone(code, active) - 토글 직후(낙관적 업데이트) 호출되는 콜백 (선택)
// 서버 응답을 기다리지 않고 UI/캐시를 먼저 갱신해 클릭 반응성을 높이고, 실패 시에만 원상복구함
async function toggleFavorite(code, btn, onDone) {
  if (!localStorage.getItem('accessToken')) {
    window.location.href = 'login.html';
    return;
  }
  const wasActive = isFavorite(code);

  if (wasActive) {
    _favoriteCodes.delete(code);
  } else {
    _favoriteCodes.add(code);
  }
  if (btn) {
    btn.classList.toggle('active', !wasActive);
    btn.setAttribute('aria-label', `관심종목 ${!wasActive ? '해제' : '추가'}`);
  }
  onDone?.(code, !wasActive);

  try {
    if (wasActive) {
      await removeFavorite(code);
    } else {
      await addFavorite(code);
    }
  } catch (e) {
    console.error('관심종목 처리 실패:', e);
    // 서버 반영 실패 시 UI/캐시 원상복구
    if (wasActive) {
      _favoriteCodes.add(code);
    } else {
      _favoriteCodes.delete(code);
    }
    if (btn) {
      btn.classList.toggle('active', wasActive);
      btn.setAttribute('aria-label', `관심종목 ${wasActive ? '해제' : '추가'}`);
    }
    onDone?.(code, wasActive);
  }
}

function bindFavoriteButtons(container, onDone) {
  container?.querySelectorAll('.favorite-btn[data-favorite-code]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleFavorite(btn.dataset.favoriteCode, btn, onDone);
    });
  });
}

function renderStockCard(stock, rank) {
  const cls = changeClass(stock.changeRate);
  const rankBadge = rank != null
    ? `<span class="rank-num${rank <= 3 ? ' top3' : ''}" style="margin-right:10px;flex-shrink:0">${rank}</span>`
    : '';
  const active = isFavorite(stock.code);
  return `
    <article class="stock-card" data-code="${stock.code}" role="button" tabindex="0" aria-label="${stock.name} 상세 보기">
      <button class="favorite-btn${active ? ' active' : ''}" data-favorite-code="${stock.code}" type="button" aria-label="관심종목 ${active ? '해제' : '추가'}">★</button>
      <div class="stock-card-header">
        <div style="display:flex;align-items:center">
          ${rankBadge}
          <div>
            <h3 class="stock-name">${stock.name}</h3>
            <p class="stock-code">${stock.code}</p>
          </div>
        </div>
        <div class="stock-price-wrap">
          <div class="stock-price">${formatPrice(stock.price)}</div>
          <div class="stock-change ${cls}">${formatChange(stock.changeRate)}</div>
        </div>
      </div>
      <div class="stock-metrics">
        <div class="metric-item"><div class="metric-label">시가총액</div><div class="metric-value">${formatMarketCap(stock.marketCap)}</div></div>
        <div class="metric-item"><div class="metric-label">PER</div><div class="metric-value">${fmt2(stock.per)}</div></div>
        <div class="metric-item"><div class="metric-label">PBR</div><div class="metric-value">${fmt2(stock.pbr)}</div></div>
        <div class="metric-item"><div class="metric-label">ROE</div><div class="metric-value">${fmt2(stock.roe, '%')}</div></div>
        <div class="metric-item"><div class="metric-label">배당수익률</div><div class="metric-value">${fmt2(stock.dividendYield, '%')}</div></div>
      </div>
    </article>
  `;
}

function bindStockCards(container, onFavoriteDone) {
  bindFavoriteButtons(container, onFavoriteDone);
  container?.querySelectorAll('.stock-card, .rank-item[data-code]').forEach((el) => {
    const code = el.dataset.code;
    el.addEventListener('click', () => goToDetail(code));
    el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        goToDetail(code);
      }
    });
  });
}

function drawLineChart(canvas, datasets, labels) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);

  const w = rect.width;
  const h = rect.height;
  const pad = { top: 32, right: 20, bottom: 36, left: 56 };
  const chartW = w - pad.left - pad.right;
  const chartH = h - pad.top - pad.bottom;

  const allValues = datasets.flatMap((d) => d.data);
  const maxVal = Math.max(...allValues, 0) * 1.1;
  const minVal = Math.min(...allValues, 0);
  const valueRange = (maxVal - minVal) || 1;

  ctx.clearRect(0, 0, w, h);

  // grid
  ctx.strokeStyle = '#e5e8eb';
  ctx.lineWidth = 1;
  ctx.setLineDash([4, 4]);
  for (let i = 0; i <= 4; i++) {
    const y = pad.top + (chartH / 4) * i;
    ctx.beginPath();
    ctx.moveTo(pad.left, y);
    ctx.lineTo(w - pad.right, y);
    ctx.stroke();
    const val = maxVal - (valueRange / 4) * i;
    ctx.setLineDash([]);
    ctx.fillStyle = '#8b95a1';
    ctx.font = '11px Pretendard, sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(Math.round(val).toLocaleString(), pad.left - 8, y + 4);
    ctx.setLineDash([4, 4]);
  }
  ctx.setLineDash([]);

  // 축 단위 표기 (억원)
  ctx.fillStyle = '#8b95a1';
  ctx.font = '11px Pretendard, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('(단위: 억)', pad.left, pad.top - 14);

  // x labels
  labels.forEach((label, i) => {
    const x = pad.left + (chartW / (labels.length - 1)) * i;
    ctx.fillStyle = '#8b95a1';
    ctx.font = '11px Pretendard, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(label, x, h - 10);
  });

  // lines
  datasets.forEach((ds, dsi) => {
    ctx.strokeStyle = ds.color;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ds.data.forEach((val, i) => {
      const x = pad.left + (chartW / (labels.length - 1)) * i;
      const y = pad.top + chartH - ((val - minVal) / valueRange) * chartH;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();

    ds.data.forEach((val, i) => {
      const x = pad.left + (chartW / (labels.length - 1)) * i;
      const y = pad.top + chartH - ((val - minVal) / valueRange) * chartH;
      ctx.fillStyle = ds.color;
      ctx.beginPath();
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
    });
  });
}

function drawBarChart(canvas, datasets, labels) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);

  const w = rect.width;
  const h = rect.height;
  const pad = { top: 32, right: 20, bottom: 36, left: 56 };
  const chartW = w - pad.left - pad.right;
  const chartH = h - pad.top - pad.bottom;

  const allValues = datasets.flatMap((d) => d.data);
  const maxVal = Math.max(...allValues, 0) * 1.1;
  const minVal = Math.min(...allValues, 0);
  const valueRange = (maxVal - minVal) || 1;
  const zeroY = pad.top + chartH - ((0 - minVal) / valueRange) * chartH;

  ctx.clearRect(0, 0, w, h);

  // grid
  ctx.strokeStyle = '#e5e8eb';
  ctx.lineWidth = 1;
  ctx.setLineDash([4, 4]);
  for (let i = 0; i <= 4; i++) {
    const y = pad.top + (chartH / 4) * i;
    ctx.beginPath();
    ctx.moveTo(pad.left, y);
    ctx.lineTo(w - pad.right, y);
    ctx.stroke();
    const val = maxVal - (valueRange / 4) * i;
    ctx.setLineDash([]);
    ctx.fillStyle = '#8b95a1';
    ctx.font = '11px Pretendard, sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(Math.round(val).toLocaleString(), pad.left - 8, y + 4);
    ctx.setLineDash([4, 4]);
  }
  ctx.setLineDash([]);

  // 축 단위 표기 (억원)
  ctx.fillStyle = '#8b95a1';
  ctx.font = '11px Pretendard, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('(단위: 억)', pad.left, pad.top - 14);

  const groupW = chartW / labels.length;
  const barW = groupW / (datasets.length + 1);

  labels.forEach((label, i) => {
    const gx = pad.left + groupW * i + groupW / 2;
    ctx.fillStyle = '#8b95a1';
    ctx.font = '11px Pretendard, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(label, gx, h - 10);

    datasets.forEach((ds, di) => {
      const val = ds.data[i];
      const barTopY = pad.top + chartH - ((val - minVal) / valueRange) * chartH;
      const y = Math.min(barTopY, zeroY);
      const barH = Math.abs(barTopY - zeroY);
      const x = pad.left + groupW * i + barW * (di + 0.5);
      ctx.fillStyle = ds.color;
      ctx.beginPath();
      ctx.roundRect(x, y, barW * 0.8, barH, [3, 3, 0, 0]);
      ctx.fill();
    });
  });
}

