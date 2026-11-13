import {siteIconChoices, renderSiteIcon} from '/site-icons.js';

const byId = id => document.getElementById(id);
const localParent = window.V5LocalParent;
if (localParent) document.body.classList.add('local-parent');
const panelHash = () => localParent ? location.hash.replace(/^#local-/, '#') : location.hash;
function setChildren(node, ...children) {
  while (node.firstChild) node.removeChild(node.firstChild);
  for (const child of children) node.appendChild(child);
}
function formObject(form) {
  const values = {};
  form.forEach((value, key) => { values[key] = value; });
  return values;
}
let csrf;
let imageUrl;
const navigation = [...document.querySelectorAll('.admin-sidebar nav a')];

function showPanel() {
  const selected = navigation.find(link => link.hash === panelHash())
    || navigation.find(link => link.hash === '#remote');
  for (const link of navigation) {
    byId(link.hash.slice(1)).hidden = link !== selected;
    if (link === selected) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  byId('page-title').textContent = selected.textContent;
  document.querySelector('.admin-content').scrollTop = 0;
  window.scrollTo(0, 0);
  if (selected.hash === '#about') refreshUpdate();
}
for (const link of navigation) {
  link.addEventListener('click', event => {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (location.hash !== link.hash) history.pushState(null, '', link.hash);
    showPanel();
  });
}
window.addEventListener('hashchange', showPanel);
window.addEventListener('popstate', showPanel);

async function request(path, options) {
  const result = await fetch(path, options);
  if (result.status === 401) location.assign('/login');
  if (!result.ok) {
    const body = await result.json().catch(() => ({}));
    throw Error(body.error || '设备暂时不可用');
  }
  return result;
}
function post(path, body, type) {
  const headers = {'x-csrf-token': csrf};
  if (type) headers['Content-Type'] = type;
  return request(path, {method:'POST', headers, body});
}
async function refreshStatus() {
  const state = await (await request('/api/status')).json();
  csrf = state.csrf_token;
  byId('status').textContent = state.locked ? '儿童模式已锁定' : '设备当前未锁定';
  byId('remote-address').textContent = state.local_ip ? `家庭 Wi-Fi 地址：http://${state.local_ip}:8766/` : '设备未连接 Wi-Fi';
  byId('capture').disabled = false;
  byId('logout').disabled = false;
}
function showApps(data) {
  const rows = data.apps.map(app => {
    const row = document.createElement('label');
    row.className = 'app';
    if (app.icon) {
      const icon = document.createElement('img');
      icon.alt = '';
      icon.src = app.icon;
      row.append(icon);
    }
    const label = document.createElement('span');
    label.textContent = app.label;
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.setAttribute('aria-label', '开放 ' + app.label);
    check.checked = app.allowed;
    check.addEventListener('change', async () => {
      check.disabled = true;
      const allowed = check.checked;
      try {
        await update('/api/apps', {id:app.id, allowed});
        app.allowed = allowed;
        byId('apps-status').textContent = app.label + (allowed ? '已开放' : '已关闭');
      } catch (error) {
        check.checked = app.allowed;
        byId('apps-status').textContent = error.message;
      } finally { check.disabled = false; }
    });
    row.append(label, check);
    return row;
  });
  setChildren(byId('apps'), ...rows);
}
function localResult(value) {
  const result = JSON.parse(value);
  if (result.error) throw Error(result.error);
  return result;
}
const json = async path => localParent
  ? localResult(localParent.read(path)) : (await request(path)).json();
const update = async (path, data) => localParent
  ? localResult(localParent.write(path, JSON.stringify(data)))
  : (await post(path, JSON.stringify(data), 'application/json')).json();
const siteForm = byId('site-form');
const sitePermissions = ['camera', 'microphone', 'photos'];
function siteSymbol(icon) {
  const symbol = document.createElement('span');
  symbol.className = 'site-symbol';
  symbol.innerHTML = renderSiteIcon(icon);
  return symbol;
}
function pickSiteIcon(id) {
  siteForm.elements.icon.value = id;
  for (const button of byId('site-icons').children) {
    button.setAttribute('aria-pressed', String(button.dataset.icon === id));
  }
}
for (const icon of siteIconChoices) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'site-choice';
  button.dataset.icon = icon.id;
  button.append(siteSymbol(icon.id), document.createTextNode(icon.name));
  button.addEventListener('click', () => pickSiteIcon(icon.id));
  byId('site-icons').append(button);
}
pickSiteIcon('globe');

function resetSiteForm() {
  siteForm.reset();
  siteForm.elements.url.readOnly = false;
  pickSiteIcon('globe');
  byId('site-save').textContent = '添加网址';
  byId('site-cancel').hidden = true;
}
byId('site-cancel').addEventListener('click', resetSiteForm);

function showSites({sites}) {
  const nodes = sites.map(site => {
    const row = document.createElement('li');
    const info = document.createElement('div');
    const url = document.createElement('small');
    const actions = document.createElement('div');
    const edit = document.createElement('button');
    const remove = document.createElement('button');
    row.className = 'site-record';
    actions.className = 'site-actions';
    url.textContent = site.url + (site.includeSubdomains ? ' · 含子域名' : '');
    edit.textContent = '编辑';
    remove.textContent = '移除';
    edit.setAttribute('aria-label', '编辑 ' + site.label);
    remove.setAttribute('aria-label', '移除 ' + site.label);
    edit.addEventListener('click', () => {
      siteForm.elements.label.value = site.label;
      siteForm.elements.url.value = site.url;
      siteForm.elements.url.readOnly = true;
      siteForm.elements.includeSubdomains.checked = site.includeSubdomains === true;
      const permissions = site.permissions || {};
      for (const key of sitePermissions) siteForm.elements[key].checked = permissions[key] === true;
      pickSiteIcon(site.icon || 'globe');
      byId('site-save').textContent = '保存网址';
      byId('site-cancel').hidden = false;
      siteForm.elements.label.focus();
    });
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try {
        showSites(await update('/api/sites', {...site, remove:true}));
        if (siteForm.elements.url.readOnly && siteForm.elements.url.value === site.url) resetSiteForm();
        byId('site-status').textContent = '已移除网址';
      } catch (error) { byId('site-status').textContent = error.message; }
      finally { remove.disabled = false; }
    });
    info.append(document.createTextNode(site.label), url);
    actions.append(edit, remove);
    row.append(siteSymbol(site.icon || 'globe'), info, actions);
    return row;
  });
  setChildren(byId('site-list'), ...nodes);
  if (!sites.length) byId('site-list').textContent = '还没有开放的网址。';
}
siteForm.addEventListener('submit', async event => {
  event.preventDefault();
  const values = formObject(new FormData(siteForm));
  const save = byId('site-save');
  save.disabled = true;
  try {
    const permissions = {};
    for (const key of sitePermissions) {
      permissions[key] = siteForm.elements[key].checked;
      delete values[key];
    }
    showSites(await update('/api/sites', {...values, permissions, includeSubdomains:siteForm.elements.includeSubdomains.checked, remove:false}));
    resetSiteForm();
    byId('site-status').textContent = '网址已同步到儿童桌面';
  } catch (error) { byId('site-status').textContent = error.message; }
  finally { save.disabled = false; }
});

