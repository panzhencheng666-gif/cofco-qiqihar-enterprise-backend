const smsForm = document.querySelector('#cofco-sms-login');
const emailForm = document.querySelector('#cofco-email-login');
const base = new URL(smsForm.dataset.appBase).origin;
const passwordForm = document.querySelector('#kc-form');
const tabs = {
  password: document.querySelector('#cofco-password-tab'),
  sms: document.querySelector('#cofco-sms-tab'),
  email: document.querySelector('#cofco-email-tab'),
};
let csrf = '';

function mode(selected) {
  passwordForm.hidden = selected !== 'password';
  smsForm.hidden = selected !== 'sms';
  emailForm.hidden = selected !== 'email';
  Object.entries(tabs).forEach(([name, tab]) =>
    tab.setAttribute('aria-pressed', String(name === selected)));
}

tabs.password.addEventListener('click', () => mode('password'));
tabs.sms.addEventListener('click', () => mode('sms'));
tabs.email.addEventListener('click', () => mode('email'));

async function api(channel, path, body) {
  if (!csrf) {
    const response = await fetch(base + '/api/v1/identity/registration-entry/bootstrap', {credentials:'include'});
    if (!response.ok) throw new Error('登录服务暂不可用，请重试');
    csrf = (await response.json()).data.csrfToken;
  }
  const response = await fetch(base + '/api/v1/identity/' + channel + '/' + path, {
    credentials:'include',
    method:'POST',
    headers:{'Content-Type':'application/json','X-XSRF-TOKEN':csrf},
    body:JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || result.error?.message || '请求失败，请重试');
  return result.data;
}

function configureOtp(form, channel, addressField, codeField, sendButton, errorField) {
  let challengeId = null;
  addressField.addEventListener('input', () => {
    challengeId = null;
    codeField.value = '';
  });
  sendButton.addEventListener('click', async () => {
    if (!addressField.reportValidity()) return;
    sendButton.disabled = true;
    errorField.textContent = '';
    try {
      const key = channel === 'phone' ? 'phone' : 'email';
      const result = await api(channel, 'challenge', {[key]:addressField.value,purpose:'LOGIN'});
      challengeId = result.challengeId;
      const until = Date.now() + result.retryAfter * 1000;
      const timer = setInterval(() => {
        const seconds = Math.max(0, Math.ceil((until-Date.now())/1000));
        sendButton.textContent = seconds ? seconds+'秒后重发' : '获取验证码';
        if (!seconds) {
          clearInterval(timer);
          sendButton.disabled = false;
        }
      },1000);
      errorField.textContent = '验证码已发送，5分钟内有效且只能使用一次';
    } catch (caught) {
      errorField.textContent = caught.message;
      sendButton.disabled = false;
    }
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!challengeId) {
      errorField.textContent = '请先获取验证码';
      return;
    }
    const button = form.querySelector('[type="submit"]');
    button.disabled = true;
    errorField.textContent = '';
    try {
      await api(channel, 'login', {challengeId,code:codeField.value});
      location.assign(base + '/');
    } catch (caught) {
      errorField.textContent = caught.message;
      button.disabled = false;
    }
  });
}

configureOtp(
  smsForm,
  'phone',
  document.querySelector('#cofco-login-phone'),
  document.querySelector('#cofco-login-code'),
  document.querySelector('#cofco-login-send'),
  document.querySelector('#cofco-login-error'),
);
configureOtp(
  emailForm,
  'email',
  document.querySelector('#cofco-login-email'),
  document.querySelector('#cofco-email-code'),
  document.querySelector('#cofco-email-send'),
  document.querySelector('#cofco-email-error'),
);

if (document.cookie.split('; ').includes('COFCO_LOGIN_MODE=sms')) {
  mode('sms');
  document.cookie='COFCO_LOGIN_MODE=; Path=/; Max-Age=0; Secure; SameSite=Strict';
}
