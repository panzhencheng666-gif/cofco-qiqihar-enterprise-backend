const form = document.querySelector('#kc-register-form');
const base = new URL(form.dataset.appBase).origin;
const unit = document.querySelector('#cofco-unit');
const regions = document.querySelector('#cofco-regions');
const search = document.querySelector('#cofco-region-search');
const send = document.querySelector('#cofco-send');
const retry = document.querySelector('#cofco-retry');
const error = document.querySelector('#cofco-entry-error');
const phone = form.querySelector('[name="phone_number"]');
const email = form.querySelector('[name="email"]');
const methods = [...form.querySelectorAll('[name="cofco-verification-method"]')];
const code = document.querySelector('#cofco-verification-code');
const codeLabel = document.querySelector('#cofco-code-label');
const username = form.querySelector('[name="username"]');
const submit = form.querySelector('[type="submit"]');
let csrf = '', challengeId = null, submitting = false, nativeReady = false, generation = 0, ready = false;
let allRegions = [], selected = '', cooldownUntil = 0, credentialsComplete = false;
submit.disabled = true;

function verificationMethod() {
  return methods.find(method => method.checked)?.value || 'PHONE';
}

function verificationTarget() {
  return verificationMethod() === 'EMAIL' ? email?.value : phone?.value;
}

function refreshVerificationFields() {
  const byPhone = verificationMethod() === 'PHONE';
  if (phone) phone.required = byPhone;
  if (email) email.required = true;
  codeLabel.textContent = byPhone ? '手机验证码 *' : '邮箱验证码 *';
  challengeId = null;
  code.value = '';
  code.required = true;
}

