
'use strict';

(function () {

    const LIMIT = 2000;
    const REFRESH_MS = 15000;
    const MAX_GAP = 900;
    const MAX_SPEED = 300;

    // ==============================================
    // CONVERSION DES DATES
    // ==============================================

    function timeOf(v) {

        if (Array.isArray(v) && v.length >= 3) {

            return Date.UTC(
                v[0],
                v[1] - 1,
                v[2],
                v[3] || 0,
                v[4] || 0,
                v[5] || 0,
                Math.floor((v[6] || 0) / 1e6)
            );
        }

        if (typeof v !== 'string') {
            return NaN;
        }

        let s = v.replace(' ', 'T');

        if (
            /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(s)
        ) {
            s += 'Z';
        }

        return Date.parse(s);
    }

    function numberOf(v) {

        return v == null || String(v).trim() === ''
            ? NaN
            : Number(v);
    }

    function clock(time) {

        return new Date(time).toLocaleTimeString(
            'fr-FR',
            {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            }
        );
    }

    function dateTime(time) {

        return new Date(time).toLocaleString(
            'fr-FR'
        );
    }

    function localInput(date) {

        const local = new Date(
            date.getTime() -
            date.getTimezoneOffset() * 60000
        );

        return local.toISOString().slice(0, 16);
    }

    function duration(seconds) {

        seconds = Math.max(0, Math.floor(seconds));

        const h = Math.floor(seconds / 3600);
        const m = Math.floor(seconds % 3600 / 60);
        const s = seconds % 60;

        if (h > 0) {
            return `${h} h ${m} min ${s} s`;
        }

        if (m > 0) {
            return `${m} min ${s} s`;
        }

        return `${s} s`;
    }

    function distanceLabel(distance) {

        if (distance < 1000) {

            return Math.round(distance) + ' m';
        }

        return (distance / 1000)
            .toLocaleString('fr-FR', {
                minimumFractionDigits: 2,
                maximumFractionDigits: 2
            }) + ' km';
    }

    function speedLabel(value) {

        if (value == null || !Number.isFinite(value)) {

            return 'Non mesurée';
        }

        return value.toLocaleString(
            'fr-FR',
            {
                maximumFractionDigits: 1
            }
        ) + ' km/h';
    }

    // ==============================================
    // CALCUL DE LA DISTANCE GPS
    // FORMULE DE HAVERSINE
    // ==============================================

    function meters(a, b) {

        const rad = Math.PI / 180;

        const h =

            Math.sin(
                (b.lat - a.lat) * rad / 2
            ) ** 2 +

            Math.cos(a.lat * rad) *

            Math.cos(b.lat * rad) *

            Math.sin(
                (b.lon - a.lon) * rad / 2
            ) ** 2;

        return 6371000 * 2 *

            Math.asin(
                Math.sqrt(
                    Math.min(1, Math.max(0, h))
                )
            );
    }

    // ==============================================
    // SEPARATION DES DIFFERENTS TRAJETS
    // ==============================================

    function splitTrips(rows) {

        const points = rows.map(p => ({

            id: p.id,

            lat: numberOf(p.latitude),

            lon: numberOf(p.longitude),

            time: timeOf(p.recorded_at),

            speed: numberOf(p.speed),

            accuracy: numberOf(p.accuracy)

        })).filter(p =>

            Number.isFinite(p.lat) &&

            Math.abs(p.lat) <= 90 &&

            Number.isFinite(p.lon) &&

            Math.abs(p.lon) <= 180 &&

            Number.isFinite(p.time)

        ).sort((a, b) =>

            a.time - b.time ||

            Number(a.id) - Number(b.id)
        );

        const trips = [];

        for (const p of points) {

            const trip = trips[trips.length - 1];

            const prev = trip?.[trip.length - 1];

            const dt = prev

                ? (p.time - prev.time) / 1000

                : 0;

            // Nouveau trajet en cas d'interruption
            // ou de distance incoherente.

            if (

                !prev ||

                dt > MAX_GAP ||

                meters(prev, p) >

                100 + dt * (MAX_SPEED / 3.6)

            ) {

                trips.push([p]);

            } else {

                trip.push(p);
            }
        }

        return trips;
    }

    // ==============================================
    // CALCUL DES STATISTIQUES
    // ==============================================

    function stats(points) {

        let distance = 0;

        for (let i = 1; i < points.length; i++) {

            distance += meters(
                points[i - 1],
                points[i]
            );
        }

        const first = points[0];

        const last = points[points.length - 1];

        const seconds =

            (last.time - first.time) / 1000;

        // Android fournit la vitesse en m/s.
        // Conversion en km/h : multiplication par 3.6.

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

                : null,

            max: speeds.length

                ? Math.max(...speeds) * 3.6

                : null
        };
    }

    function metric(title, value, detail = '') {

        return `
            <div class="sg-trip-metric">

                <span>${esc(title)}</span>

                <strong>${esc(value)}</strong>

                <small>${esc(detail)}</small>

            </div>
        `;
    }

    // ==============================================
    // NOUVELLE PAGE GPS ADMIN
    // ==============================================

    gpsPage = async function () {

        if (admin?.role !== 'ADMIN') {

            notify('Accès administrateur interdit.', true);

            return;
        }

        const root = document.createElement('section');

        root.id = 'sgTripsRoot';

        root.className = 'sg-trips';

        root.innerHTML = '<div class="panel">Chargement GPS…</div>';

        $('adminContent').replaceChildren(root);

        const users = arr(

            await api('/admin/users')

        ).filter(u => u.role === 'USER');

        if (page !== 'gps' || !root.isConnected) {

            return;
        }

        if (!window.L) {

            root.textContent = 'OpenStreetMap / Leaflet indisponible.';

            return;
        }

        const now = new Date();

        const today = new Date(now);

        today.setHours(0, 0, 0, 0);

        root.innerHTML = `

            <div class="sg-trip-heading">

                <div>

                    <h2>Trajets et localisation</h2>

                    <p>
                        Consultez les déplacements enregistrés
                        de vos agents.
                    </p>

                </div>

                <div class="sg-trip-tabs">

                    <button type="button"
                            id="sgTripLive"
                            class="selected">

                        Suivi en direct

                    </button>

                    <button type="button"
                            id="sgTripHistory">

                        Historique des trajets

                    </button>

                </div>

            </div>

            <div class="sg-trip-layout">

                <aside class="sg-trip-sidebar">

                    <section class="sg-trip-card">

                        <h3>Rechercher un trajet</h3>

                        <form id="sgTripSearch">

                            <label for="sgTripUser">

                                Utilisateur

                            </label>

                            <select id="sgTripUser" required>

                                ${users.map(u =>

            option(
                u.id,
                (u.fullName || u.username)
                + ' (#' + u.id + ')',
                Number(u.id) === gpsUser
            )

        ).join('')}

                            </select>

                            <div id="sgTripDates" hidden>

                                <label for="sgTripFrom">
                                    Du
                                </label>

                                <input type="datetime-local"
                                       id="sgTripFrom"
                                       value="${localInput(today)}">

                                <label for="sgTripTo">
                                    Au
                                </label>

                                <input type="datetime-local"
                                       id="sgTripTo"
                                       value="${localInput(now)}">

                                <p class="sg-trip-note">

                                    Dates en heure locale du navigateur.

                                </p>

                            </div>

                            <button type="submit"
                                    class="sg-trip-primary"
                                    id="sgTripLoad">

                                Actualiser le suivi

                            </button>

                        </form>

                        <div id="sgTripSelection" hidden>

                            <label for="sgTripList">

                                Trajet enregistré

                            </label>

                            <select id="sgTripList"></select>

                        </div>

                        <p class="sg-trip-note"
                           id="sgTripStatus"
                           role="status">

                            Chargement…

                        </p>

                    </section>

                    <section class="sg-trip-card">

                        <h3>Résumé du trajet</h3>

                        <div id="sgTripMetrics"
                             class="sg-trip-metrics">

                            Aucune donnée.

                        </div>

                    </section>

                </aside>

                <section class="sg-trip-card sg-trip-map-card">

                    <div class="sg-trip-map-heading">

                        <strong>Carte des déplacements</strong>

                        <span id="sgTripModeLabel"
                              class="sg-trip-mode">

                            Actualisation 15 s

                        </span>

                    </div>

                    <div id="sgTripMap"></div>

                    <div class="sg-trip-legend">

                        <span>
                            <i class="sg-trip-start"></i>
                            Départ
                        </span>

                        <span>
                            <i class="sg-trip-line"></i>
                            Trajet enregistré
                        </span>

                        <span>
                            <i class="sg-trip-end"></i>
                            Dernier point / Arrivée
                        </span>

                    </div>

                </section>

            </div>

            <section class="sg-trip-card sg-trip-table">

                <h3>Relevés GPS du trajet</h3>

                <div id="sgTripRows"></div>

            </section>
        `;

        const q = id => root.querySelector('#' + id);

        let mode = 'live';

        let trips = [];

        let requestVersion = 0;

        let busy = false;

        let lastDrawKey = '';

        // La variable globale map est conservee.
        // Le dashboard pourra la supprimer en changeant de page.

        map = L.map(q('sgTripMap')).setView(
            [0, 0],
            2
        );

        const currentMap = map;

        L.tileLayer(

            'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',

            {

                maxZoom: 19,

                attribution: '© OpenStreetMap'
            }

        ).addTo(currentMap);

        const layers = L.featureGroup()
            .addTo(currentMap);

        function active() {

            return root.isConnected &&

                page === 'gps' &&

                map === currentMap;
        }

        function clearResult() {

            layers.clearLayers();

            lastDrawKey = '';

            q('sgTripMetrics').innerHTML =

                metric('Départ', '—') +

                metric('Arrivée', '—') +

                metric('Durée', '—') +

                metric('Distance', '—') +

                metric('Points GPS', '—') +

                metric('Vitesse max.', '—') +

                metric('Vitesse moy.', '—') +

                metric('Précision', '—');

            q('sgTripRows').innerHTML =

                '<p class="sg-trip-note">Aucun relevé à afficher.</p>';

            currentMap.setView([0, 0], 2);
        }

        // ==========================================
        // AFFICHER UN TRAJET ET SES STATISTIQUES
        // ==========================================

        function drawTrip(points) {

            if (!active()) return;

            if (!points || !points.length) {

                clearResult();

                return;
            }

            const first = points[0];

            const last = points[points.length - 1];

            const result = stats(points);

            const coords = points.map(

                p => [p.lat, p.lon]
            );

            const moved = points.some(p =>

                p.lat !== first.lat ||

                p.lon !== first.lon
            );

            const key =

                gpsUser + ':' +

                first.id + ':' +

                last.id + ':' +

                points.length;

            layers.clearLayers();

            // Ligne blanche sous le trajet
            // pour ameliorer sa visibilite.

            if (moved) {

                L.polyline(coords, {

                    color: '#ffffff',

                    weight: 9,

                    opacity: 0.95

                }).addTo(layers);

                // Trace principal bleu.

                L.polyline(coords, {

                    color: '#2854C5',

                    weight: 5,

                    opacity: 1,

                    lineCap: 'round',

                    lineJoin: 'round'

                }).addTo(layers);
            }

            // Marqueur de depart.

            if (moved) {

                L.circleMarker(
                    [first.lat, first.lon],
                    {
                        radius: 8,
                        color: '#ffffff',
                        weight: 3,
                        fillColor: '#2854C5',
                        fillOpacity: 1
                    }

                ).addTo(layers)

                    .bindTooltip(

                        'Départ · ' + clock(first.time),

                        {
                            permanent: true,
                            direction: 'left',
                            className: 'sg-trip-tooltip'
                        }
                    );
            }

            // Cercle de precision annoncee.

            if (
                Number.isFinite(last.accuracy) &&
                last.accuracy > 0
            ) {

                L.circle(

                    [last.lat, last.lon],

                    {
                        radius: last.accuracy,
                        color: '#C42B6B',
                        weight: 1,
                        fillOpacity: 0.08,
                        interactive: false
                    }

                ).addTo(layers);
            }

            // Marqueur de la derniere position.

            L.circleMarker(

                [last.lat, last.lon],

                {

                    radius: 9,

                    color: '#ffffff',

                    weight: 3,

                    fillColor: '#C42B6B',

                    fillOpacity: 1
                }

            ).addTo(layers)

                .bindTooltip(

                    (mode === 'live'
                        ? 'Dernier point · '
                        : 'Arrivée · ') +

                    clock(last.time),

                    {
                        permanent: true,
                        direction: 'right',
                        className: 'sg-trip-tooltip'
                    }
                )

                .bindPopup(

                    '<strong>Dernière position</strong>' +

                    '<br>Date : ' +

                    esc(dateTime(last.time)) +

                    '<br>Latitude : ' +

                    esc(last.lat.toFixed(7)) +

                    '<br>Longitude : ' +

                    esc(last.lon.toFixed(7)) +

                    '<br>Précision : ' +

                    (
                        Number.isFinite(last.accuracy)

                            ? esc(last.accuracy) + ' m'

                            : 'Non mesurée'
                    )
                );

            // Zoom uniquement si le trajet a change.

            if (key !== lastDrawKey) {

                currentMap.invalidateSize();

                if (moved) {

                    currentMap.fitBounds(

                        L.latLngBounds(coords).pad(0.18),

                        {
                            maxZoom: 17
                        }
                    );

                } else {

                    currentMap.setView(

                        [last.lat, last.lon],

                        17
                    );
                }

                lastDrawKey = key;
            }

            // Resume dynamique.

            q('sgTripMetrics').innerHTML =

                metric(

                    'Départ',

                    clock(first.time),

                    new Date(first.time)
                        .toLocaleDateString('fr-FR')
                ) +

                metric(

                    mode === 'live'
                        ? 'Dernier point'
                        : 'Arrivée',

                    clock(last.time),

                    new Date(last.time)
                        .toLocaleDateString('fr-FR')
                ) +

                metric(

                    'Durée',

                    duration(result.seconds)
                ) +

                metric(

                    'Distance estimée',

                    distanceLabel(result.distance)
                ) +

                metric(

                    'Points GPS',

                    points.length
                ) +

                metric(

                    'Vitesse maximale',

                    speedLabel(result.max)
                ) +

                metric(

                    'Vitesse moyenne',

                    speedLabel(result.average)
                ) +

                metric(

                    'Dernière précision',

                    Number.isFinite(last.accuracy)
                    && last.accuracy > 0

                        ? Math.round(last.accuracy) + ' m'

                        : 'Non mesurée'
                );

            // Tableau des 100 derniers points du trajet.

            q('sgTripRows').innerHTML = table(

                [
                    'Date et heure',
                    'Latitude',
                    'Longitude',
                    'Précision',
                    'Vitesse'
                ],

                points.slice(-100).reverse().map(p =>

                    tr(

                        esc(dateTime(p.time)),

                        esc(p.lat.toFixed(7)),

                        esc(p.lon.toFixed(7)),

                        Number.isFinite(p.accuracy)
                        && p.accuracy > 0

                            ? esc(Math.round(p.accuracy)) + ' m'

                            : '—',

                        Number.isFinite(p.speed)
                        && p.speed >= 0

                            ? esc(speedLabel(p.speed * 3.6))

                            : '—'
                    )
                )
            );

            if (points.length > 100) {

                const note = document.createElement('p');

                note.className = 'sg-trip-note';

                note.textContent =

                    'Les 100 derniers relevés sont affichés. ' +

                    'Les statistiques utilisent tout le trajet chargé.';

                q('sgTripRows').appendChild(note);
            }
        }

        // ==========================================
        // CHARGEMENT DEPUIS LE BACKEND
        // ==========================================

        async function loadTrips(reset = false) {

            if (!active()) return;

            const version = ++requestVersion;

            busy = true;

            const requestMode = mode;

            const id = Number(q('sgTripUser').value);

            gpsUser = id;

            if (reset) {

                trips = [];

                q('sgTripList').innerHTML = '';

                q('sgTripSelection').hidden = true;

                clearResult();
            }

            try {

                if (!Number.isSafeInteger(id) || id <= 0) {

                    throw new Error('Sélectionnez un utilisateur.');
                }

                const params = new URLSearchParams({

                    limit: String(LIMIT)
                });

                if (requestMode === 'history') {

                    const from = new Date(
                        q('sgTripFrom').value
                    );

                    const to = new Date(
                        q('sgTripTo').value
                    );

                    if (

                        !Number.isFinite(from.getTime()) ||

                        !Number.isFinite(to.getTime()) ||

                        from > to

                    ) {

                        throw new Error(

                            'Sélectionnez une période valide.'
                        );
                    }

                    // Conversion heure locale vers UTC.
                    // Format compatible avec Instant.parse en Java.

                    params.set(
                        'from',
                        from.toISOString()
                    );

                    params.set(
                        'to',
                        to.toISOString()
                    );
                }

                q('sgTripStatus').textContent =

                    'Actualisation des relevés GPS…';

                const rows = arr(

                    await api(

                        '/positions/user/' +

                        id +

                        '/history?' +

                        params.toString()
                    )
                );

                // Ignorer les anciennes reponses
                // apres changement d'utilisateur ou de mode.

                if (

                    !active() ||

                    version !== requestVersion ||

                    requestMode !== mode ||

                    id !== Number(q('sgTripUser').value)

                ) {

                    return;
                }

                trips = splitTrips(rows);

                if (!trips.length) {

                    clearResult();

                    q('sgTripStatus').textContent =

                        'Aucun relevé GPS valide pour cette recherche.';

                    return;
                }

                const lastIndex = trips.length - 1;

                // En historique, proposer tous les segments.

                q('sgTripSelection').hidden =

                    mode !== 'history';

                q('sgTripList').innerHTML = trips

                    .map((trip, i) => {

                        const first = trip[0];

                        const last = trip[trip.length - 1];

                        return option(

                            i,

                            'Trajet ' + (i + 1) +

                            ' · ' +

                            dateTime(first.time) +

                            ' → ' +

                            clock(last.time),

                            i === lastIndex
                        );
                    })

                    .join('');

                const selected = trips[lastIndex];

                drawTrip(selected);

                const count = trips.reduce(

                    (sum, trip) => sum + trip.length,

                    0
                );

                const last = selected[selected.length - 1];

                const age = Math.max(

                    0,

                    (Date.now() - last.time) / 1000
                );

                let message =

                    mode === 'live'

                        ? 'Dernier relevé reçu il y a ' +

                        duration(age) + '. '

                        : trips.length +

                        ' trajet(s) trouvé(s). ';

                if (mode === 'live' && age > 120) {

                    message +=

                        'Aucun nouveau relevé depuis plus de 2 minutes. ';
                }

                if (rows.length >= LIMIT) {

                    message +=

                        'Limite de 2 000 relevés atteinte. ' +

                        'Réduisez la période pour un historique complet. ';
                }

                if (count < rows.length) {

                    message +=

                        (rows.length - count) +

                        ' relevé(s) invalide(s) ignoré(s). ';
                }

                q('sgTripStatus').textContent =

                    message +

                    'Actualisé à ' +

                    clock(Date.now());

            } catch (error) {

                if (
                    active() &&
                    version === requestVersion
                ) {

                    q('sgTripStatus').textContent =

                        'Erreur : ' + error.message +

                        (reset
                            ? ''
                            : ' Les données précédentes peuvent être anciennes.');
                }

            } finally {

                if (version === requestVersion) {

                    busy = false;
                }
            }
        }

        // ==========================================
        // CHANGEMENT DE MODE
        // ==========================================

        function setMode(next) {

            mode = next;

            q('sgTripLive').classList.toggle(

                'selected',

                mode === 'live'
            );

            q('sgTripHistory').classList.toggle(

                'selected',

                mode === 'history'
            );

            q('sgTripDates').hidden =

                mode !== 'history';

            q('sgTripModeLabel').textContent =

                mode === 'live'

                    ? 'Actualisation 15 s'

                    : 'Historique';

            q('sgTripLoad').textContent =

                mode === 'live'

                    ? 'Actualiser le suivi'

                    : 'Afficher le trajet';

            if (mode === 'history') {

                q('sgTripTo').value = localInput(new Date());
            }

            loadTrips(true);
        }

        q('sgTripLive').addEventListener(

            'click',

            () => setMode('live')
        );

        q('sgTripHistory').addEventListener(

            'click',

            () => setMode('history')
        );

        q('sgTripSearch').addEventListener(

            'submit',

            event => {

                event.preventDefault();

                loadTrips(true);
            }
        );

        q('sgTripUser').addEventListener(

            'change',

            () => loadTrips(true)
        );

        q('sgTripList').addEventListener(

            'change',

            () => {

                const index = Number(
                    q('sgTripList').value
                );

                drawTrip(trips[index]);
            }
        );

        // Si la periode est modifiee,
        // attendre une nouvelle recherche.

        for (const id of ['sgTripFrom', 'sgTripTo']) {

            q(id).addEventListener('change', () => {

                requestVersion++;

                busy = false;

                trips = [];

                q('sgTripSelection').hidden = true;

                clearResult();

                q('sgTripStatus').textContent =

                    'Cliquez sur Afficher le trajet.';
            });
        }

        // ==========================================
        // SUIVI AUTOMATIQUE EN DIRECT
        // ==========================================

        if (refresh) {

            clearInterval(refresh);
        }

        refresh = setInterval(() => {

            if (

                active() &&

                mode === 'live' &&

                !document.hidden &&

                !busy

            ) {

                loadTrips(false);
            }

        }, REFRESH_MS);

        setTimeout(() => {

            if (active()) {

                currentMap.invalidateSize();
            }

        }, 150);

        if (!users.length) {

            q('sgTripLoad').disabled = true;

            clearResult();

            q('sgTripStatus').textContent =

                'Aucun utilisateur disponible.';

            return;
        }

        await loadTrips(true);
    };

    console.info(
        'SmartGeo : module trajets et historique chargé.'
    );

})();
