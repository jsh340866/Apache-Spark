const ADMIN_USERS_KEY = 'valuepick.admin.users';

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

function getUserSeed() {
  return [
    { id: createId(), nickname: 'value_hunter', email: 'value@pick.io', blocked: false, role: 'user' },
    { id: createId(), nickname: 'bad_actor', email: 'bad@pick.io', blocked: true, role: 'user' },
    { id: createId(), nickname: 'ops_lead', email: 'ops@pick.io', blocked: false, role: 'admin' },
  ];
}

function loadUsers() {
  const raw = getStorageItem(ADMIN_USERS_KEY);
  if (!raw) {
    const seed = getUserSeed();
    setStorageItem(ADMIN_USERS_KEY, JSON.stringify(seed));
    return seed;
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return [];
  }
}

function saveUsers(items) {
  setStorageItem(ADMIN_USERS_KEY, JSON.stringify(items));
}

document.addEventListener('DOMContentLoaded', () => {
  initHeader('admin');
  initFooter();

  const bodyEl = document.getElementById('userTableBody');
  let users = loadUsers();

  function render() {
    if (users.length === 0) {
      bodyEl.innerHTML = '<tr><td colspan="5">유저가 없습니다.</td></tr>';
      return;
    }

    bodyEl.innerHTML = users
      .map(
        (user) => `
          <tr data-id="${user.id}">
            <td>${user.nickname}</td>
            <td>${user.email}</td>
            <td><span class="pill ${user.blocked ? 'blocked' : 'good'}">${user.blocked ? '제재' : '정상'}</span></td>
            <td>${user.role === 'admin' ? '관리자' : '일반'}</td>
            <td>
              <div class="actions">
                <button class="btn-outline" data-action="toggleRole" type="button">${user.role === 'admin' ? '권한 회수' : '관리자 부여'}</button>
                <button class="btn-danger" data-action="delete" type="button">삭제</button>
              </div>
            </td>
          </tr>
        `
      )
      .join('');
  }

  bodyEl.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-action]');
    if (!btn) return;

    const row = btn.closest('tr[data-id]');
    const id = row?.dataset.id;
    if (!id) return;

    if (btn.dataset.action === 'delete') {
      users = users.filter((item) => item.id !== id);
      saveUsers(users);
      render();
      return;
    }

    if (btn.dataset.action === 'toggleRole') {
      users = users.map((item) =>
        item.id === id ? { ...item, role: item.role === 'admin' ? 'user' : 'admin' } : item
      );
      saveUsers(users);
      render();
    }
  });

  render();
});
