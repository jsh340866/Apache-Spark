const COMMUNITY_KEY = 'valuepick.community.posts';

// 투자일지 공유 기능은 investment-journal.js가 쓰는 실제 저장 테이블을 읽기 전용으로 그대로 사용한다.
const JOURNAL_POSITION_KEY = 'valuepick.positions.v3';
const JOURNAL_BUY_KEY = 'valuepick.buys.v3';
const JOURNAL_SELL_KEY = 'valuepick.sells.v3';
const USER_KEY = 'valuepick.userId';
const JOURNAL_VOTE_KEY = 'valuepick.community.journalVotes';
const JOURNAL_COMMENT_KEY = 'valuepick.community.journalComments';

function createId() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID();
  }
  return `id-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
}

function getStorageItem(key) {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    return null;
  }
}

function setStorageItem(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    // ignore storage failures for preview mode
  }
}

function getCurrentUserId() {
  let uid = getStorageItem(USER_KEY);
  if (!uid) {
    uid = createId();
    setStorageItem(USER_KEY, uid);
  }
  return uid;
}

function formatWon(value) {
  return Number(value || 0).toLocaleString('ko-KR') + '원';
}

function formatShares(value) {
  return Number(value || 0).toLocaleString('ko-KR') + '주';
}

function formatDateTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatShortDate(value) {
  if (!value) return '-';
  const d = new Date(value);
  if (isNaN(d.getTime())) return '-';
  const y = String(d.getFullYear()).slice(-2);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}.${m}.${day}`;
}

function createCommunitySeed() {
  return [
    {
      id: createId(),
      tab: 'free',
      title: '오늘 장 분위기 어떤가요?',
      content: '거래대금이 줄어드는데도 지수는 버티는 느낌입니다.',
      author: 'value_hunter',
      createdAt: new Date(Date.now() - 1000 * 60 * 80).toISOString(),
      comments: 4,
    },
    {
      id: createId(),
      tab: 'discussion',
      stockName: '삼성전자',
      title: '메모리 업황 회복 타이밍 토론',
      content: 'HBM 수요가 실적에 본격 반영되는 시점을 어떻게 보시나요?',
      author: 'alpha_park',
      createdAt: new Date(Date.now() - 1000 * 60 * 160).toISOString(),
      comments: 12,
    },
  ];
}

function loadCommunityPosts() {
  const raw = getStorageItem(COMMUNITY_KEY);
  if (!raw) {
    const seed = createCommunitySeed();
    setStorageItem(COMMUNITY_KEY, JSON.stringify(seed));
    return seed;
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return [];
  }
}

function saveCommunityPosts(items) {
  setStorageItem(COMMUNITY_KEY, JSON.stringify(items));
}

function loadJsonTable(key, fallback) {
  const raw = getStorageItem(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return fallback;
  }
}

function loadJournalVotes() {
  return loadJsonTable(JOURNAL_VOTE_KEY, {});
}

function saveJournalVotes(store) {
  setStorageItem(JOURNAL_VOTE_KEY, JSON.stringify(store));
}

function loadJournalComments() {
  return loadJsonTable(JOURNAL_COMMENT_KEY, {});
}

function saveJournalComments(store) {
  setStorageItem(JOURNAL_COMMENT_KEY, JSON.stringify(store));
}

