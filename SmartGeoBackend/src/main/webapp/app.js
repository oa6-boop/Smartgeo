'use strict';

// SmartGeo - espace agent. La position n'est envoyee que sur action explicite.
if (!Auth.getToken()) window.location.replace('index.html');
const el = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const array = value => Array.isArray(value) ? value : Array.isArray(value?.data) ? value.data : [];
const unpack = value => value && 'data' in value ? value.data : value;
const label = v => ({PENDING:'En attente',IN_PROGRESS:'En cours',COMPLETED:'Terminée',CANCELLED:'Annulée',SUBMITTED:'Soumis',REVIEWED:'Examiné',REJECTED:'Rejeté',REFUSED:'Refusée',TRANSMITTED:'Transmise',ALLOWED:'Autorisé',CONSENT_DISABLED:'Consentement désactivé',OUTSIDE_SCHEDULE:'Hors planning',ACCOUNT_INACTIVE:'Compte inactif'}[v] || v || '—');
const pill = v => `<span class="pill ${esc(v)}">${esc(label(v))}</span>`;
const date = v => {if (!v) return '—'; if (Array.isArray(v)) return v.slice(0,6).join('/'); const d = new Date(v); return isNaN(d) ? esc(v) : d.toLocaleString('fr-FR');};
const option = (id,text,selected=false) => `<option value="${esc(id)}" ${selected?'selected':''}>${esc(text)}</option>`;
const table = (headers, rows) => `<div class="table-wrap"><table><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.join(''):`<tr><td colspan="${headers.length}" class="empty">Aucune donnée disponible</td></tr>`}</tbody></table></div>`;
const tr = (...cells) => `<tr>${cells.map(c=>`<td>${c}</td>`).join('')}</tr>`;
const panel = (title,html) => `<div class="panel"><h2>${esc(title)}</h2>${html}</div>`;
let currentUser = null, page = 'home', map = null, activeContact = 0;
function info(text, error=false) {const n=el('notice');n.hidden=false;n.className='notice'+(error?' error-notice':'');n.textContent=text;}
function clearMap() { if(map){map.remove();map=null;} }
async function run(fn) { try { await fn(); } catch (e) { console.error(e);info(e.message || 'Erreur inconnue.',true); } }
function openPage(next) {
  clearMap();page=next;activeContact=0;el('notice').hidden=true;
  const labels={home:'Tableau de bord',missions:'Mes missions',gps:'Ma localisation',messages:'Messagerie',requests:'Demandes de position',reports:'Signalements',profile:'Mon profil'};
  el('pageTitle').textContent=labels[next]||'SmartGeo';
  document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===next));
  document.querySelector('.sidebar').classList.remove('open');
  el('content').innerHTML='<div class="panel muted">Chargement…</div>';
  const actions={home:homePage,missions:missionsPage,gps:gpsPage,messages:messagesPage,requests:requestsPage,reports:reportsPage,profile:profilePage};
  run(async()=>{await actions[next]();});
}
async function homePage(){
  const [missions,reports,requests,status]=await Promise.all([Auth.request('/missions'),Auth.request('/reports'),Auth.request('/location-requests'),Auth.request('/gps/status').catch(()=>null)]);
  const m=array(missions),r=array(reports),q=array(requests),s=unpack(status);
  if(page!=='home')return;
  el('content').innerHTML=`<div class="cards"><div class="card"><span class="muted">Missions</span><div class="metric">${m.length}</div></div><div class="card"><span class="muted">Demandes en attente</span><div class="metric">${q.filter(x=>x.status==='PENDING').length}</div></div><div class="card"><span class="muted">Signalements</span><div class="metric">${r.length}</div></div></div>`+
    panel('Suivi GPS',`<p>${pill(s?.status)}</p><p class="muted">${esc(s?.message||'Statut indisponible')}</p><button class="secondary" data-go="gps">Gérer mon GPS</button>`)+
    panel('Missions récentes',table(['Mission','Statut','Début','Fin'],m.slice(0,5).map(x=>tr(esc(x.title),pill(x.status),esc(date(x.start_date)),esc(date(x.end_date))))));
}
async function missionsPage(){
  const data=array(await Auth.request('/missions'));if(page!=='missions')return;
  el('content').innerHTML=panel('Missions affectées',`<p class="muted">Vous pouvez démarrer ou terminer vos missions. L’affectation et l’édition sont réservées à l’administrateur.</p>`+table(['Mission','Description','Période','Statut','Modifier'],data.map(m=>tr(esc(m.title),esc(m.description),`${esc(date(m.start_date))} – ${esc(date(m.end_date))}`,pill(m.status),`<div class="actions"><select class="inline-select" id="mission-${m.id}" ${m.status==='CANCELLED'?'disabled':''}>${['PENDING','IN_PROGRESS','COMPLETED'].map(s=>option(s,label(s),s===m.status)).join('')}</select><button class="secondary" data-mission="${m.id}" ${m.status==='CANCELLED'?'disabled':''}>Enregistrer</button></div>`))));
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

async function gpsPage(){
  const [consentResult,scheduleResult,statusResult,historyResult]=await Promise.allSettled([Auth.request('/gps-consent/me'),Auth.request('/schedules/me'),Auth.request('/gps/status'),Auth.request('/positions/me/history?limit=150')]);
  const val=r=>r.status==='fulfilled'?unpack(r.value):null;
  const consent=val(consentResult),plans=array(val(scheduleResult)),gps=val(statusResult),positions=array(val(historyResult));
  if(page!=='gps')return;
  el('content').innerHTML=panel('Autorisation et envoi GPS',`<p class="muted">SmartGeo ne démarre aucun suivi automatique dans cette page. Chaque transmission nécessite votre action et la permission du navigateur. L'envoi reste soumis au planning configuré.</p><p><strong>Statut :</strong> ${pill(gps?.status)}</p><p><strong>Consentement :</strong> ${consent?.enabled?'Activé':'Désactivé'}</p><div class="actions"><button class="secondary" data-consent="${consent?.enabled?'false':'true'}">${consent?.enabled?'Désactiver le consentement':'Activer mon consentement'}</button><button class="primary" data-gps-send>Envoyer ma position actuelle</button></div><p class="small muted">La géolocalisation nécessite localhost ou HTTPS et votre autorisation.</p>`)+
  panel('Planning GPS',table(['Début','Fin','Fréquence','Fuseau','Activé'],plans.map(s=>tr(esc(s.start_time),esc(s.end_time),esc(s.frequency_seconds)+' s',esc(s.timezone),s.enabled?'Oui':'Non'))))+
  panel('Positions enregistrées',`<div id="map"></div><p class="map-note">Trajet récent uniquement. Les anciens relevés restent dans le tableau.</p>`+table(['Date','Latitude','Longitude','Précision'],positions.slice(0,20).map(p=>tr(esc(date(p.recorded_at)),esc(p.latitude),esc(p.longitude),esc(p.accuracy??'—')))));
  if(!window.L){info('Leaflet indisponible : carte désactivée.',true);return;}
  map=L.map('map').setView([0,0],2);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
  const coords=sgGpsRecent(positions).recent.map(p=>p.coord);
  if(coords.length){coords.forEach((c,i)=>L.circleMarker(c,{radius:i===coords.length-1?9:5}).addTo(map));if(coords.length>1)L.polyline(coords).addTo(map);if(coords.every(c=>c[0]===coords[0][0]&&c[1]===coords[0][1]))map.setView(coords.at(-1),16);else map.fitBounds(L.latLngBounds(coords).pad(.15),{maxZoom:16});}
  setTimeout(()=>{if(map)map.invalidateSize();},100);
}
function getPosition(){
  return new Promise((resolve,reject)=>{
    if(!navigator.geolocation){reject(new Error('Votre navigateur ne propose pas la géolocalisation.'));return;}
    navigator.geolocation.getCurrentPosition(resolve,err=>reject(new Error('GPS : '+err.message)),{enableHighAccuracy:true,timeout:20000,maximumAge:0});
  });
}
async function transmitPosition(){
  const status=unpack(await Auth.request('/gps/status'));
  if(!status?.allowed)throw new Error(status?.message||'GPS non autorisé. Vérifiez consentement et planning.');
  const position=await getPosition();
  const p=position.coords;
  const uuid=typeof crypto!=='undefined'&&crypto.randomUUID?crypto.randomUUID():"xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g,c=>{const r=Math.floor(Math.random()*16);return(c==='x'?r:(r&3|8)).toString(16);});
  const response=await Auth.request('/positions',{method:'POST',body:{latitude:p.latitude,longitude:p.longitude,accuracy:p.accuracy,speed:Number.isFinite(p.speed)?p.speed:null,bearing:Number.isFinite(p.heading)?p.heading:null,batteryLevel:null,recordedAt:new Date(position.timestamp).toISOString(),clientPositionId:uuid}});
  const id=Number(unpack(response)?.id||response?.id);
  if(!id)throw new Error('Position envoyée, mais identifiant absent.');
  return id;
}
async function messagesPage(){
  const [contactsResult,inboxResult]=await Promise.all([Auth.request('/messages/contacts'),Auth.request('/messages/inbox')]);
  const contacts=array(contactsResult),inbox=array(inboxResult);if(page!=='messages')return;
  el('content').innerHTML=`<div class="grid2">${panel('Envoyer un message',`<form id="messageForm"><label>Administrateur<select name="receiverId" required>${contacts.map(c=>option(c.id,`${c.fullName||c.username} (#${c.id})`)).join('')}</select></label><label>Message<textarea name="content" maxlength="4000" required></textarea></label><button class="primary" ${contacts.length?'':'disabled'}>Envoyer</button></form>`)}${panel('Conversation',`<form id="conversationForm"><label>Contact<select name="otherId" required>${contacts.map(c=>option(c.id,`${c.fullName||c.username} (#${c.id})`)).join('')}</select></label><button class="secondary">Ouvrir</button></form><div id="conversation"></div>`)}</div>`+
  panel('Boîte de réception',table(['Expéditeur','Message','Reçu','Lu'],inbox.map(m=>tr(esc(m.sender_name),esc(m.content),esc(date(m.sent_at)),m.read_at?'Oui':'Non'))));
}
async function conversation(other){
  const data=array(await Auth.request('/messages/conversation/'+encodeURIComponent(other)));activeContact=Number(other);
  const target=el('conversation');if(!target)return;
  target.innerHTML=data.length?data.map(m=>`<div class="message ${Number(m.sender_id)===Number(currentUser.id)?'mine':''}"><strong>${Number(m.sender_id)===Number(currentUser.id)?'Moi':'Administrateur'}</strong><p>${esc(m.content)}</p><small>${esc(date(m.sent_at))}</small>${Number(m.receiver_id)===Number(currentUser.id)&&!m.read_at?`<button class="secondary" data-read="${m.id}">Marquer comme lu</button>`:''}</div>`).join(''):'<p class="empty">Aucun message.</p>';
}
async function requestsPage(){
  const requests=array(await Auth.request('/location-requests'));if(page!=='requests')return;
  el('content').innerHTML=panel('Demandes reçues',`<p class="muted">Transmettre : le navigateur demande votre position, puis la partage uniquement si vous avez activé votre consentement et si le planning autorise le GPS. Refuser ne partage aucune position.</p>`+table(['Date','Administrateur','Statut','Réponse'],requests.map(x=>tr(esc(date(x.requested_at)),esc(x.admin_name||x.admin_id),pill(x.status),x.status==='PENDING'?`<div class="actions"><button class="primary" data-request-send="${x.id}">Transmettre ma position</button><button class="danger-btn" data-request-refuse="${x.id}">Refuser</button></div>`:'—'))));
}
async function reportsPage(){
  const [missionsResult,reportsResult]=await Promise.all([Auth.request('/missions'),Auth.request('/reports')]);
  const missions=array(missionsResult).filter(m=>m.status!=='CANCELLED'),reports=array(reportsResult);if(page!=='reports')return;
  el('content').innerHTML=panel('Créer un signalement',`<form id="reportForm"><label>Mission<select name="missionId" required>${missions.map(m=>option(m.id,m.title)).join('')}</select></label><label>Titre<input name="title" maxlength="180" required></label><label>Description<textarea name="description" maxlength="20000" required></textarea></label><button class="primary" ${missions.length?'':'disabled'}>Envoyer le rapport</button></form>`)+panel('Mes signalements',table(['Titre','Mission','Description','Statut','Créé le'],reports.map(r=>tr(esc(r.title),esc(r.mission_title||r.mission_id),esc(r.description),pill(r.status),esc(date(r.created_at))))));
}
async function profilePage(){
  const user=unpack(await Auth.request('/users/me'));currentUser=user;if(page!=='profile')return;
  el('content').innerHTML=panel('Modifier mon profil',`<form id="profileForm"><div class="grid2"><label>Nom complet<input name="fullName" maxlength="150" value="${esc(user.fullName)}" required></label><label>Nom d'utilisateur<input value="${esc(user.username)}" disabled></label><label>Email<input type="email" name="email" maxlength="160" value="${esc(user.email||'')}"></label><label>Téléphone<input name="phone" maxlength="30" value="${esc(user.phone||'')}"></label></div><p class="muted">Rôle : ${esc(user.role)} · Statut : ${esc(user.status)}</p><button class="primary">Enregistrer les modifications</button></form>`);
}
document.addEventListener('click',e=>{
  const t=e.target.closest('button');if(!t)return;
  if(t.dataset.page)openPage(t.dataset.page);
  if(t.dataset.go)openPage(t.dataset.go);
  if(t.dataset.mission)run(async()=>{await Auth.request('/missions/'+t.dataset.mission+'/status',{method:'PUT',body:{status:el('mission-'+t.dataset.mission).value}});await missionsPage();info('Mission mise à jour.');});
  if(t.hasAttribute('data-consent'))run(async()=>{await Auth.request('/gps-consent/me',{method:'PUT',body:{enabled:t.dataset.consent==='true'}});await gpsPage();info('Consentement modifié.');});
  if(t.hasAttribute('data-gps-send'))run(async()=>{t.disabled=true;try{await transmitPosition();await gpsPage();info('Position GPS enregistrée.');}finally{t.disabled=false;}});
  if(t.dataset.requestRefuse)run(async()=>{if(!confirm('Refuser cette demande ?'))return;await Auth.request('/location-requests/'+t.dataset.requestRefuse+'/respond',{method:'PUT',body:{status:'REFUSED'}});await requestsPage();info('Demande refusée.');});
  if(t.dataset.requestSend)run(async()=>{if(!confirm('Partager votre position actuelle avec l’administrateur ?'))return;t.disabled=true;try{const id=await transmitPosition();await Auth.request('/location-requests/'+t.dataset.requestSend+'/respond',{method:'PUT',body:{status:'TRANSMITTED',positionId:id}});await requestsPage();info('Position transmise avec votre accord.');}finally{t.disabled=false;}});
  if(t.dataset.read)run(async()=>{await Auth.request('/messages/'+t.dataset.read+'/read',{method:'PUT'});await conversation(activeContact);});
});
document.addEventListener('submit',e=>{
  const f=e.target;if(!['messageForm','conversationForm','reportForm','profileForm'].includes(f.id))return;e.preventDefault();
  run(async()=>{
    const d=new FormData(f);
    if(f.id==='messageForm'){await Auth.request('/messages',{method:'POST',body:{receiverId:Number(d.get('receiverId')),content:String(d.get('content')).trim()}});f.reset();info('Message envoyé.');}
    if(f.id==='conversationForm')await conversation(Number(d.get('otherId')));
    if(f.id==='reportForm'){await Auth.request('/reports',{method:'POST',body:{missionId:Number(d.get('missionId')),title:String(d.get('title')).trim(),description:String(d.get('description')).trim()}});await reportsPage();info('Signalement envoyé.');}
    if(f.id==='profileForm'){await Auth.request('/users/me',{method:'PUT',body:{fullName:String(d.get('fullName')).trim(),email:String(d.get('email')).trim(),phone:String(d.get('phone')).trim()}});await profilePage();info('Profil mis à jour.');}
  });
});
el('logout').addEventListener('click',()=>Auth.logout());
el('menuToggle').addEventListener('click',()=>document.querySelector('.sidebar').classList.toggle('open'));
run(async()=>{currentUser=unpack(await Auth.request('/users/me'));if(currentUser?.role!=='USER'){Auth.logout();return;}el('userBadge').textContent=currentUser.fullName||currentUser.username;openPage('home');});
