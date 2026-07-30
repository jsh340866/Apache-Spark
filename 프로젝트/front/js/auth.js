document.addEventListener('DOMContentLoaded', () => {
  initHeader('');
  initFooter();
  const page = document.body.dataset.page;

  if (page === 'login') initLogin();
  if (page === 'register') initRegister();
});

function showAlert(message, type) {
  const box = document.getElementById('alertBox');
  box.textContent = message;
  box.className = `alert alert-${type} visible`;
}

function hideAlert() {
  const box = document.getElementById('alertBox');
  box.className = 'alert';
}

function setFieldError(id, message) {
  const input = document.getElementById(id);
  const err = document.getElementById(id + 'Error');
  if (message) {
    input?.classList.add('error');
    if (err) { err.textContent = message; err.classList.add('visible'); }
  } else {
    input?.classList.remove('error');
    err?.classList.remove('visible');
  }
}

async function apiCall(url, body) {
  const base = typeof API_BASE !== 'undefined' ? API_BASE : '';
  const res = await fetch(base + url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || '요청에 실패했습니다.');
  return data;
}

function initLogin() {
  const form = document.getElementById('loginForm');
  const btn = document.getElementById('submitBtn');

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    hideAlert();

    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;

    if (!email || !password) {
      showAlert('이메일과 비밀번호를 입력해주세요.', 'error');
      return;
    }

    btn.disabled = true;
    btn.textContent = '로그인 중...';

    try {
      const data = await apiCall('/api/auth/login', { email, password });
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
      showAlert('로그인 성공! 잠시 후 이동합니다.', 'success');
      setTimeout(() => { window.location.href = 'index.html'; }, 1000);
    } catch (err) {
      showAlert(err.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '로그인';
    }
  });
}

function initRegister() {
  const form = document.getElementById('registerForm');
  const btn = document.getElementById('submitBtn');

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    hideAlert();
    setFieldError('email', '');
    setFieldError('nickname', '');
    setFieldError('password', '');
    setFieldError('passwordConfirm', '');

    const email = document.getElementById('email').value.trim();
    const nickname = document.getElementById('nickname').value.trim();
    const password = document.getElementById('password').value;
    const passwordConfirm = document.getElementById('passwordConfirm').value;

    let valid = true;
    if (!email) { setFieldError('email', '이메일을 입력해주세요.'); valid = false; }
    if (!nickname) { setFieldError('nickname', '닉네임을 입력해주세요.'); valid = false; }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(password)) { setFieldError('password', '비밀번호는 영문+숫자 조합 8자 이상이어야 합니다.'); valid = false; }
    if (password !== passwordConfirm) { setFieldError('passwordConfirm', '비밀번호가 일치하지 않습니다.'); valid = false; }
    if (!valid) return;

    btn.disabled = true;
    btn.textContent = '가입 중...';

    try {
      await apiCall('/api/auth/register', { email, nickname, password });
      showAlert('회원가입 완료! 로그인 페이지로 이동합니다.', 'success');
      setTimeout(() => { window.location.href = 'login.html'; }, 1200);
    } catch (err) {
      showAlert(err.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '회원가입';
    }
  });
}
