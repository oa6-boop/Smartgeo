'use strict';
// SmartGeo Admin : meme module sur le port 8080 et sur le frontend local (5500).
const API_ROOT=window.SMARTGEO?.API_BASE||new URL('../api',location.href).href.replace(/\/$/,'');
const token=sessionStorage.getItem('smartgeo_admin_token')||localStorage.getItem('smartgeo_token');
if(!token)location.replace(location.pathname.includes('/admin/')?'login.html':'index.html');
const $=id=>document.getElementById(id);
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const arr=x=>Array.isArray(x)?x:Array.isArray(x?.data)?x.data:[];
const obj=x=>x&&'data' in x?x.data:x;
const labels={PENDING:'En attente',IN_PROGRESS:'En cours',COMPLETED:'Terminée',CANCELLED:'Annulée',SUBMITTED:'Soumis',REVIEWED:'Examiné',REJECTED:'Rejeté',ACTIVE:'Actif',INACTIVE:'Inactif',BLOCKED:'Bloqué',TRANSMITTED:'Transmise',REFUSED:'Refusée',USER:'Agent',ADMIN:'Admin'};
const pretty=x=>labels[x]||x||'—';
const badge=x=>`<span class="admin-badge ${esc(x)}">${esc(pretty(x))}</span>`;
const fmt=x=>{if(!x)return '—';if(Array.isArray(x))return x.join('/');const d=new Date(x);return isNaN(d)?String(x):d.toLocaleString('fr-FR');};
const inputDate=x=>{if(!x)return '';if(Array.isArray(x))return [x[0],String(x[1]).padStart(2,'0'),String(x[2]).padStart(2,'0')].join('-');if(typeof x==='string'&&/^\d{4}-\d{2}-\d{2}/.test(x))return x.slice(0,10);const d=new Date(x);return isNaN(d)?'':d.toISOString().slice(0,10);};
const option=(id,text,chosen)=>`<option value="${esc(id)}" ${chosen?'selected':''}>${esc(text)}</option>`;
const panel=(title,body)=>`<section class="panel"><h2>${esc(title)}</h2>${body}</section>`;
const table=(heads,rows)=>`<div class="table-scroll"><table class="admin-table"><thead><tr>${heads.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.join(''):`<tr><td colspan="${heads.length}" class="admin-muted">Aucune donnée disponible</td></tr>`}</tbody></table></div>`;
const tr=(...parts)=>`<tr>${parts.map(x=>`<td>${x}</td>`).join('')}</tr>`;
let page='home',admin=null,map=null,refresh=null,gpsUser=0,chatId=0;
function notify(msg,bad=false){const n=$('adminNotice');n.textContent=msg;n.hidden=false;n.className='admin-notice'+(bad?' bad':'');}
function dispose(){if(refresh){clearInterval(refresh);refresh=null;}if(map){map.remove();map=null;}}
function logout(){dispose();sessionStorage.removeItem('smartgeo_admin_token');localStorage.removeItem('smartgeo_token');localStorage.removeItem('smartgeo_user');location.replace(location.pathname.includes('/admin/')?'login.html':'index.html');}
async function api(path,method='GET',body){
  let response;
  try {response=await fetch(API_ROOT+path,{method,headers:{'Accept':'application/json','Authorization':'Bearer '+token,...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});}
  catch {throw new Error('Serveur SmartGeo indisponible. Vérifiez WildFly.');}
  let result=null;const text=await response.text();if(text){try{result=JSON.parse(text);}catch{result={message:text};}}
  if(response.status===401){logout();throw new Error('Session expirée. Reconnexion nécessaire.');}
  if(!response.ok||result?.success===false)throw new Error(result?.message||result?.error||'Erreur HTTP '+response.status);
  return result;
}
async function task(fn){try{await fn();}catch(e){console.error(e);notify(e.message||'Erreur.',true);}}
function openPage(next){
  dispose();page=next;chatId=0;$('adminNotice').hidden=true;
  document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===next));
  $('adminContent').innerHTML='<div class="panel">Chargement…</div>';
  const views={home:homePage,users:usersPage,missions:missionsPage,gps:gpsPage,schedules:schedulesPage,messages:messagesPage,requests:requestsPage,reports:reportsPage};
  task(async()=>await views[next]());
}
async function homePage(){
  const [statsResult,missionsResult,reportsResult]=await Promise.all([api('/admin/dashboard'),api('/missions'),api('/reports')]);
  const stats=obj(statsResult),missions=arr(missionsResult),reports=arr(reportsResult);if(page!=='home')return;
  const cards=[['Utilisateurs',stats.users_total],['Utilisateurs actifs',stats.users_active],['Agents localisés récemment',stats.sharing_now],['Demandes GPS en attente',stats.pending_requests],['Missions',missions.length],['Missions terminées',missions.filter(x=>x.status==='COMPLETED').length],['Signalements à examiner',reports.filter(x=>x.status==='SUBMITTED').length],['Messages non lus',stats.unread_messages]];
  $('adminContent').innerHTML=`<div class="admin-stats">${cards.map(([name,count])=>panel(name,`<strong>${Number(count)||0}</strong>`)).join('')}</div>`+
    panel('Missions récentes',table(['Titre','Agent','Statut','Début'],missions.slice(0,8).map(m=>tr(esc(m.title),esc(m.assigned_user_id||'Non affectée'),badge(m.status),esc(fmt(m.start_date))))));
}
async function usersPage(){
  const users=arr(await api('/admin/users'));if(page!=='users')return;
  $('adminContent').innerHTML=panel('Ajouter un utilisateur',`<form class="admin-form admin-two" id="createUser"><label>Nom complet<input name="fullName" maxlength="150" required></label><label>Identifiant<input name="username" maxlength="80" required></label><label>Email<input name="email" type="email" maxlength="160"></label><label>Téléphone<input name="phone" maxlength="30"></label><label>Mot de passe initial<input name="password" type="password" minlength="8" required></label><label>Rôle<select name="role">${option('USER','Agent')}${option('ADMIN','Administrateur')}</select></label><div><button>Créer le compte</button></div></form>`)+
    panel('Utilisateurs',table(['ID','Nom','Identifiant','Email','Rôle','Statut','Actions'],users.map(u=>tr(esc(u.id),esc(u.fullName),esc(u.username),esc(u.email),badge(u.role),badge(u.status),`<div class="admin-actions"><button class="admin-secondary" data-edit-user="${u.id}">Profil</button><select id="user-role-${u.id}">${['USER','ADMIN'].map(x=>option(x,pretty(x),x===u.role)).join('')}</select><button class="admin-secondary" data-role="${u.id}">Rôle</button><select id="user-status-${u.id}">${['ACTIVE','INACTIVE','BLOCKED'].map(x=>option(x,pretty(x),x===u.status)).join('')}</select><button class="admin-secondary" data-user-status="${u.id}">Statut</button></div>`))));
}
async function editUser(id){
  const u=obj(await api('/admin/users/'+id));
  if(!u)throw new Error('Utilisateur introuvable.');
  const fullName=prompt('Nom complet',u.fullName||'');if(fullName===null)return;
  if(!fullName.trim()||fullName.length>150)throw new Error('Nom invalide (150 caractères max).');
  const email=prompt('Email (laisser vide pour ne pas modifier)',u.email||'');if(email===null)return;
  const phone=prompt('Téléphone',u.phone||'');if(phone===null)return;
  const payload={fullName:fullName.trim(),phone:phone.trim()};
  if(email.trim())payload.email=email.trim();
  await api(`/admin/users/${id}/profile`,'PUT',payload);
  await usersPage();notify('Profil utilisateur modifié.');
}
function userChoices(users,includeNone=false){return (includeNone?option('','Non affectée'): '')+users.filter(u=>u.role==='USER'&&u.status==='ACTIVE').map(u=>option(u.id,`${u.fullName||u.username} (#${u.id})`)).join('');}
async function missionsPage(){
  const [mr,ur]=await Promise.all([api('/missions'),api('/admin/users')]);const missions=arr(mr),users=arr(ur);if(page!=='missions')return;
  $('adminContent').innerHTML=panel('Créer une mission',`<form id="createMission" class="admin-form"><div class="admin-two"><label>Titre<input name="title" maxlength="180" required></label><label>Agent<select name="assignedUserId">${userChoices(users,true)}</select></label><label>Début<input type="date" name="startDate"></label><label>Fin<input type="date" name="endDate"></label></div><label>Description<textarea name="description"></textarea></label><button>Créer la mission</button></form>`)+
    panel('Missions et affectations',table(['Mission','Description','Affectation','Dates','Statut','Actions'],missions.map(m=>tr(`<strong>#${m.id} ${esc(m.title)}</strong>`,esc(m.description),esc(users.find(u=>Number(u.id)===Number(m.assigned_user_id))?.fullName||'Non affectée'),`${esc(fmt(m.start_date))} – ${esc(fmt(m.end_date))}`,badge(m.status),`<div class="admin-actions"><button data-edit-mission="${m.id}" class="admin-secondary">Modifier</button><select id="assign-${m.id}">${userChoices(users,true).replace(`value="${m.assigned_user_id}"`,`value="${m.assigned_user_id}" selected`)}</select><button data-assign="${m.id}" class="admin-secondary">Affecter</button><select id="mission-status-${m.id}">${['PENDING','IN_PROGRESS','COMPLETED','CANCELLED'].map(s=>option(s,pretty(s),m.status===s)).join('')}</select><button data-mission-status="${m.id}">Statut</button></div>`))));
}
async function editMission(id){
  const missions=arr(await api('/missions'));const m=missions.find(x=>Number(x.id)===Number(id));if(!m)throw new Error('Mission introuvable.');
  const title=prompt('Titre de la mission',m.title);if(title===null)return;if(!title.trim())throw new Error('Titre obligatoire.');
  const description=prompt('Description de la mission',m.description||'');if(description===null)return;
  const start=prompt('Début (AAAA-MM-JJ, vide pour aucun)',inputDate(m.start_date));if(start===null)return;
  const end=prompt('Fin (AAAA-MM-JJ, vide pour aucun)',inputDate(m.end_date));if(end===null)return;
  if(start.trim()&&!/^\d{4}-\d{2}-\d{2}$/.test(start.trim()))throw new Error('Date de début invalide (AAAA-MM-JJ).');
  if(end.trim()&&!/^\d{4}-\d{2}-\d{2}$/.test(end.trim()))throw new Error('Date de fin invalide (AAAA-MM-JJ).');
  if(start.trim()&&end.trim()&&end<start)throw new Error('Date de fin avant la date de début.');
  await api(`/admin/missions/${id}/details`,'PUT',{title:title.trim(),description});
  await api(`/admin/mission-management/${id}/dates`,'PUT',{startDate:start.trim()||null,endDate:end.trim()||null});
  await missionsPage();notify('Mission modifiée.');
}
const DAYS=[['MONDAY','Lun'],['TUESDAY','Mar'],['WEDNESDAY','Mer'],['THURSDAY','Jeu'],['FRIDAY','Ven'],['SATURDAY','Sam'],['SUNDAY','Dim']];
async function schedulesPage(){
  const [ur,sr]=await Promise.all([api('/admin/users'),api('/schedules')]);const users=arr(ur),schedules=arr(sr);if(page!=='schedules')return;
  $('adminContent').innerHTML=panel('Créer un planning GPS',`<p class="admin-muted">L'agent doit également activer son consentement. Le planning seul ne déclenche jamais sa localisation.</p><form id="scheduleForm" class="admin-form"><div class="admin-two"><label>Agent<select name="userId" required>${userChoices(users)}</select></label><label>Fréquence (secondes)<input type="number" name="frequencySeconds" min="30" max="3600" value="60" required></label><label>Début<input type="time" name="startTime" value="08:00" required></label><label>Fin<input type="time" name="endTime" value="18:00" required></label><label>Fuseau horaire<input name="timezone" value="Africa/Casablanca" required></label><label>Actif<select name="enabled">${option('true','Oui')}${option('false','Non')}</select></label></div><div class="admin-days">${DAYS.map(([v,t])=>`<label><input type="checkbox" name="days" value="${v}" ${v==='SATURDAY'||v==='SUNDAY'?'':'checked'}> ${t}</label>`).join('')}</div><button>Créer le planning</button></form>`)+
    panel('Plannings actuels',table(['ID','Agent','Horaire','Fréquence','Fuseau','État','Action'],schedules.map(s=>tr(esc(s.id),esc(users.find(u=>Number(u.id)===Number(s.user_id))?.fullName||s.user_id),`${esc(s.start_time)} – ${esc(s.end_time)}`,esc(s.frequency_seconds)+' s',esc(s.timezone),s.enabled?'Actif':'Inactif',`<div class="admin-actions"><button data-edit-schedule="${s.id}" class="admin-secondary">Modifier</button><button data-delete-schedule="${s.id}" class="admin-danger">Supprimer</button></div>`))));
}
async function editSchedule(id){
  const plans=arr(await api('/schedules'));const s=plans.find(x=>Number(x.id)===Number(id));
  if(!s)throw new Error('Planning introuvable.');
  const start=prompt('Début (HH:mm:ss)',String(s.start_time||'08:00:00'));if(start===null)return;
  const end=prompt('Fin (HH:mm:ss)',String(s.end_time||'18:00:00'));if(end===null)return;
  const frequency=prompt('Fréquence en secondes (30 à 3600)',String(s.frequency_seconds||60));if(frequency===null)return;
  const tz=prompt('Fuseau horaire',s.timezone||'Africa/Casablanca');if(tz===null)return;
  const daysInput=prompt('Nouveaux jours (MONDAY,TUESDAY,...) : saisissez-les tous. Ils remplaceront les anciens.', 'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY');if(daysInput===null)return;
  const days=daysInput.split(',').map(x=>x.trim().toUpperCase()).filter(Boolean);
  if(!days.length||days.some(d=>!DAYS.some(([name])=>name===d)))throw new Error('Jours invalides.');
  const active=confirm('Activer ce planning ? OK=Oui, Annuler=Non');
  await api(`/admin/schedules/${id}`,'PUT',{startTime:start.trim(),endTime:end.trim(),frequencySeconds:Number(frequency),timezone:tz.trim(),enabled:active,days});
  await schedulesPage();notify('Planning modifié.');
}
// SmartGeo GPS: preserve all rows, draw only the most recent continuous segment.
function sgGpsTime(value){
  if(Array.isArray(value) && value.length>=3){
    return Date.UTC(value[0],value[1]-1,value[2],value[3]||0,value[4]||0,value[5]||0,Math.floor((value[6]||0)/1e6));
  }
  if(typeof value!=='string')return NaN;
  let text=value.replace(' ','T');
  if(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(text))text+='Z';
  return Date.parse(text);
}
function sgGpsRecent(history){
  const points=history.filter(p=>p.latitude!=null&&p.longitude!=null&&String(p.latitude).trim()!==''&&String(p.longitude).trim()!==''&&Number.isFinite(+p.latitude)&&Number.isFinite(+p.longitude)&&Math.abs(+p.latitude)<=90&&Math.abs(+p.longitude)<=180).slice().reverse().map(p=>({raw:p,coord:[+p.latitude,+p.longitude],time:sgGpsTime(p.recorded_at)}));
  let start=0;
  for(let i=1;i<points.length;i++){
    const a=points[i-1],b=points[i],dt=(b.time-a.time)/1000;
    const rad=Math.PI/180, dlat=(b.coord[0]-a.coord[0])*rad,dlon=(b.coord[1]-a.coord[1])*rad;
    const h=Math.sin(dlat/2)**2+Math.cos(a.coord[0]*rad)*Math.cos(b.coord[0]*rad)*Math.sin(dlon/2)**2;
    const distance=6371000*2*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
    if(!Number.isFinite(dt)||dt<0||dt>900||distance>100+dt*(300/3.6))start=i;
  }
  return {points, recent:points.slice(start), split:start};
}

