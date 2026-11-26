
'use strict';

(function () {

    const LIMIT = 2000;
    const REFRESH = 15000;

    const num = v =>
        v == null || String(v).trim() === ''
            ? NaN : Number(v);

    const date = v =>
        new Date(v).toLocaleString('fr-FR');

    const clock = v =>
        new Date(v).toLocaleTimeString('fr-FR');

    const speed = v =>
        Number.isFinite(v)
            ? v.toFixed(1) + ' km/h'
            : '—';

    function timeOf(v) {

        if (Array.isArray(v)) {

            return Date.UTC(
                v[0], v[1] - 1, v[2],
                v[3] || 0, v[4] || 0, v[5] || 0,
                Math.floor((v[6] || 0) / 1e6)
            );
        }

        if (typeof v !== 'string') return NaN;

        let s = v.replace(' ', 'T');

        if (
            /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/
                .test(s)
        ) {
            s += 'Z';
        }

        return Date.parse(s);
    }

    function duration(seconds) {

        const s = Math.max(0, Math.floor(seconds));

        return [
            Math.floor(s / 3600) + ' h',
            Math.floor(s % 3600 / 60) + ' min',
            s % 60 + ' s'
        ].join(' ');
    }

    function localInput(d) {

        return new Date(
            d.getTime() -
            d.getTimezoneOffset() * 60000
        ).toISOString().slice(0, 16);
    }

    function validPoint(p) {

        return Number.isFinite(p.lat) &&
            Number.isFinite(p.lon) &&
            Math.abs(p.lat) <= 90 &&
            Math.abs(p.lon) <= 180 &&
            Number.isFinite(p.time);
    }

    function meters(a, b) {

        const r = Math.PI / 180;

        const h =
            Math.sin((b.lat - a.lat) * r / 2) ** 2 +
            Math.cos(a.lat * r) *
            Math.cos(b.lat * r) *
            Math.sin((b.lon - a.lon) * r / 2) ** 2;

        return 12742000 *
            Math.asin(Math.sqrt(
                Math.min(1, Math.max(0, h))
            ));
    }

    function splitTrips(rows) {

        const points = rows.map(p => ({
            id: p.id,
            lat: num(p.latitude),
            lon: num(p.longitude),
            time: timeOf(p.recorded_at),
            speed: num(p.speed),
            accuracy: num(p.accuracy)
        })).filter(validPoint).sort(
            (a, b) => a.time - b.time ||
                Number(a.id) - Number(b.id)
        );

        const trips = [];

        for (const p of points) {

            const trip = trips.at(-1);
            const last = trip?.at(-1);

            const seconds = last
                ? (p.time - last.time) / 1000
                : 0;

            if (
                !last ||
                seconds > 900 ||
                meters(last, p) >
                100 + seconds * (300 / 3.6)
            ) {
                trips.push([p]);
            } else {
                trip.push(p);
            }
        }

        return trips;
    }

    function stats(points) {

        let distance = 0;

        for (let i = 1; i < points.length; i++) {

            distance += meters(
                points[i - 1],
                points[i]
            );
        }

        const seconds =
            (points.at(-1).time -
                points[0].time) / 1000;

        const speeds = points
            .map(p => p.speed)
            .filter(v =>
                Number.isFinite(v) && v >= 0
            );

        return {
            distance,
            seconds,
            average: seconds > 0
                ? distance / seconds * 3.6
                : NaN,
            maximum: speeds.length
                ? Math.max(...speeds) * 3.6
                : NaN
        };
    }

    function metric(title, value, note = '') {

        return `
            <div class="sgv-metric">
                <span>${esc(title)}</span>
                <strong>${esc(value)}</strong>
                <small>${esc(note)}</small>
            </div>
        `;
    }

    function agentStatus(a) {

        switch (a.gpsStatus) {

            case 'ACCOUNT_INACTIVE':
                return 'Compte inactif';

            case 'CONSENT_DISABLED':
                return 'Partage désactivé';

            case 'OUTSIDE_SCHEDULE':
                return 'Hors planning';

            case 'ALLOWED':
                return a.recent
                    ? 'Relevé récent'
                    : 'Autorisé · aucun relevé récent';

            default:
                return 'État indisponible';
        }
    }

    function agentColor(a) {

        return a.gpsStatus === 'ALLOWED' &&
        a.recent
            ? '#5271DE'
            : '#8593A7';
    }

    // =============================================
    // TABLEAU DE BORD ADMIN
    // =============================================

    homePage = async function () {

        const root = document.createElement('section');

        root.className = 'sgv';

        $('adminContent').replaceChildren(root);

        root.innerHTML =
            '<p>Chargement des statistiques…</p>';

        const [response, missionsResult] =
            await Promise.all([
                api('/admin/supervision'),
                api('/missions')
            ]);

        if (
            page !== 'home' ||
            !root.isConnected
        ) return;

        const data = obj(response);

        const s = data.statistics;

        const missions = arr(missionsResult);

        const peak = Math.max(
            1,
            ...data.activity.map(d => Number(d.total))
        );

        root.innerHTML = `

            <div class="sgv-heading">
                <div>
                    <h2>Tableau de bord SmartGeo</h2>
                    <p>Supervision et activité générale</p>
                </div>

                <button type="button" id="sgvOpenGps">
                    Ouvrir la carte générale
                </button>
            </div>

            <div class="sgv-kpis">

                ${metric('Agents', s.agents_total)}

                ${metric(
            'Comptes actifs',
            s.agents_active
        )}

                ${metric(
            'Positions récentes',
            s.agents_recent,
            'Relevés des 120 dernières secondes'
        )}

                ${metric(
            'Missions en cours',
            s.missions_in_progress
        )}

                ${metric(
            'Missions terminées',
            s.missions_completed
        )}

                ${metric(
            'Signalements à traiter',
            s.pending_reports
        )}

                ${metric(
            'Messages non lus',
            s.unread_messages
        )}

                ${metric(
            'Demandes GPS',
            s.pending_requests
        )}

            </div>

            <section class="sgv-card">

                <h3>Activité GPS des 7 derniers jours</h3>

                <div class="sgv-chart">

                    ${data.activity.map(d => `

                        <div class="sgv-chart-column">

                            <strong>${esc(d.total)}</strong>

                            <div class="sgv-chart-track">

                                <div class="sgv-chart-bar"

                                     style="height:${
            Number(d.total) /
            peak * 100
        }%">

                                </div>

                            </div>

                            <small>
                                ${esc(d.date.slice(8))}/${
            esc(d.date.slice(5, 7))
        }
                            </small>

                        </div>

                    `).join('')}

                </div>

                <p class="sgv-note">
                    Nombre de relevés GPS enregistrés par jour UTC.
                </p>

            </section>

            <section class="sgv-card">

                <h3>État des agents</h3>

                ${table(
            [
                'Agent',
                'État GPS',
                'Dernier relevé',
                'Action'
            ],

            data.agents.map(a => tr(

                esc(a.fullName),

                esc(agentStatus(a)),

                a.recorded_at
                    ? esc(date(a.recorded_at))
                    : '—',

                `<button type="button"
                                 class="admin-secondary"
                                 data-sgv-agent="${Number(a.id)}">

                            Voir

                        </button>`
            ))
        )}

            </section>

            <section class="sgv-card">

                <h3>Missions récentes</h3>

                ${table(

            ['Mission', 'Agent', 'Statut'],

            missions.slice(0, 8).map(m => tr(

                esc(m.title),

                esc(
                    data.agents.find(a =>
                        Number(a.id) ===
                        Number(m.assigned_user_id)
                    )?.fullName || 'Non affectée'
                ),

                badge(m.status)
            ))
        )}

            </section>
        `;

        root.querySelector('#sgvOpenGps')
            .onclick = () => openPage('gps');

        root.querySelectorAll('[data-sgv-agent]')
            .forEach(b => {

                b.onclick = () => {

                    gpsUser =
                        Number(b.dataset.sgvAgent);

                    initialMode = 'live';

                    openPage('gps');
                };
            });
    };

    // =============================================
    // NOUVELLE CARTE GPS
    // =============================================

    let initialMode = 'global';

    gpsPage = async function () {

        const root = document.createElement('section');

        root.className = 'sgv';

        $('adminContent').replaceChildren(root);

        root.innerHTML =
            '<p>Chargement de la carte…</p>';

        const users = arr(
            await api('/admin/users')
        ).filter(u => u.role === 'USER');

        if (
            page !== 'gps' ||
            !root.isConnected
        ) return;

        if (!window.L) {

            root.textContent =
                'Leaflet indisponible. Vérifiez Internet.';

            return;
        }

        const now = new Date();

        const today = new Date(now);

        today.setHours(0, 0, 0, 0);

        root.innerHTML = `

            <div class="sgv-heading">

                <div>
                    <h2>Supervision GPS</h2>
                    <p>Localisation et historique des agents</p>
                </div>

                <div class="sgv-tabs">

                    <button type="button"
                            data-mode="global">
                        Vue globale
                    </button>

                    <button type="button"
                            data-mode="live">
                        Suivi individuel
                    </button>

                    <button type="button"
                            data-mode="history">
                        Historique
                    </button>

                </div>

            </div>

            <div class="sgv-layout">

                <aside class="sgv-sidebar">

                    <section class="sgv-card"
                             id="sgvGlobal">

                        <h3>Tous les agents</h3>

                        <input id="sgvSearch"
                               placeholder="Rechercher un agent…">

                        <div id="sgvAgents"></div>

                    </section>

                    <section class="sgv-card"
                             id="sgvIndividual"
                             hidden>

                        <h3>Rechercher un trajet</h3>

                        <label>Utilisateur</label>

                        <select id="sgvUser">

                            ${users.map(u => option(

            u.id,

            u.fullName || u.username,

            Number(u.id) === gpsUser

        )).join('')}

                        </select>

                        <div id="sgvDates" hidden>

                            <label>Du</label>

                            <input type="datetime-local"
                                   id="sgvFrom"
                                   value="${localInput(today)}">

                            <label>Au</label>

                            <input type="datetime-local"
                                   id="sgvTo"
                                   value="${localInput(now)}">

                            <p class="sgv-note">
                                Heure locale du navigateur.
                            </p>

                        </div>

                        <button type="button"
                                id="sgvLoad">
                            Actualiser
                        </button>

                        <div id="sgvTripSelect" hidden>

                            <label>Trajet</label>

                            <select id="sgvTripList"></select>

                        </div>

                    </section>

                    <section class="sgv-card">

                        <h3>Informations</h3>

                        <p id="sgvStatus"
                           class="sgv-note"
                           role="status"></p>

                        <div id="sgvMetrics"
                             class="sgv-metrics"></div>

                    </section>

                </aside>

                <section class="sgv-card sgv-map-card">

                    <div class="sgv-map-toolbar">

                        <strong>Carte interactive</strong>

                        <button type="button"
                                id="sgvFullscreen"
                                class="admin-secondary">
                            Plein écran
                        </button>

                    </div>

                    <div id="sgvMap"></div>

                    <p class="sgv-note">
                        Bleu : relevé récent ou trajet.
                        Gris : ancienne position.
                        Rose : dernier point du trajet.
                    </p>

                </section>

            </div>

            <section id="sgvHistory"
                     class="sgv-card"
                     hidden>

                <div class="sgv-heading">

                    <h3>Relevés du trajet</h3>

                    <div class="sgv-actions">

                        <button type="button"
                                id="sgvCsv">
                            Exporter CSV
                        </button>

                        <button type="button"
                                id="sgvPdf"
                                class="admin-secondary">
                            Exporter PDF
                        </button>

                    </div>

                </div>

                <div id="sgvRows"></div>

            </section>
        `;

        const q = id =>
            root.querySelector('#' + id);

        map = L.map(q('sgvMap')).setView(
            [0, 0], 2
        );

        const currentMap = map;

        L.tileLayer(
            'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
            {
                maxZoom: 19,
                attribution: '© OpenStreetMap'
            }
        ).addTo(currentMap);

        const layers =
            L.featureGroup().addTo(currentMap);

        let mode = initialMode;

        initialMode = 'global';

        let agents = [];
        let trips = [];
        let selected = [];

        let version = 0;
        let busy = false;
        let fitNext = true;
        let limited = false;

        const active = () =>
            root.isConnected &&
            page === 'gps' &&
            map === currentMap;

        function clear() {

            layers.clearLayers();

            selected = [];
            trips = [];

            q('sgvMetrics').innerHTML = '';
            q('sgvRows').innerHTML = '';

            q('sgvTripList').innerHTML = '';

            q('sgvTripSelect').hidden = true;

            q('sgvCsv').disabled = true;
            q('sgvPdf').disabled = true;
        }

        function fit(coords) {

            if (!fitNext) return;

            currentMap.invalidateSize();

            if (!coords.length) {

                currentMap.setView([0, 0], 2);

            } else {

                currentMap.fitBounds(
                    L.latLngBounds(coords).pad(0.15),
                    {maxZoom: 16}
                );
            }

            fitNext = false;
        }

        function openAgent(id) {

            const select = q('sgvUser');

            if (
                !Array.from(select.options).some(o =>
                    Number(o.value) === id
                )
            ) {

                // Un nouveau compte a pu etre cree
                // depuis l'ouverture de la page.
                initialMode = 'live';
                gpsUser = id;
                openPage('gps');
                return;
            }

            select.value = String(id);

            setMode('live');
        }

        function renderGlobal() {

            if (!active() || mode !== 'global') return;

            layers.clearLayers();

            const search =
                q('sgvSearch').value.trim().toLowerCase();

            const shown = agents.filter(a =>
                `${a.fullName} ${a.username}`
                    .toLowerCase()
                    .includes(search)
            );

            const coords = [];

            q('sgvAgents').innerHTML =
                shown.map(a => `

                    <button type="button"
                            class="sgv-agent"
                            data-agent="${Number(a.id)}">

                        <i style="background:${agentColor(a)}"></i>

                        <span>

                            <strong>${esc(a.fullName)}</strong>

                            <small>
                                ${esc(agentStatus(a))}
                            </small>

                            <small>
                                ${a.recorded_at
                    ? esc(date(a.recorded_at))
                    : 'Aucune position affichable'}
                            </small>

                        </span>

                    </button>

                `).join('') ||
                '<p class="sgv-note">Aucun agent trouvé.</p>';

            q('sgvAgents')
                .querySelectorAll('[data-agent]')
                .forEach(b => {

                    b.onclick = () =>
                        openAgent(Number(b.dataset.agent));
                });

            for (const a of shown) {

                const lat = num(a.latitude);
                const lon = num(a.longitude);

                if (
                    !Number.isFinite(lat) ||
                    !Number.isFinite(lon) ||
                    Math.abs(lat) > 90 ||
                    Math.abs(lon) > 180 ||
                    !a.recorded_at
                ) continue;

                coords.push([lat, lon]);

                const details =
                    document.createElement('div');

                details.innerHTML = `

                    <strong>${esc(a.fullName)}</strong>

                    <p>${esc(agentStatus(a))}</p>

                    <p>
                        Dernier relevé :
                        ${esc(date(a.recorded_at))}
                    </p>

                    <p>
                        Précision :
                        ${esc(a.accuracy ?? '—')} m
                    </p>

                    <p>
                        Vitesse :
                        ${esc(speed(num(a.speed) * 3.6))}
                    </p>

                    <p>
                        Batterie au dernier relevé :
                        ${esc(a.battery_level ?? '—')} %
                    </p>
                `;

                const view =
                    document.createElement('button');

                view.textContent =
                    'Consulter son trajet';

                view.onclick = () =>
                    openAgent(Number(a.id));

                details.appendChild(view);

                const request =
                    document.createElement('button');

                request.textContent =
                    'Demander sa position';

                request.onclick = async () => {

                    if (!confirm(
                        'Envoyer une demande de localisation à cet agent ?'
                    )) return;

                    request.disabled = true;

                    try {

                        await api(
                            '/location-requests',
                            'POST',
                            {userId: Number(a.id)}
                        );

                        notify('Demande envoyée.');

                    } catch (e) {

                        notify(e.message, true);

                    } finally {

                        request.disabled = false;
                    }
                };

                details.appendChild(request);

                L.circleMarker([lat, lon], {

                    radius: 9,
                    color: '#ffffff',
                    weight: 3,
                    fillColor: agentColor(a),
                    fillOpacity: 1

                }).addTo(layers)
                    .bindTooltip(esc(a.fullName))
                    .bindPopup(details);
            }

            fit(coords);

            q('sgvMetrics').innerHTML =

                metric('Agents', agents.length) +

                metric(
                    'Positions récentes',
                    agents.filter(a => a.recent).length
                ) +

                metric(
                    'Positions affichées',
                    coords.length
                ) +

                metric(
                    'Partage désactivé',
                    agents.filter(a =>
                        a.gpsStatus === 'CONSENT_DISABLED'
                    ).length
                );
        }

        // ==========================================
        // AFFICHAGE DU TRAJET INDIVIDUEL
        // ==========================================

        function drawTrip(points) {

            if (!active()) return;

            selected = points || [];

            layers.clearLayers();

            const available =
                selected.length > 0;

            q('sgvCsv').disabled = !available;
            q('sgvPdf').disabled = !available;

            if (!available) {

                q('sgvMetrics').innerHTML =
                    '<p>Aucun trajet.</p>';

                q('sgvRows').innerHTML = '';

                return;
            }

            const first = selected[0];

            const last = selected.at(-1);

            const s = stats(selected);

            const coords =
                selected.map(p => [p.lat, p.lon]);

            if (selected.length > 1) {

                L.polyline(coords, {
                    color: '#ffffff',
                    weight: 9
                }).addTo(layers);

                L.polyline(coords, {
                    color: '#2854C5',
                    weight: 5,
                    lineJoin: 'round'
                }).addTo(layers);

                L.circleMarker(coords[0], {
                    color: '#ffffff',
                    weight: 2,
                    fillColor: '#5271DE',
                    fillOpacity: 1,
                    radius: 7
                }).addTo(layers)
                    .bindTooltip(
                        'Départ · ' + clock(first.time)
                    );
            }

            if (
                Number.isFinite(last.accuracy) &&
                last.accuracy > 0
            ) {

                L.circle(coords.at(-1), {

                    radius: last.accuracy,
                    color: '#C42B6B',
                    weight: 1,
                    fillOpacity: 0.1

                }).addTo(layers);
            }

            L.circleMarker(coords.at(-1), {

                radius: 9,
                weight: 3,
                color: '#ffffff',
                fillColor: '#C42B6B',
                fillOpacity: 1

            }).addTo(layers)
                .bindTooltip(
                    'Dernier point · ' + clock(last.time)
                );

            fit(coords);

            q('sgvMetrics').innerHTML =

                metric(
                    'Départ observé',
                    clock(first.time),
                    date(first.time)
                ) +

                metric(
                    mode === 'history'
                        ? 'Fin observée'
                        : 'Dernier point',
                    clock(last.time),
                    date(last.time)
                ) +

                metric(
                    'Durée',
                    duration(s.seconds)
                ) +

                metric(
                    'Distance estimée',
                    (s.distance / 1000).toFixed(3) + ' km'
                ) +

                metric('Points GPS', selected.length) +

                metric('Vitesse moyenne', speed(s.average)) +

                metric('Vitesse maximale', speed(s.maximum)) +

                metric(
                    'Précision',
                    Number.isFinite(last.accuracy)
                        ? last.accuracy + ' m'
                        : '—'
                );

            q('sgvRows').innerHTML = table(

                [
                    'Date et heure',
                    'Latitude',
                    'Longitude',
                    'Précision',
                    'Vitesse'
                ],

                selected.slice(-100).reverse().map(p => tr(

                    esc(date(p.time)),

                    esc(p.lat.toFixed(7)),

                    esc(p.lon.toFixed(7)),

                    Number.isFinite(p.accuracy)
                        ? esc(p.accuracy) + ' m'
                        : '—',

                    esc(speed(p.speed * 3.6))
                ))
            );

            if (selected.length > 100) {

                q('sgvRows').insertAdjacentHTML(
                    'beforeend',
                    '<p class="sgv-note">' +
                    '100 derniers points affichés. ' +
                    'Les exports utilisent tous les points du trajet chargé.' +
                    '</p>'
                );
            }
        }

        // ==========================================
        // CHARGEMENT DES DONNEES
        // ==========================================

        async function load(reset = false) {

            if (!active()) return;

            if (reset) {

                clear();

                fitNext = true;
                limited = false;
            }

            const v = ++version;

            const currentMode = mode;

            busy = true;

            q('sgvStatus').textContent =
                'Chargement des données…';

            try {

                if (currentMode === 'global') {

                    const result = obj(
                        await api('/admin/supervision')
                    );

                    if (
                        !active() ||
                        v !== version
                    ) return;

                    agents = result.agents;

                    renderGlobal();

                } else {

                    const id =
                        Number(q('sgvUser').value);

                    if (!id) {

                        throw new Error(
                            'Sélectionnez un utilisateur.'
                        );
                    }

                    gpsUser = id;

                    const params =
                        new URLSearchParams({
                            limit: String(LIMIT)
                        });

                    if (currentMode === 'history') {

                        const from =
                            new Date(q('sgvFrom').value);

                        const to =
                            new Date(q('sgvTo').value);

                        if (
                            !Number.isFinite(from.getTime()) ||
                            !Number.isFinite(to.getTime()) ||
                            from > to
                        ) {

                            throw new Error(
                                'Période de recherche invalide.'
                            );
                        }

                        params.set('from', from.toISOString());

                        params.set('to', to.toISOString());
                    }

                    const rows = arr(await api(

                        `/positions/user/${id}/history?` +

                        params.toString()
                    ));

                    if (
                        !active() ||
                        v !== version ||
                        id !== Number(q('sgvUser').value)
                    ) return;

                    limited = rows.length >= LIMIT;

                    trips = splitTrips(rows);

                    q('sgvTripSelect').hidden =
                        currentMode !== 'history' ||
                        !trips.length;

                    q('sgvTripList').innerHTML =
                        trips.map((t, i) => option(

                            i,

                            'Trajet ' + (i + 1) +
                            ' · ' + date(t[0].time) +
                            ' → ' + clock(t.at(-1).time),

                            i === trips.length - 1

                        )).join('');

                    drawTrip(trips.at(-1));
                }

                let message =
                    'Actualisé à ' + clock(Date.now());

                if (mode === 'history') {

                    message +=
                        ' · ' + trips.length +
                        ' trajet(s) trouvé(s).';
                }

                if (mode === 'live' && selected.length) {

                    const age = Math.max(
                        0,
                        (Date.now() -
                            selected.at(-1).time) / 1000
                    );

                    message +=
                        ' · Dernier relevé il y a ' +
                        duration(age);

                    if (age > 120) {

                        message +=
                            ' · Aucun relevé récent.';
                    }
                }

                if (limited) {

                    message +=
                        ' · Limite de 2 000 points atteinte.' +
                        ' Réduisez la période.';
                }

                q('sgvStatus').textContent = message;

            } catch (e) {

                if (
                    active() &&
                    v === version
                ) {

                    q('sgvStatus').textContent =

                        'Erreur : ' + e.message +

                        (reset
                            ? ''
                            : ' Les données affichées peuvent être anciennes.');
                }

            } finally {

                if (v === version) {
                    busy = false;
                }
            }
        }

        function setMode(next) {

            mode = next;

            root.querySelectorAll('[data-mode]')
                .forEach(b => {

                    b.classList.toggle(
                        'selected',
                        b.dataset.mode === mode
                    );
                });

            q('sgvGlobal').hidden =
                mode !== 'global';

            q('sgvIndividual').hidden =
                mode === 'global';

            q('sgvDates').hidden =
                mode !== 'history';

            q('sgvHistory').hidden =
                mode === 'global';

            q('sgvLoad').textContent =
                mode === 'history'
                    ? 'Afficher le trajet'
                    : 'Actualiser le suivi';

            if (mode === 'history') {

                q('sgvTo').value =
                    localInput(new Date());
            }

            load(true);
        }

        root.querySelectorAll('[data-mode]')
            .forEach(b => {

                b.onclick = () =>
                    setMode(b.dataset.mode);
            });

        q('sgvSearch').oninput = () => {

            fitNext = true;

            renderGlobal();
        };

        q('sgvLoad').onclick = () => load(true);

        q('sgvUser').onchange = () => load(true);

        q('sgvTripList').onchange = () => {

            fitNext = true;

            drawTrip(
                trips[Number(q('sgvTripList').value)]
            );
        };

        for (const id of ['sgvFrom', 'sgvTo']) {

            q(id).onchange = () => {

                version++;

                busy = false;

                clear();

                q('sgvStatus').textContent =
                    'Cliquez sur Afficher le trajet.';
            };
        }

        q('sgvFullscreen').onclick = async () => {

            try {

                if (document.fullscreenElement) {

                    await document.exitFullscreen();

                } else {

                    await root
                        .querySelector('.sgv-map-card')
                        .requestFullscreen();
                }

                currentMap.invalidateSize();

            } catch (e) {

                notify(
                    'Plein écran indisponible.',
                    true
                );
            }
        };

        // ==========================================
        // EXPORT CSV - TOUS LES POINTS CHARGES
        // ==========================================

        q('sgvCsv').onclick = () => {

            if (!selected.length) return;

            if (
                limited &&
                !confirm(
                    'La période est limitée à 2 000 relevés. ' +
                    'Exporter le trajet partiel chargé ?'
                )
            ) return;

            const lines = [
                'date_utc;latitude;longitude;precision_m;vitesse_kmh'
            ];

            for (const p of selected) {

                lines.push([

                    new Date(p.time).toISOString(),

                    p.lat.toFixed(7),

                    p.lon.toFixed(7),

                    Number.isFinite(p.accuracy)
                        ? p.accuracy
                        : '',

                    Number.isFinite(p.speed)
                        ? (p.speed * 3.6).toFixed(2)
                        : ''

                ].join(';'));
            }

            const blob = new Blob(
                ['\uFEFF' + lines.join('\r\n')],
                {type: 'text/csv;charset=utf-8'}
            );

            const url = URL.createObjectURL(blob);

            const a = document.createElement('a');

            a.href = url;

            a.download =
                `SmartGeo-Agent-${gpsUser}-${new Date(
                    selected[0].time
                ).toISOString().slice(0, 10)}.csv`;

            a.click();

            setTimeout(
                () => URL.revokeObjectURL(url),
                1000
            );
        };

        // ==========================================
        // EXPORT PDF VIA IMPRESSION DU NAVIGATEUR
        // ==========================================

        q('sgvPdf').onclick = () => {

            if (!selected.length) return;

            if (
                limited &&
                !confirm(
                    'Historique potentiellement partiel. Continuer ?'
                )
            ) return;

            const s = stats(selected);

            const agent =
                users.find(u =>
                    Number(u.id) === gpsUser
                );

            const w = window.open('', '_blank');

            if (!w) {

                notify(
                    'Autorisez les fenêtres pop-up.',
                    true
                );

                return;
            }

            w.document.write(`

                <!DOCTYPE html>
                <html lang="fr">
                <head>

                    <meta charset="UTF-8">

                    <title>SmartGeo - Rapport GPS</title>

                    <style>

                        body {
                            font-family: Arial, sans-serif;
                            color: #14213D;
                            margin: 30px;
                            font-size: 12px;
                        }

                        h1 {
                            color: #5271DE;
                        }

                        table {
                            width: 100%;
                            border-collapse: collapse;
                        }

                        td, th {
                            padding: 7px;
                            border: 1px solid #DCE3EE;
                            text-align: left;
                        }

                        thead {
                            background: #EDF1FF;
                            display: table-header-group;
                        }

                        tr {
                            break-inside: avoid;
                        }

                        @media print {
                            button {display:none;}
                        }

                    </style>

                </head>

                <body>

                    <h1>SmartGeo</h1>

                    <h2>Rapport de déplacement GPS</h2>

                    <p>
                        Agent :
                        ${esc(agent?.fullName || gpsUser)}
                    </p>

                    <p>
                        Départ observé :
                        ${esc(date(selected[0].time))}
                    </p>

                    <p>
                        Fin observée :
                        ${esc(date(selected.at(-1).time))}
                    </p>

                    <p>
                        Durée :
                        ${esc(duration(s.seconds))}
                    </p>

                    <p>
                        Distance estimée :
                        ${(s.distance / 1000).toFixed(3)} km
                    </p>

                    <p>
                        Vitesse moyenne :
                        ${esc(speed(s.average))}
                    </p>

                    <p>
                        Vitesse maximale :
                        ${esc(speed(s.maximum))}
                    </p>

                    <p>Relevés : ${selected.length}</p>

                    <p>
                        ${limited
                ? 'Attention : historique potentiellement partiel.'
                : ''}

                        Heures locales du navigateur.
                        Distance calculée entre les relevés GPS.
                    </p>

                    <p>
                        Document confidentiel.
                        Réservé aux personnes autorisées.
                    </p>

                    <button onclick="window.print()">
                        Enregistrer en PDF
                    </button>

                    <hr>

                    ${table(

                [
                    'Date',
                    'Latitude',
                    'Longitude',
                    'Précision'
                ],

                selected.map(p => tr(

                    esc(date(p.time)),

                    esc(p.lat.toFixed(7)),

                    esc(p.lon.toFixed(7)),

                    Number.isFinite(p.accuracy)
                        ? esc(p.accuracy) + ' m'
                        : '—'
                ))
            )}

                </body>
                </html>
            `);

            w.document.close();
        };

        // ==========================================
        // ACTUALISATION AUTOMATIQUE
        // ==========================================

        if (refresh) clearInterval(refresh);

        refresh = setInterval(() => {

            if (
                active() &&
                !document.hidden &&
                !busy &&
                mode !== 'history'
            ) {

                load(false);
            }

        }, REFRESH);

        setMode(mode);
    };

    // =============================================
    // RECHERCHE ET PAGINATION DES TABLEAUX
    // =============================================

    function enhanceTables() {

        if (![
            'users',
            'missions',
            'schedules',
            'reports'
        ].includes(page)) return;

        const content = $('adminContent');

        if (!content) return;

        content.querySelectorAll('.table-scroll')
            .forEach(wrapper => {

                if (wrapper.dataset.sgvReady) return;

                const body =
                    wrapper.querySelector('tbody');

                if (!body) return;

                const rows =
                    Array.from(body.rows);

                if (!rows.length) return;

                wrapper.dataset.sgvReady = 'true';

                const controls =
                    document.createElement('div');

                controls.className =
                    'sgv-table-controls';

                controls.innerHTML = `

                    <input placeholder="Rechercher dans le tableau…"
                           aria-label="Rechercher">

                    <select aria-label="Nombre de lignes">
                        <option value="10">10 lignes</option>
                        <option value="20">20 lignes</option>
                        <option value="50">50 lignes</option>
                    </select>

                    <button type="button"
                            class="admin-secondary"
                            data-prev>
                        Précédent
                    </button>

                    <button type="button"
                            class="admin-secondary"
                            data-next>
                        Suivant
                    </button>

                    <small></small>
                `;

                wrapper.before(controls);

                const input =
                    controls.querySelector('input');

                const size =
                    controls.querySelector('select');

                const previous =
                    controls.querySelector('[data-prev]');

                const next =
                    controls.querySelector('[data-next]');

                let index = 0;

                function render() {

                    const term =
                        input.value.toLowerCase();

                    const n = Number(size.value);

                    const found = rows.filter(row =>
                        Array.from(row.cells)
                            .filter(cell =>
                                !cell.querySelector('.admin-actions')
                            )
                            .map(cell => cell.textContent)
                            .join(' ')
                            .toLowerCase()
                            .includes(term)
                    );

                    const count =
                        Math.max(1, Math.ceil(found.length / n));

                    index =
                        Math.min(index, count - 1);

                    rows.forEach(r => r.hidden = true);

                    found.slice(
                        index * n,
                        (index + 1) * n
                    ).forEach(r => r.hidden = false);

                    previous.disabled = index === 0;

                    next.disabled = index >= count - 1;

                    controls.querySelector('small')
                        .textContent =
                        `${found.length} résultat(s) · ` +
                        `Page ${index + 1}/${count}`;
                }

                input.oninput = () => {
                    index = 0;
                    render();
                };

                size.onchange = () => {
                    index = 0;
                    render();
                };

                previous.onclick = () => {
                    index--;
                    render();
                };

                next.onclick = () => {
                    index++;
                    render();
                };

                render();
            });
    }

    const content = $('adminContent');

    if (content) {

        new MutationObserver(enhanceTables).observe(
            content,
            {
                childList: true,
                subtree: true
            }
        );
    }

    console.info(
        'SmartGeo V2 : supervision, historique, exports et tableaux chargés.'
    );

})();
