const form = document.querySelector('#cofco-sms-login');
const base = new URL(form.dataset.appBase).origin;
const password = document.querySelector('#kc-form');
const passwordTab = document.querySelector('#cofco-password-tab');
const smsTab = document.querySelector('#cofco-sms-tab');
const phone = document.querySelector('#cofco-login-phone');
const code = document.querySelector('#cofco-login-code');
const send = document.querySelector('#cofco-login-send');
const error = document.querySelector('#cofco-login-error');
let csrf = '', challengeId = null;
function mode(sms) { form.hidden=!sms; password.hidden=sms; passwordTab.setAttribute('aria-pressed',String(!sms)); smsTab.setAttribute('aria-pressed',String(sms)); }
passwordTab.addEventListener('click',()=>mode(false)); smsTab.addEventListener('click',()=>mode(true));
async function api(path, body) {
 if (!csrf) {
  const response=await fetch(base+'/api/v1/identity/registration-entry/bootstrap',{credentials:'include'});
  if (!response.ok) throw new Error('登录服务暂不可用，请重试');
  csrf=(await response.json()).data.csrfToken;
 }
 const response=await fetch(base+'/api/v1/identity/phone/'+path,{credentials:'include',method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':csrf},body:JSON.stringify(body)});
 const result=await response.json().catch(()=>({})); if(!response.ok)throw new Error(result.message||result.error?.message||'请求失败，请重试'); return result.data;
}
phone.addEventListener('input',()=>{challengeId=null;code.value='';});
send.addEventListener('click',async()=>{
 if(!phone.reportValidity())return; send.disabled=true;error.textContent='';
 try {
  const result=await api('challenge',{phone:phone.value,purpose:'LOGIN'}); challengeId=result.challengeId;
  const until=Date.now()+result.retryAfter*1000; const timer=setInterval(()=>{const n=Math.max(0,Math.ceil((until-Date.now())/1000));send.textContent=n?n+'秒后重发':'获取验证码';if(!n){clearInterval(timer);send.disabled=false;}},1000);
  error.textContent='验证码已发送';
 } catch(e){error.textContent=e.message;send.disabled=false;}
});
form.addEventListener('submit',async(event)=>{
 event.preventDefault(); if(!challengeId){error.textContent='请先获取验证码';return;}
 const button=form.querySelector('[type="submit"]');button.disabled=true;error.textContent='';
 try {await api('login',{challengeId,code:code.value});location.assign(base+'/');} catch(e){error.textContent=e.message;button.disabled=false;}
});

if (document.cookie.split('; ').includes('COFCO_LOGIN_MODE=sms')) {
  mode(true); document.cookie='COFCO_LOGIN_MODE=; Path=/; Max-Age=0; Secure; SameSite=Strict';
}