function formatDateShort(value) {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initHeader('community');
  initFooter();

  let posts = loadCommunityPosts();
  let tab = 'free';
  let query = '';

  // 투자일지 원본 데이터(읽기 전용) — 매수/매도는 positionId로 내역과 연결된다.
  const journalPositions = loadJsonTable(JOURNAL_POSITION_KEY, []);
  const journalBuys = loadJsonTable(JOURNAL_BUY_KEY, []);
  const journalSells = loadJsonTable(JOURNAL_SELL_KEY, []);
  let journalVotes = loadJournalVotes();
  let journalComments = loadJournalComments();

  const listEl = document.getElementById('communityList');
  const tabsEl = document.getElementById('communityTabs');
  const searchEl = document.getElementById('communitySearchInput');

  const modalEl = document.getElementById('postModal');
  const openBtn = document.getElementById('openPostModal');
  const closeBtn = document.getElementById('closePostModal');
  const cancelBtn = document.getElementById('cancelPost');
  const formEl = document.getElementById('postForm');
  const stockField = document.getElementById('stockField');
  const tabSelectEl = formEl.elements.tab;

  const journalModalEl = document.getElementById('journalDetailModal');
  const journalDetailTitleEl = document.getElementById('journalDetailTitle');
  const journalDetailBodyEl = document.getElementById('journalDetailBody');
  const closeJournalDetailBtn = document.getElementById('closeJournalDetailModal');

  let openJournalId = null;

  function getPositionBuys(positionId) {
    return journalBuys.filter((b) => b.positionId === positionId);
  }

  function getPositionSells(positionId) {
    return journalSells.filter((s) => s.positionId === positionId);
  }

  function getBoughtQuantity(positionId) {
    return getPositionBuys(positionId).reduce((sum, b) => sum + Number(b.quantity || 0), 0);
  }

  function getSoldQuantity(positionId) {
    return getPositionSells(positionId).reduce((sum, s) => sum + Number(s.quantity || 0), 0);
  }

  function getHoldingQuantity(positionId) {
    return getBoughtQuantity(positionId) - getSoldQuantity(positionId);
  }

  function getUsedAmount(positionId) {
    return getPositionBuys(positionId).reduce((sum, b) => sum + Number(b.price || 0) * Number(b.quantity || 0), 0);
  }

  function getSoldAmount(positionId) {
    return getPositionSells(positionId).reduce((sum, s) => sum + Number(s.price || 0) * Number(s.quantity || 0), 0);
  }

  function getPnl(positionId) {
    return getSoldAmount(positionId) - getUsedAmount(positionId);
  }

  function isPositionClosed(position) {
    return getBoughtQuantity(position.id) > 0 && getHoldingQuantity(position.id) <= 0;
  }

  function getFinalSellAt(positionId) {
    return getPositionSells(positionId).reduce(
      (latest, s) => (!latest || new Date(s.sellAt) > new Date(latest) ? s.sellAt : latest),
      null
    );
  }

  function loadSharedJournals() {
    return journalPositions.filter((p) => p.isShared);
  }

  function getVoteEntry(positionId) {
    return journalVotes[positionId] || { up: 0, down: 0, voters: {} };
  }

  function toggleVote(positionId, voteType) {
    const entry = getVoteEntry(positionId);
    const uid = getCurrentUserId();
    const current = entry.voters[uid];

    if (current === voteType) {
      entry[voteType] = Math.max(0, (entry[voteType] || 0) - 1);
      delete entry.voters[uid];
    } else {
      if (current) entry[current] = Math.max(0, (entry[current] || 0) - 1);
      entry[voteType] = (entry[voteType] || 0) + 1;
      entry.voters[uid] = voteType;
    }

    journalVotes[positionId] = entry;
    saveJournalVotes(journalVotes);
  }

  function renderJournalTab() {
    const shared = loadSharedJournals().filter((item) => {
      if (!query) return true;
      const merged = `${item.title} ${item.stockName} ${item.note || ''}`.toLowerCase();
      return merged.includes(query);
    });

    if (shared.length === 0) {
      listEl.innerHTML = '<div class="empty">공유된 투자일지가 아직 없습니다.</div>';
      return;
    }

    listEl.innerHTML = shared
      .map((item) => {
        const closed = isPositionClosed(item);
        const qty = getHoldingQuantity(item.id);
        const vote = getVoteEntry(item.id);
        const uid = getCurrentUserId();
        const myVote = vote.voters[uid];
        const commentCount = (journalComments[item.id] || []).length;
        return `
          <article class="post-card" data-journal-id="${item.id}" role="button" tabindex="0">
            <div class="post-card-head">
              <div>
                <div class="post-title">${item.title}</div>
                <div class="meta">${item.stockName}(${item.stockCode || '-'}) · ${closed ? '거래완료' : `보유 ${formatShares(qty)}`} · ${formatDateShort(item.createdAt)}</div>
              </div>
            </div>
            ${item.note ? `<p class="post-body">${item.note}</p>` : ''}
            <div class="post-actions">
              <span class="meta">댓글 ${commentCount}</span>
              <div class="vote-inline">
                <button type="button" data-vote="up" data-journal-id="${item.id}" class="${myVote === 'up' ? 'active' : ''}">추천 ${vote.up || 0}</button>
                <button type="button" data-vote="down" data-journal-id="${item.id}" class="${myVote === 'down' ? 'active' : ''}">비추천 ${vote.down || 0}</button>
              </div>
            </div>
          </article>
        `;
      })
      .join('');
  }

  function renderCommunityTab() {
    const visible = posts
      .filter((item) => item.tab === tab)
      .filter((item) => {
        if (!query) return true;
        const merged = `${item.title} ${item.content} ${item.author} ${item.stockName || ''}`.toLowerCase();
        return merged.includes(query);
      })
      .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

    if (visible.length === 0) {
      listEl.innerHTML = '<div class="empty">조건에 맞는 글이 없습니다.</div>';
      return;
    }

    listEl.innerHTML = visible
      .map((item) => {
        const extra = item.stockName ? ` · ${item.stockName}` : '';
        return `
          <article class="post-card">
            <div class="post-card-head">
              <div>
                <div class="post-title">${item.title}</div>
                <div class="meta">${item.author}${extra} · ${formatDateShort(item.createdAt)} · 댓글 ${item.comments}</div>
              </div>
            </div>
            <p class="post-body">${item.content}</p>
            <div class="post-actions">
              <a class="link-btn" href="community-detail.html?id=${encodeURIComponent(item.id)}">상세 보기</a>
            </div>
          </article>
        `;
      })
      .join('');
  }

  function render() {
    if (tab === 'journal') {
      renderJournalTab();
      return;
    }
    renderCommunityTab();
  }

  function openModal() {
    modalEl.classList.add('open');
    modalEl.setAttribute('aria-hidden', 'false');
  }

  function closeModal() {
    modalEl.classList.remove('open');
    modalEl.setAttribute('aria-hidden', 'true');
    formEl.reset();
    stockField.classList.add('hidden');
  }

  function closeJournalDetailModal() {
    openJournalId = null;
    journalModalEl.classList.remove('open');
    journalModalEl.setAttribute('aria-hidden', 'true');
  }

  function renderJournalDetail() {
    const position = journalPositions.find((p) => p.id === openJournalId);
    if (!position) {
      closeJournalDetailModal();
      return;
    }

    const closed = isPositionClosed(position);
    const qty = getHoldingQuantity(position.id);
    const usedAmount = getUsedAmount(position.id);
    const vote = getVoteEntry(position.id);
    const uid = getCurrentUserId();
    const myVote = vote.voters[uid];
    const comments = journalComments[position.id] || [];

    journalDetailTitleEl.textContent = position.title;

    const buyRows = getPositionBuys(position.id)
      .slice()
      .sort((a, b) => new Date(a.buyAt) - new Date(b.buyAt))
      .map(
        (b) =>
          `<p>${formatDateTime(b.buyAt)} · ${formatShares(b.quantity)} · 주가 ${formatWon(b.price)} (총 ${formatWon(b.price * b.quantity)})</p>`
      )
      .join('');

    const sellRows = getPositionSells(position.id)
      .slice()
      .sort((a, b) => new Date(a.sellAt) - new Date(b.sellAt))
      .map(
        (s) =>
          `<p>${formatDateTime(s.sellAt)} · ${formatShares(s.quantity)} · 주가 ${formatWon(s.price)} (총 ${formatWon(s.price * s.quantity)})</p>`
      )
      .join('');

    let statusHtml;
    if (!closed) {
      const sellAllNow = qty * Number(position.priceFallback || 0);
      const currentPnl = getSoldAmount(position.id) + sellAllNow - usedAmount;
      statusHtml = `
        <div class="detail-block">
          <h3>보유 현황</h3>
          <p>보유주 수: ${formatShares(qty)}</p>
          <p>현재가: ${position.priceFallback > 0 ? formatWon(position.priceFallback) : '-'}</p>
          <p>사용 금액: ${formatWon(usedAmount)}</p>
          <p>현재 손익: ${currentPnl >= 0 ? '+' : ''}${formatWon(currentPnl)}</p>
        </div>
      `;
    } else {
      const pnl = getPnl(position.id);
      const pnlRate = usedAmount > 0 ? (pnl / usedAmount) * 100 : 0;
      statusHtml = `
        <div class="detail-block">
          <h3>거래결과</h3>
          <p>사용 금액: ${formatWon(usedAmount)}</p>
          <p>손익: ${pnl >= 0 ? '+' : ''}${formatWon(pnl)}</p>
          <p>손익률: ${pnlRate >= 0 ? '+' : ''}${pnlRate.toFixed(2)}%</p>
          <p>기간: ${formatShortDate(position.firstBuyAt)} ~ ${formatShortDate(getFinalSellAt(position.id))}</p>
        </div>
      `;
    }

    journalDetailBodyEl.innerHTML = `
      <p class="meta">${position.stockName}(${position.stockCode || '-'}) · ${closed ? '거래완료' : '보유'}</p>
      ${statusHtml}
      <div class="detail-block">
        <h3>매수 기록</h3>
        ${buyRows || '<p>매수 기록 없음</p>'}
      </div>
      <div class="detail-block">
        <h3>매도 기록</h3>
        ${sellRows || '<p>매도 기록 없음</p>'}
      </div>
      ${position.note ? `<div class="detail-block"><h3>메모</h3><p>${position.note}</p></div>` : ''}
      <div class="detail-block">
        <h3>추천</h3>
        <div class="vote-inline">
          <button type="button" id="journalDetailVoteUp" class="${myVote === 'up' ? 'active' : ''}">추천 ${vote.up || 0}</button>
          <button type="button" id="journalDetailVoteDown" class="${myVote === 'down' ? 'active' : ''}">비추천 ${vote.down || 0}</button>
        </div>
      </div>
      <div class="detail-block">
        <h3>댓글 ${comments.length}</h3>
        <div class="inline-comment-list">
          ${
            comments.length > 0
              ? comments
                  .slice()
                  .reverse()
                  .map(
                    (c) => `
                      <article class="comment-item">
                        <div class="row">${c.author} · ${formatDateShort(c.createdAt)}</div>
                        <p>${c.message}</p>
                      </article>
                    `
                  )
                  .join('')
              : '<p class="meta">아직 댓글이 없습니다.</p>'
          }
        </div>
        <form class="inline-comment-form" id="journalCommentForm">
          <textarea name="message" rows="2" placeholder="댓글을 입력하세요" required></textarea>
          <button class="btn-primary" type="submit">댓글 등록</button>
        </form>
      </div>
    `;

    document.getElementById('journalDetailVoteUp').addEventListener('click', () => {
      toggleVote(position.id, 'up');
      renderJournalDetail();
      renderJournalTab();
    });
    document.getElementById('journalDetailVoteDown').addEventListener('click', () => {
      toggleVote(position.id, 'down');
      renderJournalDetail();
      renderJournalTab();
    });
    document.getElementById('journalCommentForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const data = new FormData(e.target);
      const message = String(data.get('message') || '').trim();
      if (!message) return;

      const list = journalComments[position.id] || [];
      list.push({ id: createId(), author: 'me', message, createdAt: new Date().toISOString() });
      journalComments[position.id] = list;
      saveJournalComments(journalComments);
      renderJournalDetail();
      renderJournalTab();
    });
  }

  function openJournalDetailModal(positionId) {
    openJournalId = positionId;
    renderJournalDetail();
    journalModalEl.classList.add('open');
    journalModalEl.setAttribute('aria-hidden', 'false');
  }

  tabsEl.addEventListener('click', (e) => {
    const target = e.target.closest('button[data-tab]');
    if (!target) return;
    tabsEl.querySelectorAll('.tab').forEach((btn) => btn.classList.remove('active'));
    target.classList.add('active');
    tab = target.dataset.tab;
    render();
  });

  searchEl.addEventListener('input', () => {
    query = searchEl.value.trim().toLowerCase();
    render();
  });

  tabSelectEl.addEventListener('change', () => {
    const isDiscussion = tabSelectEl.value === 'discussion';
    stockField.classList.toggle('hidden', !isDiscussion);
    formEl.elements.stockName.required = isDiscussion;
  });

  openBtn.addEventListener('click', openModal);
  closeBtn.addEventListener('click', closeModal);
  cancelBtn.addEventListener('click', closeModal);
  modalEl.addEventListener('click', (e) => {
    if (e.target === modalEl) closeModal();
  });

  formEl.addEventListener('submit', (e) => {
    e.preventDefault();
    const data = new FormData(formEl);
    const next = {
      id: createId(),
      tab: String(data.get('tab')),
      stockName: String(data.get('stockName') || '').trim(),
      title: String(data.get('title') || '').trim(),
      content: String(data.get('content') || '').trim(),
      author: 'me',
      comments: 0,
      createdAt: new Date().toISOString(),
    };
    posts.unshift(next);
    saveCommunityPosts(posts);
    closeModal();
    render();
  });

  listEl.addEventListener('click', (e) => {
    if (tab !== 'journal') return;
    const voteBtn = e.target.closest('button[data-vote]');
    if (voteBtn) {
      toggleVote(voteBtn.dataset.journalId, voteBtn.dataset.vote);
      renderJournalTab();
      return;
    }
    const card = e.target.closest('article[data-journal-id]');
    if (card) openJournalDetailModal(card.dataset.journalId);
  });

  closeJournalDetailBtn.addEventListener('click', closeJournalDetailModal);
  journalModalEl.addEventListener('click', (e) => {
    if (e.target === journalModalEl) closeJournalDetailModal();
  });

  render();
});
