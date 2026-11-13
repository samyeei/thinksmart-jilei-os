(function(saved){
 'use strict';
 if(window.top!==window)return;
 const epoch=window.__jileLoginFillEpoch=(window.__jileLoginFillEpoch||0)+1;
 function sameOrigin(){return window.location.protocol==='https:'&&'https://'+window.location.hostname+':'+(window.location.port||'443')===saved.origin;}
 if(!sameOrigin())return;
 function visible(e){return e&&e.getClientRects().length>0&&!e.disabled&&!e.readOnly;}
 function fill(){
  if(epoch!==window.__jileLoginFillEpoch||!sameOrigin())return true;
  const pass=Array.from(document.querySelectorAll('input[type="password"]')).filter(visible);
  if(pass.length!==1)return false;
  const p=pass[0],form=p.closest('form');if(!form)return false;
  if(form.querySelector('[autocomplete="new-password"]')||(!/^(\/.*)?\/(login|signin|sign-in)(\/|$)/i.test(location.pathname)&&p.autocomplete!=='current-password'))return true;
  try{if(form.getAttribute('action')&&new URL(form.getAttribute('action'),location.href).origin!==location.origin)return true;}catch(e){return true;}
  const users=Array.from(form.querySelectorAll('input')).filter(e=>visible(e)&&['email','text'].includes(e.type)&&(/username|email/.test(e.autocomplete)||/^(email|username|user)$/i.test(e.name||e.id)));
  if(users.length!==1)return false;const u=users[0];
  if((u.value&&u.value!==saved.username)||p.value)return true;
  const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
  function set(e,value){setter.call(e,value);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
  if(!u.value)set(u,saved.username);set(p,saved.password);return true;
 }
 let attempts=0;function retry(){if(fill()||++attempts>=25)return;setTimeout(retry,300);}retry();
})