const desktopForm = byId('desktop-form');
const wallpaperChoices = [
  ['original', '深蓝流光'], ['aurora', '极光之境'], ['cosmos', '星际漫游'], ['dune', '暮色沙丘']
];
let savedPhoto = '';
let pendingPhoto = '';
let photoLoading = false;
let desktopSaving = false;
function renderWallpapers() {
  const photo = pendingPhoto || savedPhoto;
  const choices = photo ? [...wallpaperChoices, ['custom', '自己的照片']] : wallpaperChoices;
  const selected = desktopForm.elements.wallpaper.value;
  setChildren(byId('wallpapers'), ...choices.map(([id, name]) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'wallpaper-choice';
    button.setAttribute('aria-pressed', String(id === selected));
    const image = document.createElement('img');
    image.alt = '';
    image.src = id === 'custom' ? photo : '/wallpapers/' + id + '.webp';
    const label = document.createElement('span');
    label.textContent = name;
    button.append(image, label);
    button.addEventListener('click', () => {
      desktopForm.elements.wallpaper.value = id;
      for (const choice of byId('wallpapers').children) {
        choice.setAttribute('aria-pressed', String(choice === button));
      }
    });
    return button;
  }));
}
function showDesktop(state) {
  byId('greeting').value = state.greeting;
  byId('motion').checked = state.motion;
  const player = byId('desktop-player');
  const choices = (state.players || []).map(app => new Option(app.label, app.package));
  if (!choices.some(option => option.value === state.player)) {
    choices.unshift(new Option('当前播放器未开放', state.player));
  }
  setChildren(player, ...choices);
  player.value = state.player;
  desktopForm.elements.wallpaper.value = state.wallpaper;
  savedPhoto = state.customWallpaper;
  renderWallpapers();
  byId('desktop-save').disabled = false;
}
async function compressPhoto(file) {
  if (!['image/jpeg','image/png','image/webp'].includes(file.type) || file.size > 20 * 1024 * 1024) {
    throw Error('请选择 20 MB 以内的 JPG、PNG 或 WebP 图片');
  }
  const image = new Image();
  const url = URL.createObjectURL(file);
  try {
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(Error('无法读取这张图片'));
      image.src = url;
    });
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 1280 / Math.max(image.naturalWidth, image.naturalHeight));
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext('2d', {alpha:false});
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    for (const quality of [0.82, 0.68, 0.52, 0.36]) {
      const compressed = canvas.toDataURL('image/jpeg', quality);
      if (compressed.length <= 390000) return compressed;
    }
    throw Error('图片压缩后仍过大，请换一张图片');
  } finally { URL.revokeObjectURL(url); }
}
byId('wallpaper-file').addEventListener('change', async event => {
  const input = event.currentTarget;
  if (!input.files.length || photoLoading || desktopSaving) return;
  const file = input.files[0];
  photoLoading = true;
  input.disabled = true;
  byId('desktop-save').disabled = true;
  try {
    pendingPhoto = await compressPhoto(file);
    desktopForm.elements.wallpaper.value = 'custom';
    renderWallpapers();
    byId('desktop-status').textContent = '照片已准备好，保存后应用到儿童桌面';
  } catch (error) { byId('desktop-status').textContent = error.message; }
  finally {
    photoLoading = false;
    input.value = '';
    input.disabled = false;
    byId('desktop-save').disabled = false;
  }
});
desktopForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (photoLoading || desktopSaving) return;
  const save = byId('desktop-save');
  desktopSaving = true;
  save.disabled = true;
  byId('wallpaper-file').disabled = true;
  const data = {greeting:byId('greeting').value, motion:byId('motion').checked,
    player:byId('desktop-player').value,
    wallpaper:desktopForm.elements.wallpaper.value};
  if (pendingPhoto) data.customWallpaper = pendingPhoto;
  try {
    const state = await update('/api/desktop', data);
    pendingPhoto = '';
    showDesktop(state);
    byId('desktop-status').textContent = '桌面设置已保存并同步到 V5';
  } catch (error) { byId('desktop-status').textContent = error.message; }
  finally {
    desktopSaving = false;
    save.disabled = false;
    byId('wallpaper-file').disabled = false;
  }
});
let usage;
function showUsage() {
  const rows = (usage && usage[byId('usage-window').value]) || [];
  const longest = Math.max(1, ...rows.map(item => item.ms));
  byId('usage-status').textContent = !(usage && usage.ready) ? '设备未开启使用情况权限。' : rows.length ? '' : '暂无应用使用记录。';
  setChildren(byId('usage-list'), ...rows.map(item => {
    const row = document.createElement('li');
    const seconds = Math.floor(item.ms / 1000);
    const duration = seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : `${Math.max(seconds, 1)} 秒`;
    const summary = document.createElement('div');
    summary.className = 'usage-row';
    const label = document.createElement('span');
    label.textContent = item.label;
    const time = document.createElement('span');
    time.textContent = duration;
    summary.append(label, time);
    const bar = document.createElement('progress');
    bar.max = longest;
    bar.value = item.ms;
    bar.setAttribute('aria-label', item.label + '使用时长 ' + duration);
    row.append(summary, bar);
    return row;
  }));
}
async function refreshUsage() {
  byId('usage-refresh').disabled = true;
  try { usage = await json('/api/usage'); showUsage(); }
  catch (error) { byId('usage-status').textContent = error.message; }
  finally { byId('usage-refresh').disabled = false; }
}
byId('usage-window').addEventListener('change', showUsage);
byId('usage-refresh').addEventListener('click', refreshUsage);
byId('capture').addEventListener('click', async () => {
  byId('capture').disabled = true;
  byId('screen').hidden = true;
  try {
    const png = await (await post('/api/screen')).blob();
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    imageUrl = URL.createObjectURL(png);
    const image = document.createElement('img');
    image.alt = '设备当前屏幕截图';
    image.src = imageUrl;
    setChildren(byId('screen'), image);
    byId('screen').hidden = false;
    byId('capture-status').textContent = '已查看一张截图';
  } catch (error) {
    byId('capture-status').textContent = error.message;
  } finally { byId('capture').disabled = false; }
});
byId('logout').addEventListener('click', async () => {
  try { await post('/api/logout'); location.assign('/login'); }
  catch (error) { byId('status').textContent = error.message; }
});
byId('password').addEventListener('submit', async event => {
  event.preventDefault();
  const data = formObject(new FormData(event.currentTarget));
  try {
    if (localParent) {
      await update('/api/password', data);
    } else {
      await post('/api/password', new URLSearchParams(data).toString(), 'application/x-www-form-urlencoded');
      location.assign('/login');
    }
  } catch (error) { byId('password-status').textContent = error.message; }
});
if (localParent) {
  byId('author-site').addEventListener('click', event => {
    event.preventDefault();
    localParent.openAuthorSite();
  });
}
function showUpdate(state) {
  byId('update-version').textContent = '当前版本 ' + state.version;
  byId('update-status').textContent = state.available ? state.message : '请先启用设备管理。';
  byId('update-check').disabled = !state.available;
}
async function refreshUpdate() {
  try { showUpdate(await json('/api/update')); }
  catch (error) { byId('update-status').textContent = error.message; }
}
byId('update-check').addEventListener('click', async () => {
  try { showUpdate(await update('/api/update', {check:true})); }
  catch (error) { byId('update-status').textContent = error.message; }
});
setInterval(() => {
  if (panelHash() === '#about' && !document.hidden) refreshUpdate();
}, 5000);
showPanel();
(async () => {
  try {
    if (localParent) {
      const panel = panelHash();
      if (panel === '#websites') {
        try { showSites(await json('/api/sites')); }
        catch (error) { byId('site-status').textContent = error.message; }
      }
      if (panel === '#desktop') {
        try { showDesktop(await json('/api/desktop')); }
        catch (error) { byId('desktop-status').textContent = error.message; }
      }
      if (panel === '#usage') await refreshUsage();
      return;
    }
    await refreshStatus();
    await Promise.all([
      json('/api/apps').then(showApps).catch(error => { byId('apps-status').textContent = error.message; }),
      json('/api/sites').then(showSites).catch(error => { byId('site-status').textContent = error.message; }),
      json('/api/desktop').then(showDesktop).catch(error => { byId('desktop-status').textContent = error.message; }),
      refreshUsage()
    ]);
  } catch (error) { byId('status').textContent = error.message; }
})();
