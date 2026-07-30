const COMMUNITY_KEY = 'valuepick.community.posts';
const STREAM_KEY = 'valuepick.community.streams';

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

function loadPosts() {
  try {
    return JSON.parse(getStorageItem(COMMUNITY_KEY) || '[]');
  } catch (e) {
    return [];
  }
}

function loadStreamsStore() {
  try {
    return JSON.parse(getStorageItem(STREAM_KEY) || '{}');
  } catch (e) {
    return {};
  }
}

function saveStreamsStore(items) {
  setStorageItem(STREAM_KEY, JSON.stringify(items));
}

function formatDateShort(value) {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function randomSentence() {
  const base = [
    '실적 대비 밸류에이션이 아직 매력적입니다.',
    '단기 과열 구간이라 분할 접근이 좋아 보입니다.',
    '수급이 강하지만 변동성 관리가 필요합니다.',
    '가이던스가 상향되면 재평가 가능성이 큽니다.',
  ];
  return base[Math.floor(Math.random() * base.length)];
}

function generateSeedStream(stockName) {
  return Array.from({ length: 30 }).map((_, idx) => {
    const daysAgo = idx % 35;
    return {
      id: createId(),
      author: `user_${(idx % 30) + 1}`,
      createdAt: new Date(Date.now() - daysAgo * 86400000 - idx * 1200000).toISOString(),
      text: `${stockName} 관점 ${idx + 1}. ${randomSentence()}`,
      hasAttachment: idx % 3 === 0,
      comments: [],
    };
  });
}

document.addEventListener('DOMContentLoaded', () => {
  initHeader('community');
  initFooter();

  const postDetailEl = document.getElementById('postDetail');
  const discussionPanelEl = document.getElementById('discussionPanel');
  const discussionTitleEl = document.getElementById('discussionTitle');
  const periodFilterEl = document.getElementById('periodFilter');
  const streamFormEl = document.getElementById('streamForm');
  const streamListEl = document.getElementById('streamList');

  const params = new URLSearchParams(window.location.search);
  const id = params.get('id');
  const posts = loadPosts();
  const target = posts.find((item) => item.id === id) || null;
  const streamsStore = loadStreamsStore();

  if (!target) {
    postDetailEl.innerHTML = '<h1>게시글을 찾을 수 없습니다.</h1>';
    return;
  }

  postDetailEl.innerHTML = `
    <h1>${target.title}</h1>
    <p class="meta">${target.author} · ${target.stockName ? `${target.stockName} · ` : ''}${formatDateShort(target.createdAt)}</p>
    <p class="post-body">${target.content}</p>
  `;

  let stream = streamsStore[id] || [];
  let visibleCount = 10;

  function persistStream() {
    streamsStore[id] = stream;
    saveStreamsStore(streamsStore);
  }

  function renderStream() {
    if (target.tab !== 'discussion') return;
    const filterValue = periodFilterEl.value;
    const now = Date.now();

    const filtered = stream.filter((item) => {
      if (filterValue === 'all') return true;
      const days = Number(filterValue);
      return now - new Date(item.createdAt).getTime() <= days * 86400000;
    });

    const visible = filtered.slice(0, visibleCount);
    streamListEl.innerHTML = visible
      .map(
        (item) => `
          <article class="stream-item">
            <div class="row">
              <span>${item.author}</span>
              <span>${formatDateShort(item.createdAt)}</span>
            </div>
            <p>${item.text}</p>
            ${item.hasAttachment ? '<div class="attachment">첨부자료 영역 (이미지/파일)</div>' : ''}
            <div class="stream-actions">
              <button class="btn-delete-stream" type="button" data-action="delete-stream" data-stream-id="${item.id}">삭제</button>
            </div>
            <div class="stream-comments">
              <div class="inline-comment-list">
                ${
                  item.comments && item.comments.length > 0
                    ? item.comments
                        .slice()
                        .reverse()
                        .map(
                          (comment) => `
                            <article class="comment-item">
                              <div class="row">${comment.author} · ${formatDateShort(comment.createdAt)}</div>
                              <p>${comment.message}</p>
                            </article>
                          `
                        )
                        .join('')
                    : '<p class="meta">아직 댓글이 없습니다.</p>'
                }
              </div>
              <form class="inline-comment-form" data-stream-id="${item.id}">
                <textarea name="message" rows="2" placeholder="이 스트림에 댓글 작성" required></textarea>
                <button class="btn-primary" type="submit">댓글 등록</button>
              </form>
            </div>
          </article>
        `
      )
      .join('');
  }

  if (target.tab === 'discussion') {
    discussionPanelEl.classList.remove('hidden');
    discussionTitleEl.textContent = `${target.stockName || '종목'} 토론 스트림`;
    if (stream.length === 0) {
      stream = generateSeedStream(target.stockName || '종목');
      persistStream();
    }
    renderStream();

    periodFilterEl.addEventListener('change', () => {
      visibleCount = 10;
      renderStream();
    });

    streamListEl.addEventListener('scroll', () => {
      if (streamListEl.scrollTop + streamListEl.clientHeight + 24 >= streamListEl.scrollHeight) {
        visibleCount += 10;
        renderStream();
      }
    });
    
    streamFormEl.addEventListener('submit', (e) => {
      e.preventDefault();
      const data = new FormData(streamFormEl);
      const text = String(data.get('streamText') || '').trim();
      if (!text) return;

      stream.unshift({
        id: createId(),
        author: 'me',
        createdAt: new Date().toISOString(),
        text,
        hasAttachment: false,
        comments: [],
      });
      persistStream();
      streamFormEl.reset();
      visibleCount = Math.max(visibleCount, 10);
      renderStream();
    });

    streamListEl.addEventListener('submit', (e) => {
      const form = e.target.closest('form.inline-comment-form');
      if (!form) return;
      e.preventDefault();
      const streamId = form.dataset.streamId;
      const data = new FormData(form);
      const message = String(data.get('message') || '').trim();
      if (!streamId || !message) return;

      stream = stream.map((item) =>
        item.id === streamId
          ? {
              ...item,
              comments: [
                ...(item.comments || []),
                { id: createId(), author: 'me', message, createdAt: new Date().toISOString() },
              ],
            }
          : item
      );
      persistStream();
      renderStream();
    });

    streamListEl.addEventListener('click', (e) => {
      const deleteBtn = e.target.closest('button[data-action="delete-stream"]');
      if (!deleteBtn) return;
      const streamId = deleteBtn.dataset.streamId;
      if (!streamId) return;

      const ok = window.confirm('이 토론 스트림을 삭제할까요?');
      if (!ok) return;

      stream = stream.filter((item) => item.id !== streamId);
      persistStream();
      renderStream();
    });
  }
});
