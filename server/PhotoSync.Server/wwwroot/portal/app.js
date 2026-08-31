const $ = id => document.getElementById(id);
let csrf = '', me = null, adminMode = false;
const message = text => { $('message').textContent = text; };
const bytes = value => new Intl.NumberFormat('ru', {maximumFractionDigits:1}).format(value / 1048576) + ' МБ';
const date = value => value ? new Date(value).toLocaleString('ru') : 'Пока нет';
function element(tag, text, className) { const node = document.createElement(tag); node.textContent = text; if (className) node.className = className; return node; }
async function api(path, body) {
  if (body !== undefined && !csrf) await getCsrf();
  const response = await fetch('/api/portal' + path, { credentials:'same-origin', method:body === undefined ? 'GET':'POST', headers:body === undefined ? {} : {'Content-Type':'application/json','X-PhotoSync-CSRF':csrf}, body:body === undefined ? undefined : JSON.stringify(body) });
  if (!response.ok) { const error = new Error(response.status === 401 ? 'Не удалось войти. Проверьте имя и пароль; после нескольких попыток вход временно блокируется.' : response.status === 403 ? 'Недостаточно прав.' : response.status === 429 ? 'Слишком много попыток. Подождите минуту.' : response.status === 409 ? 'Устройство уже назначено аккаунту.' : 'Операция не выполнена. Проверьте данные и повторите попытку.'); error.status = response.status; throw error; }
  return response.json();
}
async function getCsrf() { csrf = (await api('/csrf')).token; }
function empty(target, text) { target.replaceChildren(element('p',text,'empty')); }
function stats(target, rows) { target.replaceChildren(...rows.map(([label,value]) => { const node=element('div','','stat');node.append(element('span',label),element('strong',String(value)));return node; })); }
async function loadUser() {
  const data = await api('/dashboard');
  stats($('user-stats'),[['Телефонов',data.devices.length],['Сохранено файлов',data.fileCount],['В архиве',bytes(data.bytesTotal)]]);
  $('my-devices').replaceChildren(...data.devices.map(d=>{const card=element('article','','device');card.append(element('h3',d.name),element('p','Последнее обращение: '+date(d.lastSeenAt)),element('p','Приложение '+d.appVersion));return card;}));
  if(!data.devices.length)empty($('my-devices'),'Пока нет назначенных телефонов. Подключите Android к этому серверу и попросите владельца подтвердить устройство. Само знание ID не даёт доступ к фотографиям.');
  $('my-albums').replaceChildren(...data.albums.map(a=>{const card=element('article','','album');card.append(element('h3',a.name),element('p',data.devices.find(d=>d.id===a.deviceId)?.name || 'Телефон'));return card;}));
  if(!data.albums.length)empty($('my-albums'),'Первые альбомы появятся после подключения вашего телефона.');
  $('my-files').replaceChildren(...data.files.map(f=>{const row=element('div','','file');const text=element('div','');text.append(element('strong',f.name),element('small',bytes(f.bytes)+' · '+date(f.uploadedAt)));const link=element('a','Скачать оригинал ↓');link.href='/api/portal/files/'+f.id+'/download';row.append(text,link);return row;}));
  if(!data.files.length)empty($('my-files'),'Загрузок пока нет. Оригиналы появятся здесь после сохранения с телефона.');
}
async function loadAdmin() {
  const data=await api('/admin/dashboard');
  stats($('admin-stats'),[['Устройств',data.deviceCount],['Файлов',data.fileCount],['В хранилище',bytes(data.bytesTotal)]]);
  $('server-name').textContent=data.server.name;
  $('server-state').textContent='База данных: '+(data.server.database?'доступна':'ошибка')+' · Протокол '+data.server.protocolVersion+' · Свободно на диске: '+(data.server.freeBytes===null?'нет данных':bytes(data.server.freeBytes));
  $('all-devices').replaceChildren(...data.devices.map(d=>{const card=element('article','','device');card.append(element('h3',d.name),element('p',d.uuid),element('p',d.fileCount+' файлов · '+bytes(d.bytes)),element('p','Последнее обращение: '+date(d.lastSeenAt)),element('p',d.ownerId?'Назначено аккаунту':'Без аккаунта'));return card;}));
  if(!data.devices.length)empty($('all-devices'),'Телефоны ещё не подключались.');
  const isOwner=me.roles.includes('SuperAdmin');$('owner-controls').hidden=!isOwner;
  if(isOwner){const users=await api('/admin/users');$('user-choice').replaceChildren(...users.map(u=>{const o=element('option',u.name+' · '+u.roles.join(', '));o.value=u.id;return o;}));$('device-choice').replaceChildren(...data.devices.filter(d=>!d.ownerId).map(d=>{const o=element('option',d.name+' · '+d.uuid);o.value=String(d.id);return o;}));$('assign-device').querySelector('button').disabled=!data.devices.some(d=>!d.ownerId)||!users.length;}
  const audit=await api('/admin/audit');$('audit').replaceChildren(...audit.map(a=>{const row=element('div','','file');const text=element('div','');text.append(element('strong',({'user_created':'Создан аккаунт','device_assigned':'Назначен владелец устройства'})[a.action]||a.action),element('small',date(a.atUtc)+' · '+a.target));row.append(text);return row;}));if(!audit.length)empty($('audit'),'Административных действий пока не было.');
}
async function refresh() { await (adminMode?loadAdmin():loadUser()); }
async function session() {
  try { me=await api('/me'); } catch(error) { if(error.status!==401)throw error;me=null; }
  $('login-view').hidden=!!me;$('workspace').hidden=!me;$('logout').hidden=!me;
  if(!me)return;
  $('welcome').textContent=me.name+' · мой архив';$('account-role').textContent=me.roles.includes('SuperAdmin')?'Владелец сервера':me.roles.includes('ServerAdmin')?'Администратор сервера':'Личный кабинет';
  $('admin-tab').hidden=!me.roles.some(r=>r==='SuperAdmin'||r==='ServerAdmin');await getCsrf();await refresh();
}
function submit(id, action) { $(id).addEventListener('submit',async event=>{event.preventDefault();const button=event.currentTarget.querySelector('button');button.disabled=true;message('');try{await action(Object.fromEntries(new FormData(event.currentTarget)));}catch(error){message(error.message);}finally{button.disabled=false;}}); }
submit('login-form',async data=>{await api('/login',data);$('login-form').reset();csrf='';await session();});
submit('create-user',async data=>{await api('/admin/users',data);$('create-user').reset();await loadAdmin();message('Аккаунт создан. Передайте пароль владельцу безопасным способом.');});
submit('assign-device',async data=>{await api('/admin/devices/'+Number(data.deviceId)+'/owner',{userId:data.userId});$('assign-device').reset();await loadAdmin();message('Телефон назначен аккаунту.');});
submit('password-form',async data=>{await api('/password',data);$('password-form').reset();csrf='';await session();message('Пароль изменён. Войдите снова.');});
$('logout').addEventListener('click',async()=>{try{await api('/logout',{});csrf='';me=null;adminMode=false;$('user-view').hidden=false;$('admin-view').hidden=true;$('user-tab').setAttribute('aria-pressed','true');$('admin-tab').setAttribute('aria-pressed','false');await session();}catch(error){message(error.message);}});
for(const [id,isAdmin] of [['user-tab',false],['admin-tab',true]])$(id).addEventListener('click',async()=>{adminMode=isAdmin;$('user-view').hidden=isAdmin;$('admin-view').hidden=!isAdmin;$('user-tab').setAttribute('aria-pressed',String(!isAdmin));$('admin-tab').setAttribute('aria-pressed',String(isAdmin));try{await refresh();}catch(error){message(error.message);}});
$('refresh').addEventListener('click',()=>refresh().catch(error=>message(error.message)));
async function start() {
  const status = await api('/status');
  $('setup-notice').hidden = status.loginAvailable;
  $('login-form').hidden = !status.loginAvailable;
  if (status.loginAvailable) await session();
}
start().catch(error=>message(error.message));
