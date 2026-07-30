const YEARS = [2021, 2022, 2023, 2024, 2025];

function formatPrice(price) {
  return (price || 0).toLocaleString('ko-KR') + '원';
}

function formatMarketCap(cap) {
  if (cap == null) return '-';
  else if(Math.round(cap/1000000000000>=1)) return (Math.round((cap  /1000000000000)*10)/10).toLocaleString('ko-KR') + '조원';
  else if(Math.round(cap/100000000>=1)) return (Math.round((cap  /100000000)*10)/10).toLocaleString('ko-KR') + '억원';
  else if(cap/10000>=1) return (Math.round((cap / 10000) * 10) / 10).toLocaleString('ko-KR') + '만원';
  else  return Math.round(cap).toLocaleString('ko-KR') + '원';
}

function fmt2(v, suffix = '') {
  if (v == null || isNaN(Number(v))) return '-';
  return Number(v).toFixed(2) + suffix;
}

function formatChange(rate) {
  if (rate == null || isNaN(Number(rate))) return '0.00%';
  const sign = rate >= 0 ? '+' : '';
  return sign + Number(rate).toFixed(2) + '%';
}

function changeClass(rate) {
  return Number(rate) >= 0 ? 'up' : 'down';
}

function escapeHtml(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function formatNewsDate(value) {
  if (!value) return '-';
  // Jackson이 LocalDateTime을 [year, month, day, hour, minute] 배열로 직렬화하는 경우 처리
  const d = Array.isArray(value)
    ? new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0)
    : new Date(value);
  if (isNaN(d.getTime())) return '-';
  return d.toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function sortStocks(stocks, key, dir) {
  return [...stocks].sort((a, b) => {
    const av = a[key] ?? 0;
    const bv = b[key] ?? 0;
    return dir === 'asc' ? av - bv : bv - av;
  });
}


// ──────────────────────────────────────────────
// API Layer
// ──────────────────────────────────────────────

const API_BASE = "/bn";

// 목록 전체 필드 정규화 (/info/list, /info/list/filter, /info/search)
function normalizeStock(s) {
  return {
    code:          s.stock_code  || '',
    name:          s.corp_name   || '',
    price:         Number(s.mkp) || 0,
    changeRate:    Number(s.flt_rt) || 0,
    changeAmount:  0,
    marketCap:     Number(s.mrkt_tot_amt) || 0,
    per:           s.per           != null ? Number(s.per)           : null,
    pbr:           s.pbr           != null ? Number(s.pbr)           : null,
    roe:           s.roe           != null ? Number(s.roe)           : null,
    dividendYield: s.dividend_yield != null ? Number(s.dividend_yield) : null,
  };
}

// 랭킹 전용 정규화 (/info/per, /info/pbr, /info/roe, /info/dividend-yield)
function normalizeRankStock(s) {
  return {
    code:          s.stock_code || '',
    name:          s.corp_name  || '',
    price:         0,
    changeRate:    0,
    changeAmount:  0,
    marketCap:     0,
    per:           s.per            != null ? Number(s.per)            : null,
    pbr:           s.pbr            != null ? Number(s.pbr)            : null,
    roe:           s.roe            != null ? Number(s.roe)            : null,
    dividendYield: s.dividend_yield != null ? Number(s.dividend_yield) : null,
    score:         s.score          != null ? (Math.round(Number(s.score)*1000))/10          : null,
  };
}


/**
 * 시장 지표 (코스피 + 환율)
 */
async function fetchMarketIndices() {
  const [kospiRes, exchangeRes] = await Promise.allSettled([
    fetch(`${API_BASE}/info/kospi`),
    fetch(`${API_BASE}/info/exchange`),
  ]);

  const result = [];

  if (kospiRes.status === 'fulfilled' && kospiRes.value.ok) {
    const k = await kospiRes.value.json();
    result.push({
      name:         k.idxNm || 'KOSPI',
      value:        (Number(k.clsprcIdx) || 0).toLocaleString('ko-KR'),
      changeRate:   Math.round(Number(k.flucRt)*100)/100       || 0,
      changeAmount: Math.round(Number(k.cmpprevddIdx)*100)/100 || 0,
    });
  }

  if (exchangeRes.status === 'fulfilled' && exchangeRes.value.ok) {
    const ex = await exchangeRes.value.json();
    ['USD', 'JPY(100)', 'CNH'].forEach((key) => {
      const e = ex[key];
      if (!e) return;
      result.push({
        name:         `${e.curUnit || key} (${e.country || ''})`,
        value:        (Number(e.dealBasR) || 0).toLocaleString('ko-KR') + '원',
        changeRate:   Math.round(Number(e.changeRate)*100)/100   || 0,
        changeAmount: Math.round(Number(e.changeAmount)*100)/100 || 0,
      });
    });
  }

  return result;
}

/**
 * 홈 주요 종목 — /info/top10 (스코어 TOP10 기준)
 */
async function fetchFeaturedStocks() {
  const res = await fetch(`${API_BASE}/info/top10`);
  if (!res.ok) throw new Error(`fetchFeaturedStocks failed: ${res.status}`);
  const body = await res.json();
  return (body.list || []).map(normalizeStock);
}

/**
 * TOP 랭킹 타입별 조회
 * @param {"value"|"lowPer"|"highRoe"|"lowPbr"|"value"} type
 */
async function fetchTop(type = "value") {
  const urlMap = {
    lowPer:      '/info/per',
    lowPbr:      '/info/pbr',
    highRoe:     '/info/roe',
    dividendYield: '/info/dividend-yield',
  };
  const url = `${API_BASE}${urlMap[type]}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`fetchTop(${type}) failed: ${res.status}`);
  const data = await res.json();
  return data.map((s) => normalizeRankStock(s));
}

/**
 * 종목 목록 (list.js 용)
 * params: { keyword, page, size, perMin, perMax, pbrMin, pbrMax, roeMin, roeMax, divMin, divMax }
 */
async function fetchStocks(params = {}) {
  const { keyword, page = 0, size = 10,
          perMin, perMax, pbrMin, pbrMax, roeMin, roeMax, divMin, divMax,
          sort, dir } = params;

  const sortQ = sort ? `&sort=${encodeURIComponent(sort)}&dir=${dir || 'desc'}` : '';

  // 키워드 검색 (서버 페이징)
  if (keyword) {
    const res = await fetch(`${API_BASE}/info/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}${sortQ}`);
    if (!res.ok) throw new Error(`fetchStocks(search) failed: ${res.status}`);
    const body = await res.json();
    return {
      stocks:     (body.result || []).map(normalizeStock),
      totalCount: body.totalCount ?? 0,
      totalPages: body.totalPages ?? 1,
      page:       body.page      ?? page,
    };
  }

  // 필터 적용
  const hasFilter = [perMin, perMax, pbrMin, pbrMax, roeMin, roeMax, divMin, divMax]
    .some((v) => v != null);

  if (hasFilter) {
    const q = new URLSearchParams();
    if (perMin != null) q.set('perMin', perMin);
    if (perMax != null) q.set('perMax', perMax);
    if (roeMin != null) q.set('roeMin', roeMin);
    if (roeMax != null) q.set('roeMax', roeMax);
    if (pbrMin != null) q.set('pbrMin', pbrMin);
    if (pbrMax != null) q.set('pbrMax', pbrMax);
    if (divMin != null) q.set('dyMin', divMin);
    if (divMax != null) q.set('dyMax', divMax);
    q.set('page', page);
    q.set('size', size);
    if (sort) { q.set('sort', sort); q.set('dir', dir || 'desc'); }
    const res = await fetch(`${API_BASE}/info/list/filter?${q.toString()}`);
    if (!res.ok) throw new Error(`fetchStocks(filter) failed: ${res.status}`);
    const body = await res.json();
    return {
      stocks:     (body.list || []).map(normalizeStock),
      totalCount: body.totalCount ?? 0,
      totalPages: body.totalPages ?? 1,
      page:       body.page      ?? page,
    };
  }

  // 전체 목록 (서버 페이징)
  const res = await fetch(`${API_BASE}/info/list?page=${page}&size=${size}${sortQ}`);
  if (!res.ok) throw new Error(`fetchStocks(list) failed: ${res.status}`);
  const body = await res.json();
  return {
    stocks:     (body.list || []).map(normalizeStock),
    totalCount: body.totalCount ?? 0,
    totalPages: body.totalPages ?? 1,
    page:       body.page      ?? page,
  };
}

/**
 * 종목 상세 — stock_code로 검색 후 반환 (detail.js 용)
 * ※ 재무제표 상세 API 구현
 */
async function fetchStockNews(code, page = 0) {
  const res = await fetch(`${API_BASE}/api/stocks/${encodeURIComponent(code)}/news?page=${page}`);
  if (!res.ok) return { content: [], totalPages: 0, number: 0 };
  return res.json();
}

// /info/search는 종목코드가 아닌 회사명(corp_name LIKE)으로만 검색되므로 회사명을 받아 조회 후
// 응답 중 stock_code가 일치하는 행만 골라 메인/랭킹 페이지와 동일한 실시간 시세(mkp/flt_rt)를 반환 (실패 시 null)
async function fetchRealtimePrice(code, corpName) {
  try {
    const res = await fetch(`${API_BASE}/info/search?keyword=${encodeURIComponent(corpName)}&page=0&size=20`);
    if (!res.ok) return null;
    const body = await res.json();
    const row = (body.result || []).find((r) => r.stock_code === code);
    if (!row) return null;
    return normalizeStock(row);
  } catch (e) {
    return null;
  }
}

async function fetchStockFull(code) {
  const [stockRes, fsRes, newsPage] = await Promise.all([
    fetch(`${API_BASE}/api/stocks/${encodeURIComponent(code)}`),
    fetch(`${API_BASE}/api/stocks/${encodeURIComponent(code)}/financial-statements`),
    fetchStockNews(code, 0),
  ]);
  if (!stockRes.ok) throw new Error(`fetchStockFull failed: ${stockRes.status}`);

  const { company, indicator, latestPrice, priceHistory = [] } = await stockRes.json();
  const statements = fsRes.ok ? await fsRes.json() : [];
  const news = newsPage.content;
  const newsTotalPages = newsPage.totalPages;
  const realtime = await fetchRealtimePrice(code, company.corpName);

  // 현재가: 메인 페이지와 동일하게 실시간 시세 우선 사용, 실패 시 DB 종가로 폴백
  const price = realtime?.price || Number(latestPrice?.clpr) || 0;
  const changeRate = realtime ? realtime.changeRate : Number(latestPrice?.fltRt) || 0;

  // 등락액: 실시간가 - 전일 종가 (실시간 조회 실패 시 최근 2개 종가 차이로 폴백)
  const prevClose = priceHistory.length >= 2 ? priceHistory[priceHistory.length - 2].clpr : null;
  const changeAmount = realtime && prevClose != null
    ? price - prevClose
    : priceHistory.length >= 2
      ? priceHistory[priceHistory.length - 1].clpr - priceHistory[priceHistory.length - 2].clpr
      : 0;

  // 연도별 재무제표 — 연도당 1건만 사용 (가장 먼저 나오는 보고서)
  const byYear = new Map();
  statements.forEach((s) => {
    if (!byYear.has(s.bsnsYear)) byYear.set(s.bsnsYear, s);
  });
  const hasFinancials = byYear.size > 0;
  const years = hasFinancials ? [...byYear.keys()].sort() : YEARS.map(String);
  const toEok = (v) => (v == null ? 0 : v / 1e8); // 원 → 억원

  return {
  code:            company.stockCode,
    news,
    newsTotalPages,
    name:            company.corpName,
    price,
    changeRate,
    changeAmount,
    marketCap:       Number(latestPrice?.mrktTotAmt) || 0,
    per:             indicator?.per ?? null,
    pbr:             indicator?.pbr ?? null,
    roe:             indicator?.roe ?? null,
    dividendYield:   indicator?.dividendYield ?? null,
    eps:             indicator?.eps ?? null,
    bps:             indicator?.bps ?? null,
    debtRatio:       indicator?.debtRatio ?? null,
    shares:          latestPrice?.lstgStCnt != null ? Number(latestPrice.lstgStCnt).toLocaleString('ko-KR') + '주' : '-',
    ceoNm:           company.ceoNm || '-',
    indutyNm:        company.indutyNm || '-',
    market:          company.corpCls === 'Y' ? '코스피' : company.corpCls === 'K' ? '코스닥' : '-',
    operatingProfit: hasFinancials ? toEok(byYear.get(years[years.length - 1]).operatingIncome) : 0,
    years,
    revenueHistory:     years.map((y) => toEok(byYear.get(y)?.revenue)),
    operatingHistory:   years.map((y) => toEok(byYear.get(y)?.operatingIncome)),
    netIncomeHistory:   years.map((y) => toEok(byYear.get(y)?.netIncome)),
    assetHistory:       years.map((y) => toEok(byYear.get(y)?.totalAssets)),
    debtHistory:        years.map((y) => toEok(byYear.get(y)?.totalLiabilities)),
    equityHistory:      years.map((y) => toEok(byYear.get(y)?.totalEquity)),
    debtRatioHistory:   years.map((y) => {
      const s = byYear.get(y);
      return s?.totalEquity ? (s.totalLiabilities / s.totalEquity) * 100 : 0;
    }),
    // 연도별 PER/PBR/ROE/EPS/BPS는 백엔드에 연도별 지표 데이터가 없어 현재는 0으로 표시
    roeHistory:  years.map(() => 0),
    perHistory:  years.map(() => 0),
    pbrHistory:  years.map(() => 0),
    epsHistory:  years.map(() => 0),
    bpsHistory:  years.map(() => 0),
  };
}

// ──────────────────────────────────────────────
// 관심종목 (로그인 필요)
// ──────────────────────────────────────────────

async function fetchFavorites() {
  const res = await authFetch(`/api/favorites`);
  if (!res.ok) throw new Error(`fetchFavorites failed: ${res.status}`);
  const data = await res.json();
  return data.map(normalizeStock);
}

async function addFavorite(stockCode) {
  const res = await authFetch(`/api/favorites/${encodeURIComponent(stockCode)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`addFavorite failed: ${res.status}`);
}

async function removeFavorite(stockCode) {
  const res = await authFetch(`/api/favorites/${encodeURIComponent(stockCode)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`removeFavorite failed: ${res.status}`);
}