let sgGpsDrawVersion=0;
async function gpsPage(){
  const users=arr(await api('/admin/users')).filter(u=>u.role==='USER');if(page!=='gps')return;
  $('adminContent').innerHTML=panel('Consulter un trajet GPS',`<form id="gpsForm" class="admin-form"><label>Agent<select name="userId" id="gpsUserId">${users.map(u=>option(u.id,`${u.fullName} (#${u.id})`,Number(u.id)===gpsUser)).join('')}</select></label><div class="admin-actions"><button>Charger l'historique</button><label><input type="checkbox" id="gpsRefresh" checked> Actualiser toutes les 15 secondes</label></div></form><p class="admin-muted">Uniquement des positions déjà enregistrées avec le consentement de l'agent et pendant son planning.</p><p id="gpsSummary"></p><div class="admin-map" id="adminMap"></div><div id="gpsHistory"></div>`);
  if(!window.L){notify('Leaflet indisponible : vérifiez votre connexion.',true);return;}
  map=L.map('adminMap').setView([0,0],2);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
  if(users.length){gpsUser=users.some(u=>Number(u.id)===gpsUser)?gpsUser:Number(users[0].id);$('gpsUserId').value=String(gpsUser);await drawGps();}
  if(page!=='gps'||!map)return;
  if(refresh)clearInterval(refresh);
  refresh=setInterval(()=>{if(page==='gps'&&!document.hidden)task(drawGps);},15000);
  setTimeout(()=>{if(page==='gps')map?.invalidateSize();},100);
}
async function drawGps(){
  if(!gpsUser||!map)return;
  const id=gpsUser, currentMap=map, version=++sgGpsDrawVersion;
  const history=arr(await api(`/positions/user/${id}/history?limit=500`));
  if(map!==currentMap||page!=='gps'||gpsUser!==id||version!==sgGpsDrawVersion)return;
  map.eachLayer(layer=>{if(!(layer instanceof L.TileLayer))map.removeLayer(layer);});
  const route=sgGpsRecent(history), coords=route.recent.map(p=>p.coord);
  if(coords.length){
    if(coords.length>1){
      L.polyline(coords,{color:'#1666d4',weight:5}).addTo(map);
      L.circleMarker(coords[0],{color:'#16a34a',radius:8}).addTo(map).bindPopup('Départ du trajet récent');
    }
    const latest=route.recent.at(-1).raw;
    L.circleMarker(coords.at(-1),{color:'#dc2626',radius:9}).addTo(map).bindPopup('Dernier relevé enregistré · précision '+esc(latest.accuracy??'—')+' m · '+esc(fmt(latest.recorded_at)));
    if(coords.every(c=>c[0]===coords[0][0]&&c[1]===coords[0][1]))map.setView(coords.at(-1),16);
    else map.fitBounds(L.latLngBounds(coords).pad(.15),{maxZoom:16});
  } else map.setView([0,0],2);
  $('gpsSummary').textContent=`Agent #${id} : ${route.points.length} relevé(s), ${coords.length} dans le trajet récent. ${route.split?'Rupture détectée : anciens trajets conservés dans le tableau. ':''}Dernière actualisation : ${new Date().toLocaleTimeString('fr-FR')}`;
  $('gpsHistory').innerHTML=table(['Date','Latitude','Longitude','Précision (m)'],history.slice(0,80).map(p=>tr(esc(fmt(p.recorded_at)),esc(p.latitude),esc(p.longitude),esc(p.accuracy??'—'))));
}
async function messagesPage(){
  const [cr,ir]=await Promise.all([api('/messages/contacts'),api('/messages/inbox')]);const contacts=arr(cr),inbox=arr(ir);if(page!=='messages')return;
  $('adminContent').innerHTML=`<div class="admin-grid">${panel('Écrire à un agent',`<form id="adminMessageForm" class="admin-form"><label>Agent<select name="receiverId">${contacts.map(c=>option(c.id,`${c.fullName||c.username} (#${c.id})`)).join('')}</select></label><label>Message<textarea name="content" maxlength="4000" required></textarea></label><button ${contacts.length?'':'disabled'}>Envoyer</button></form>`)}${panel('Conversation',`<form id="adminConversationForm" class="admin-form"><label>Agent<select name="otherId">${contacts.map(c=>option(c.id,`${c.fullName||c.username} (#${c.id})`)).join('')}</select></label><button>Afficher</button></form><div id="adminConversation" class="admin-chat"></div>`)}</div>`+
  panel('Boîte de réception',table(['Agent','Message','Reçu','Statut'],inbox.map(m=>tr(esc(m.sender_name),esc(m.content),esc(fmt(m.sent_at)),m.read_at?'Lu':'Non lu'))));
}
async function showConversation(other){
  chatId=Number(other);const messages=arr(await api('/messages/conversation/'+chatId));const container=$('adminConversation');if(!container)return;
  container.innerHTML=messages.length?messages.map(m=>`<div class="admin-msg ${Number(m.sender_id)===Number(admin.id)?'mine':''}"><strong>${Number(m.sender_id)===Number(admin.id)?'Moi':'Agent'}</strong><p>${esc(m.content)}</p><small>${esc(fmt(m.sent_at))}</small>${Number(m.receiver_id)===Number(admin.id)&&!m.read_at?`<p><button class="admin-secondary" data-message-read="${m.id}">Marquer comme lu</button></p>`:''}</div>`).join(''):'<p>Aucun message.</p>';
}
async function requestsPage(){
  const [ur,qr]=await Promise.all([api('/admin/users'),api('/location-requests')]);const users=arr(ur).filter(u=>u.role==='USER'&&u.status==='ACTIVE'),requests=arr(qr);if(page!=='requests')return;
  $('adminContent').innerHTML=panel('Demander une position',`<p class="admin-muted">L'agent peut accepter en transmettant une position, ou refuser. Aucune collecte n'est lancée automatiquement.</p><form id="locationForm" class="admin-form"><label>Agent<select name="userId">${users.map(u=>option(u.id,`${u.fullName} (#${u.id})`)).join('')}</select></label><button ${users.length?'':'disabled'}>Envoyer la demande</button></form>`)+
    panel('Suivi des demandes',table(['Créée le','Agent','Administrateur','Statut','Réponse','Position'],requests.map(q=>tr(esc(fmt(q.requested_at)),esc(q.user_name||q.user_id),esc(q.admin_name||q.admin_id),badge(q.status),esc(fmt(q.responded_at)),esc(q.position_id||'—')))));
}
async function reportsPage(){
  const reports=arr(await api('/reports'));if(page!=='reports')return;
  $('adminContent').innerHTML=panel('Signalements des agents',table(['ID','Titre','Mission','Agent','Description','Date','Statut','Action'],reports.map(r=>tr(esc(r.id),esc(r.title),esc(r.mission_title||r.mission_id),esc(r.user_name||r.user_id),esc(r.description),esc(fmt(r.created_at)),badge(r.status),`<div class="admin-actions"><select id="report-status-${r.id}">${['SUBMITTED','REVIEWED','REJECTED'].map(s=>option(s,pretty(s),r.status===s)).join('')}</select><button data-report-status="${r.id}">Enregistrer</button></div>`))));
}
$('logoutBtn').addEventListener('click',logout);
document.addEventListener('click',e=>{
  const b=e.target.closest('button');if(!b)return;
  if(b.dataset.page){openPage(b.dataset.page);return;}
  if(b.dataset.editUser)task(()=>editUser(b.dataset.editUser));
  if(b.dataset.editSchedule)task(()=>editSchedule(b.dataset.editSchedule));
  if(b.dataset.role)task(async()=>{await api(`/admin/users/${b.dataset.role}/role`,'PUT',{role:$('user-role-'+b.dataset.role).value});await usersPage();notify('Rôle modifié.');});
  if(b.dataset.userStatus)task(async()=>{await api(`/admin/users/${b.dataset.userStatus}/status`,'PUT',{status:$('user-status-'+b.dataset.userStatus).value});await usersPage();notify('Statut modifié.');});
  if(b.dataset.assign)task(async()=>{const user=$('assign-'+b.dataset.assign).value;if(user)await api(`/admin/missions/${b.dataset.assign}/assign`,'PUT',{assignedUserId:Number(user)});else await api(`/admin/mission-management/${b.dataset.assign}/unassign`,'PUT');await missionsPage();notify('Affectation enregistrée.');});
  if(b.dataset.editMission)task(()=>editMission(b.dataset.editMission));
  if(b.dataset.missionStatus)task(async()=>{await api(`/missions/${b.dataset.missionStatus}/status`,'PUT',{status:$('mission-status-'+b.dataset.missionStatus).value});await missionsPage();notify('Statut de mission modifié.');});
  if(b.dataset.reportStatus)task(async()=>{await api(`/reports/${b.dataset.reportStatus}/status`,'PUT',{status:$('report-status-'+b.dataset.reportStatus).value});await reportsPage();notify('Signalement traité.');});
  if(b.dataset.deleteSchedule)task(async()=>{if(!confirm('Supprimer définitivement ce planning ?'))return;await api(`/schedules/${b.dataset.deleteSchedule}`,'DELETE');await schedulesPage();notify('Planning supprimé.');});
  if(b.dataset.messageRead)task(async()=>{await api(`/messages/${b.dataset.messageRead}/read`,'PUT');await showConversation(chatId);});
});
document.addEventListener('submit',e=>{
  const f=e.target;const supported=['createUser','createMission','scheduleForm','gpsForm','adminMessageForm','adminConversationForm','locationForm'];
  if(!supported.includes(f.id))return;e.preventDefault();task(async()=>{
    const d=new FormData(f);
    if(f.id==='createUser'){await api('/admin/users','POST',{fullName:String(d.get('fullName')).trim(),username:String(d.get('username')).trim(),email:String(d.get('email')).trim(),phone:String(d.get('phone')).trim(),password:String(d.get('password')),role:String(d.get('role'))});await usersPage();notify('Utilisateur créé.');}
    if(f.id==='createMission'){await api('/missions','POST',{title:String(d.get('title')).trim(),description:String(d.get('description')).trim(),assignedUserId:d.get('assignedUserId')?Number(d.get('assignedUserId')):null,startDate:d.get('startDate')||null,endDate:d.get('endDate')||null,status:'PENDING'});await missionsPage();notify('Mission créée.');}
    if(f.id==='scheduleForm'){const start=String(d.get('startTime'));const end=String(d.get('endTime'));const days=d.getAll('days');if(!days.length)throw new Error('Choisissez au moins un jour.');await api('/admin/schedules','POST',{userId:Number(d.get('userId')),startTime:start.length===5?start+':00':start,endTime:end.length===5?end+':00':end,frequencySeconds:Number(d.get('frequencySeconds')),timezone:String(d.get('timezone')).trim(),enabled:d.get('enabled')==='true',days});await schedulesPage();notify('Planning GPS créé.');}
    if(f.id==='gpsForm'){gpsUser=Number(d.get('userId'));await drawGps();notify('Historique GPS chargé.');}
    if(f.id==='adminMessageForm'){await api('/messages','POST',{receiverId:Number(d.get('receiverId')),content:String(d.get('content')).trim()});f.reset();notify('Message envoyé.');}
    if(f.id==='adminConversationForm'){await showConversation(Number(d.get('otherId')));}
    if(f.id==='locationForm'){await api('/location-requests','POST',{userId:Number(d.get('userId'))});await requestsPage();notify('Demande envoyée.');}
  });
});
document.addEventListener('change',e=>{
  if(e.target.id==='gpsRefresh'){
    if(refresh){clearInterval(refresh);refresh=null;}
    if(e.target.checked)refresh=setInterval(()=>{if(page==='gps'&&!document.hidden)task(drawGps);},15000);
  }
});
task(async()=>{admin=obj(await api('/users/me'));if(admin?.role!=='ADMIN'){logout();return;}openPage('home');});