async function api(path, body) {
  const response = await fetch(base + '/api/v1/identity/' + path, {
    credentials: 'include',
    method: body ? 'POST' : 'GET',
    headers: body ? {'Content-Type':'application/json','X-XSRF-TOKEN':csrf} : {},
    ...(body ? {body:JSON.stringify(body)} : {}),
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || result.error?.message || '请求失败，请稍后重试');
  return result.data;
}

function renderRegions() {
  const term = search.value.trim();
  regions.replaceChildren(new Option('请选择一个乡镇',''), ...allRegions
    .filter(region => !term || region.name.includes(term) || selected === region.code)
    .map(region => {
      const option = new Option(region.name, region.code);
      option.selected = selected === region.code;
      return option;
    }));
  regions.value = selected;
}

async function loadOptions(codeValue = 'QIQIHAR_BUSINESS') {
  const current = ++generation;
  ready = false;
  send.disabled = true;
  submit.disabled = true;
  regions.disabled = true;
  error.textContent = '正在加载单位和地区…';
  retry.hidden = true;
  try {
    const data = await api('registration-entry/options?workUnitCode=' + encodeURIComponent(codeValue));
    if (current !== generation) return;
    if (!data.workUnits?.length || !data.regions?.length) throw new Error('单位或地区暂无可用选项，请重试或联系管理员');
    unit.replaceChildren(...data.workUnits.map(item => new Option(item.name,item.code)));
    unit.value = codeValue;
    if (!unit.value) throw new Error('所选单位不可用');
    allRegions = data.regions.filter(region => region.administrativeLevel === 'TOWNSHIP');
    if (!allRegions.some(region => region.code === selected)) selected = '';
    renderRegions();
    unit.disabled = false;
    regions.disabled = false;
    ready = true;
    send.disabled = Date.now() < cooldownUntil;
    submit.disabled = false;
    error.textContent = '';
  } catch (caught) {
    if (current === generation) {
      error.textContent = caught.message;
      retry.hidden = false;
    }
  }
}

regions.addEventListener('change', () => { selected = regions.value; });
search.addEventListener('input', renderRegions);
unit.addEventListener('change', () => { selected = ''; search.value = ''; void loadOptions(unit.value); });
retry.addEventListener('click', () => void loadOptions(unit.value || 'QIQIHAR_BUSINESS'));
phone?.addEventListener('input', refreshVerificationFields);
email?.addEventListener('input', refreshVerificationFields);
methods.forEach(method => method.addEventListener('change', refreshVerificationFields));

send.addEventListener('click', async () => {
  const method = verificationMethod();
  const valid = method === 'EMAIL'
    ? email?.reportValidity()
    : phone && /^1[3-9][0-9]{9}$/.test(phone.value);
  if (!ready || !valid) {
    error.textContent = method === 'EMAIL' ? '请填写有效邮箱' : '请填写有效的11位手机号';
    return;
  }
  send.disabled = true;
  error.textContent = '';
  try {
    const result = await api('registration-entry/challenge', {
      verificationMethod: method,
      phone: phone?.value || null,
      email: email.value,
    });
    challengeId = result.challengeId;
    cooldownUntil = Date.now() + result.retryAfter * 1000;
    const timer = setInterval(() => {
      const seconds = Math.max(0, Math.ceil((cooldownUntil-Date.now())/1000));
      send.textContent = seconds ? seconds+'秒后重发' : '获取验证码';
      if (!seconds) { clearInterval(timer); send.disabled = !ready; }
    },1000);
    error.textContent = method === 'EMAIL' ? '验证码已发送，请查看邮箱' : '验证码已发送，请查看短信';
  } catch (caught) {
    error.textContent = caught.message;
    send.disabled = !ready;
  }
});

form.addEventListener('submit', async event => {
  if (nativeReady) return;
  event.preventDefault();
  if (submitting || !ready) return;
  if (!form.reportValidity() || !selected) {
    error.textContent = '请完整填写注册信息并选择地区';
    return;
  }
  if (!challengeId) {
    error.textContent = '请先获取并填写验证码';
    return;
  }
  submitting = true;
  submit.disabled = true;
  error.textContent = '';
  try {
    const first = form.querySelector('[name="firstName"]')?.value || '';
    const last = form.querySelector('[name="lastName"]')?.value || '';
    const result = await api('registration-entry/draft', {
      username: username.value,
      displayName: last + first,
      phone: phone?.value || null,
      email: email.value,
      verificationMethod: verificationMethod(),
      workUnitCode: unit.value,
      regionCodes: [selected],
      challengeId,
      code: code.value,
    });
    if (result.complete) {
      location.assign(base + '/oauth2/authorization/enterprise');
      return;
    }
    nativeReady = true;
    submit.disabled = false;
    form.requestSubmit();
  } catch (caught) {
    error.textContent = caught.message;
    submitting = false;
    submit.disabled = !ready;
  }
});

try {
  if (!phone || !email) throw new Error('手机或邮箱注册配置未就绪，请联系管理员');
  refreshVerificationFields();
  const state = await api('registration-entry/bootstrap');
  csrf = state.csrfToken;
  if (state.registered) {
    location.replace(base + '/');
  } else {
    credentialsComplete = state.credentialsComplete;
    if (credentialsComplete) {
      username.value = state.username;
      username.readOnly = true;
      phone.value = state.phone;
      for (const name of ['password','password-confirm']) {
        const field = form.querySelector('[name="'+name+'"]');
        if (field) {
          field.required = false;
          field.disabled = true;
          field.closest('.form-group')?.setAttribute('hidden','');
        }
      }
    }
    const draft = state.draft;
    if (draft?.username && draft.username === username.value) {
      email.value = draft.email || email.value;
      phone.value = draft.phone || phone.value;
      const savedMethod = methods.find(method => method.value === draft.verificationMethod);
      if (savedMethod) savedMethod.checked = true;
      if (draft.email === email.value && (!draft.phone || draft.phone === phone.value)) {
        code.required = false;
        code.placeholder = '联系方式已验证';
      }
      selected = draft.regionCodes?.length === 1 ? draft.regionCodes[0] : '';
    }
    refreshVerificationFields();
    if (draft?.username) {
      code.required = false;
      code.placeholder = '联系方式已验证';
    }
    await loadOptions(draft?.workUnitCode || 'QIQIHAR_BUSINESS');
    if (state.registrationError) error.textContent = state.registrationError;
  }
} catch (caught) {
  error.textContent = caught.message;
}
