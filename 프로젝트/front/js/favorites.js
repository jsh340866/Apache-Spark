document.addEventListener('DOMContentLoaded', async () => {
  initHeader('watchlist');
  initFooter();

  if (!localStorage.getItem('accessToken')) {
    window.location.href = 'login.html';
    return;
  }

  const countEl = document.getElementById('stockCount');
  const tableBody = document.getElementById('tableBody');
  const cardList = document.getElementById('cardList');

  await loadFavoriteState();

  let stocks = [];
  try {
    stocks = await fetchFavorites();
  } catch (e) {
    console.error('관심종목 로드 실패:', e);
    countEl.textContent = '관심종목을 불러올 수 없습니다.';
    tableBody.innerHTML = `<tr><td colspan="9"><div class="empty-state">관심종목을 불러올 수 없습니다.</div></td></tr>`;
    cardList.innerHTML = '<div class="empty-state">관심종목을 불러올 수 없습니다.</div>';
    return;
  }

  render(stocks);

  function render(data) {
    countEl.textContent = `총 ${data.length}개 종목`;

    if (data.length === 0) {
      tableBody.innerHTML = `<tr><td colspan="9"><div class="empty-state">관심종목으로 등록한 종목이 없습니다.</div></td></tr>`;
      cardList.innerHTML = '<div class="empty-state">관심종목으로 등록한 종목이 없습니다.</div>';
      return;
    }

    tableBody.innerHTML = data.map((s) => {
      const cls = changeClass(s.changeRate);
      return `
        <tr data-code="${s.code}">
          <td class="td-favorite">
            <button class="favorite-btn active" data-favorite-code="${s.code}" type="button" aria-label="관심종목 해제">★</button>
          </td>
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

    cardList.innerHTML = data.map((s) => renderStockCard(s)).join('');

    tableBody.querySelectorAll('tr[data-code]').forEach((row) => {
      row.addEventListener('click', () => goToDetail(row.dataset.code));
    });

    // 관심종목 페이지에서는 별 해제를 낙관적으로 목록에서 바로 제거함
    // (서버 반영 실패 시 alert로 알리고 목록을 다시 불러와 실제 상태와 동기화)
    const onFavoriteDone = async (code, active) => {
      if (!active) {
        stocks = stocks.filter((s) => s.code !== code);
        render(stocks);
      } else if (!stocks.some((s) => s.code === code)) {
        alert('관심종목 해제에 실패했습니다. 다시 시도해주세요.');
        try {
          stocks = await fetchFavorites();
        } catch (e) {
          console.error('관심종목 재조회 실패:', e);
        }
        render(stocks);
      }
    };
    bindFavoriteButtons(tableBody, onFavoriteDone);
    bindStockCards(cardList, onFavoriteDone);
  }

  // 다른 페이지(예: 상세페이지)에서 관심종목을 변경한 뒤 뒤로가기로 bfcache 복원되면
  // DOMContentLoaded가 재실행되지 않으므로 목록을 서버에서 다시 불러와 동기화
  window.addEventListener('pageshow', async (e) => {
    if (!e.persisted) return;
    try {
      stocks = await fetchFavorites();
      render(stocks);
    } catch (err) {
      console.error('관심종목 재조회 실패:', err);
    }
  });
});
