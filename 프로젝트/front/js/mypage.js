document.addEventListener('DOMContentLoaded', () => {
  if (!localStorage.getItem('accessToken')) {
    window.location.href = 'login.html';
    return;
  }

  initHeader('');
  initFooter();
  initMenu();
  loadProfile();
  loadWatchlist();
  loadJournal();
  initProfileForm();
  initPasswordForm();
  initLogout();
  initWithdraw();
});

// ── 메뉴 전환 ──────────────────────────────────────
function initMenu() {
  const items = document.querySelectorAll('.side-nav-item[data-menu]');
  const sections = {
    watchlist: document.getElementById('section-watchlist'),
    journal:   document.getElementById('section-journal'),
    profile:   document.getElementById('section-profile'),
    password:  document.getElementById('section-password'),
  };

  items.forEach(item => {
    item.addEventListener('click', () => {
      items.forEach(i => i.classList.remove('active'));
      item.classList.add('active');

      Object.values(sections).forEach(s => s.style.display = 'none');
      sections[item.dataset.menu].style.display = 'block';
    });
  });
}

// ── 프로필 불러오기 ────────────────────────────────
async function loadProfile() {
  const res = await authFetch('/api/users/me');
  if (!res) return;

  const data = await res.json();

  const initial = (data.nickname || data.email || '?')[0].toUpperCase();
  document.getElementById('profileAvatar').textContent = initial;
  document.getElementById('profileNickname').textContent = data.nickname;
  document.getElementById('profileEmail').textContent = data.email;
  document.getElementById('profileSince').textContent =
    '가입일 : ' + data.createdAt.slice(0, 10).replace(/-/g, '. ');

  const nicknameInput = document.getElementById('nicknameInput');
  nicknameInput.value = data.nickname;
  document.getElementById('emailInput').value = data.email;
  document.getElementById('nicknameCount').textContent = `${data.nickname.length}/20자`;
}

// ── 프로필 수정 ────────────────────────────────────
function initProfileForm() {
  const nicknameInput = document.getElementById('nicknameInput');
  nicknameInput.addEventListener('input', () => {
    document.getElementById('nicknameCount').textContent = `${nicknameInput.value.length}/20자`;
  });

  document.getElementById('profileForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const nickname = nicknameInput.value.trim();
    if (!nickname) return;

    const btn = document.getElementById('profileSaveBtn');
    btn.disabled = true;

    const res = await authFetch('/api/users/me', {
      method: 'PUT',
      body: JSON.stringify({ nickname }),
    });
    if (!res) return;

    if (res.ok) {
      const data = await res.json();
      document.getElementById('profileNickname').textContent = data.nickname;
      document.getElementById('profileAvatar').textContent = data.nickname[0].toUpperCase();
      showAlert('profileAlert', 'profileAlertMsg', '닉네임이 변경되었습니다.', 'success');
    } else {
      const data = await res.json().catch(() => ({}));
      showAlert('profileAlert', 'profileAlertMsg', data.message || '저장에 실패했습니다.', 'error');
    }

    btn.disabled = false;
  });
}

// ── 비밀번호 변경 ──────────────────────────────────
function initPasswordForm() {
  const newPw = document.getElementById('newPassword');
  const confirmPw = document.getElementById('confirmPassword');
  const confirmError = document.getElementById('confirmError');

  confirmPw.addEventListener('input', () => {
    if (confirmPw.value && newPw.value !== confirmPw.value) {
      confirmError.textContent = '비밀번호가 일치하지 않습니다.';
      confirmError.classList.add('visible');
    } else {
      confirmError.classList.remove('visible');
    }
  });

  document.getElementById('passwordForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const currentPassword = document.getElementById('currentPassword').value;
    const newPassword = newPw.value;
    const confirmPassword = confirmPw.value;

    if (newPassword !== confirmPassword) return;
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(newPassword)) {
      showAlert('passwordAlert', 'passwordAlertMsg', '비밀번호는 영문+숫자 조합 8자 이상이어야 합니다.', 'error');
      return;
    }

    const btn = document.getElementById('passwordSaveBtn');
    btn.disabled = true;

    const res = await authFetch('/api/users/me/password', {
      method: 'PUT',
      body: JSON.stringify({ currentPassword, newPassword }),
    });
    if (!res) return;

    if (res.ok) {
      showAlert('passwordAlert', 'passwordAlertMsg', '비밀번호가 변경되었습니다.', 'success');
      document.getElementById('passwordForm').reset();
    } else {
      const data = await res.json().catch(() => ({}));
      showAlert('passwordAlert', 'passwordAlertMsg', data.message || '변경에 실패했습니다.', 'error');
    }

    btn.disabled = false;
  });
}

