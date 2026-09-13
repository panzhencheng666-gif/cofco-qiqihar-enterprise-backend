const form = document.querySelector('#kc-register-form');
const base = new URL(form.dataset.appBase).origin;
const unit = document.querySelector('#cofco-unit');
const regions = document.querySelector('#cofco-regions');
const search = document.querySelector('#cofco-region-search');
const send = document.querySelector('#cofco-send');
const retry = document.querySelector('#cofco-retry');
const error = document.querySelector('#cofco-entry-error');
const phone = form.querySelector('[name="phone_number"]');
const code = document.querySelector('#cofco-sms-code');
const username = form.querySelector('[name="username"]');
let csrf = '', challengeId = null, submitting = false, nativeReady = false, generation = 0, ready = false;
let allRegions = [], selected = '', cooldownUntil = 0, credentialsComplete = false;
const submit = form.querySelector('[type="submit"]');
submit.disabled = true;
async function api(path, body) {
  const response = await fetch(base + '/api/v1/identity/' + path, {
    credentials: 'include', method: body ? 'POST' : 'GET',
    headers: body ? {'Content-Type':'application/json','X-XSRF-TOKEN':csrf} : {},
    ...(body ? {body:JSON.stringify(body)} : {}),
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || result.error?.message || '请求失败，请稍后重试');
  return result.data;
}
function renderRegions() {
  const term = search.value.trim();
  regions.replaceChildren(new Option('请选择一个乡镇',''), ...allRegions.filter(r => !term || r.name.includes(term) || selected === r.code).map(r => {
    const option = new Option(r.name, r.code); option.selected = selected === r.code; return option;
  }));
  regions.value = selected;
}
async function loadOptions(codeValue = 'QIQIHAR_BUSINESS') {
  const current = ++generation; ready = false; send.disabled = true; submit.disabled = true; regions.disabled = true;
  error.textContent = '正在加载单位和地区…'; retry.hidden = true;
  try {
    const data = await api('registration-entry/options?workUnitCode=' + encodeURIComponent(codeValue));
    if (current !== generation) return;
    if (!data.workUnits?.length || !data.regions?.length) throw new Error('单位或地区暂无可用选项，请重试或联系管理员');
    unit.replaceChildren(...data.workUnits.map(u => new Option(u.name,u.code)));
    unit.value = codeValue; if (!unit.value) throw new Error('所选单位不可用');
    allRegions = data.regions.filter(r => r.administrativeLevel === 'TOWNSHIP');
    if (!allRegions.some(r => r.code === selected)) selected = '';
    renderRegions(); unit.disabled = false; regions.disabled = false; ready = true;
    send.disabled = Date.now() < cooldownUntil; submit.disabled = false; error.textContent = '';
  } catch (caught) { if (current === generation) { error.textContent = caught.message; retry.hidden = false; } }
}
regions.addEventListener('change', () => { selected = regions.value; });
search.addEventListener('input', renderRegions);
unit.addEventListener('change', () => { selected = '';  search.value = ''; void loadOptions(unit.value); });
retry.addEventListener('click', () => void loadOptions(unit.value || 'QIQIHAR_BUSINESS'));
phone?.addEventListener('input', () => { challengeId = null; code.value = ''; code.required = true; });
send.addEventListener('click', async () => {
  if (!ready || !phone || !/^1[3-9][0-9]{9}$/.test(phone.value)) { error.textContent = '请填写有效的11位手机号'; return; }
  send.disabled = true; error.textContent = '';
  try {
    const result = await api('registration-entry/challenge', {phone:phone.value}); challengeId = result.challengeId;
    cooldownUntil = Date.now() + result.retryAfter * 1000;
    const timer = setInterval(() => {
      const seconds = Math.max(0, Math.ceil((cooldownUntil-Date.now())/1000));
      send.textContent = seconds ? seconds+'秒后重发' : '获取验证码';
      if (!seconds) { clearInterval(timer); send.disabled = !ready; }
    },1000);
    error.textContent = '验证码已发送，请查看短信';
  } catch (caught) { error.textContent = caught.message; send.disabled = !ready; }
});
form.addEventListener('submit', async event => {
  if (nativeReady) return;
  event.preventDefault(); if (submitting || !ready) return;
  if (!form.reportValidity() || !selected) { error.textContent = '请完整填写注册信息并选择地区'; return; }
  submitting = true; submit.disabled = true; error.textContent = '';
  try {
    const first = form.querySelector('[name="firstName"]')?.value || '';
    const last = form.querySelector('[name="lastName"]')?.value || '';
    const result = await api('registration-entry/draft', {
      username:username.value, displayName:last+first, phone:phone.value, workUnitCode:unit.value,
      regionCodes:[selected], challengeId, code:code.value,
    });
    if (result.complete) { location.assign(base + '/oauth2/authorization/enterprise'); return; }
    nativeReady = true; submit.disabled = false; form.requestSubmit();
  } catch (caught) { error.textContent = caught.message; submitting = false; submit.disabled = !ready; }
});
try {
  if (!phone) throw new Error('手机号注册配置未就绪，请联系管理员');
  phone.required = true;
  const state = await api('registration-entry/bootstrap'); csrf = state.csrfToken;
  if (state.registered) { location.replace(base+'/'); }
  else {
    credentialsComplete = state.credentialsComplete;
    if (credentialsComplete) {
      username.value = state.username; username.readOnly = true; phone.value = state.phone;
      for (const name of ['password','password-confirm','email']) {
        const field = form.querySelector('[name="'+name+'"]');
        if (field) { field.required = false; field.disabled = true; field.closest('.form-group')?.setAttribute('hidden',''); }
      }
    }
    const draft = state.draft;
    if (draft?.username && draft.username === username.value && draft.phone === phone.value) {
      code.required = false; code.placeholder = '手机号已验证';
      selected = draft.regionCodes?.length === 1 ? draft.regionCodes[0] : '';
    }
    await loadOptions(draft?.workUnitCode || 'QIQIHAR_BUSINESS');
    if (state.registrationError) error.textContent = state.registrationError;
  }
} catch (caught) { error.textContent = caught.message; }