// ── 로그아웃 ───────────────────────────────────────
function initLogout() {
  document.getElementById('logoutSideBtn').addEventListener('click', () => {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    window.location.href = 'login.html';
  });
}

// ── 회원탈퇴 ───────────────────────────────────────
function initWithdraw() {
  const modal = document.getElementById('withdrawModal');

  document.getElementById('withdrawBtn').addEventListener('click', () => {
    modal.style.display = 'flex';
  });

  document.getElementById('withdrawCancelBtn').addEventListener('click', () => {
    modal.style.display = 'none';
  });

  // 바깥 클릭 시 닫기
  modal.addEventListener('click', (e) => {
    if (e.target === modal) modal.style.display = 'none';
  });

  document.getElementById('withdrawConfirmBtn').addEventListener('click', async () => {
    const res = await authFetch('/api/users/me', { method: 'DELETE' });
    if (!res) return;

    if (res.ok) {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      modal.style.display = 'none';

      const msg = document.getElementById('withdrawDoneMsg');
      msg.style.display = 'flex';
      setTimeout(() => { window.location.href = 'index.html'; }, 2500);
    }
  });
}

// ── 관심종목 ───────────────────────────────────────
async function loadWatchlist() {
  const tbody = document.getElementById('watchlist-tbody');
  const table = document.getElementById('watchlist-table');
  const empty = document.getElementById('watchlist-empty');

  const res = await authFetch('/api/favorites');
  if (!res) return;
  const data = await res.json();

  if (!data.length) {
    empty.style.display = 'block';
    return;
  }

  tbody.innerHTML = data.map(s => {
    const price = Number(s.mkp || 0).toLocaleString('ko-KR');
    const rate = Number(s.flt_rt || 0);
    const cls = rate > 0 ? 'positive' : rate < 0 ? 'negative' : '';
    const sign = rate > 0 ? '+' : '';
    return `<tr>
      <td><button class="fav-remove-btn active" data-code="${s.stock_code}">★</button></td>
      <td class="td-name">${s.corp_name}</td>
      <td>${price}원</td>
      <td class="${cls}">${sign}${rate}%</td>
    </tr>`;
  }).join('');

  table.style.display = 'table';

  tbody.querySelectorAll('.fav-remove-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const code = btn.dataset.code;
      const removeRes = await authFetch(`/api/favorites/${encodeURIComponent(code)}`, { method: 'DELETE' });
      if (removeRes && removeRes.ok) {
        table.style.display = 'none';
        empty.style.display = 'none';
        tbody.innerHTML = '';
        loadWatchlist();
      }
    });
  });
}

// ── 투자일지 ───────────────────────────────────────
async function loadJournal() {
  const tbody = document.getElementById('journal-tbody');
  const table = document.getElementById('journal-table');
  const empty = document.getElementById('journal-empty');

  const res = await authFetch('/api/journal?category=hold&page=0');
  if (!res) return;
  const data = await res.json();
  const items = data.content || [];

  if (!items.length) {
    empty.style.display = 'block';
    return;
  }

  tbody.innerHTML = items.map(s => {
    const qty = s.holdingQty || 0;
    const currentPrice = s.currentPrice || 0;
    const pnl = currentPrice * qty - (s.usedAmount || 0);
    const cls = pnl > 0 ? 'positive' : pnl < 0 ? 'negative' : '';
    const sign = pnl > 0 ? '+' : '';
    return `<tr>
      <td class="td-name">${s.corpName}</td>
      <td>${qty.toLocaleString('ko-KR')}주</td>
      <td>${currentPrice.toLocaleString('ko-KR')}원</td>
      <td class="${cls}">${sign}${pnl.toLocaleString('ko-KR')}원</td>
    </tr>`;
  }).join('');

  table.style.display = 'table';
}

// ── 폼 결과 알림 표시 ──────────────────────────────
// 확인 버튼 클릭(onclick="hideAlert") 또는 3초 후 자동 사라짐
function showAlert(alertId, msgId, message, type) {
  const el = document.getElementById(alertId);
  document.getElementById(msgId).textContent = message;
  el.className = `alert ${type} visible`;
  setTimeout(() => hideAlert(alertId), 3000);
}

function hideAlert(alertId) {
  document.getElementById(alertId).classList.remove('visible');
}
