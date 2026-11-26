
'use strict';

(function () {

    const oldUsers = usersPage;

    const oldMissions = missionsPage;

    let drawVersion = 0;

    // =========================================
    // AJOUT DES BOUTONS DE SUPPRESSION
    // =========================================

    function addDelete(
        selector,
        attribute,
        allowed
    ) {

        document.querySelectorAll(

            '#adminContent ' + selector

        ).forEach(edit => {

            const actions =
                edit.closest('.admin-actions');

            const row =
                edit.closest('tr');

            if (

                !actions ||

                !row ||

                !allowed(row) ||

                actions.querySelector(
                    '[' + attribute + ']'
                )

            ) {

                return;
            }

            const button =
                document.createElement('button');

            button.type = 'button';

            button.className = 'admin-danger';

            button.textContent = 'Supprimer';

            button.setAttribute(

                attribute,

                edit.dataset.editUser ||
                edit.dataset.editMission
            );

            actions.appendChild(button);
        });
    }

    // =========================================
    // UTILISATEURS
    // =========================================

    usersPage = async function () {

        await oldUsers();

        if (

            page === 'users' &&

            admin?.role === 'ADMIN'

        ) {

            addDelete(

                'button[data-edit-user]',

                'data-sg-remove-user',

                row => !!row.cells[4]?.querySelector(
                    '.admin-badge.USER'
                )
            );
        }
    };

    // =========================================
    // MISSIONS
    // =========================================

    missionsPage = async function () {

        await oldMissions();

        if (

            page === 'missions' &&

            admin?.role === 'ADMIN'

        ) {

            addDelete(

                'button[data-edit-mission]',

                'data-sg-remove-mission',

                () => true
            );
        }
    };

    // =========================================
    // GESTION DES SUPPRESSIONS
    // =========================================

    document.addEventListener(
        'click',
        async event => {

            const b = event.target.closest(

                'button[data-sg-remove-user],' +
                'button[data-sg-remove-mission]'
            );

            if (

                !b ||

                b.disabled ||

                admin?.role !== 'ADMIN'

            ) {

                return;
            }

            event.preventDefault();

            const isUser = b.hasAttribute(
                'data-sg-remove-user'
            );

            const id = Number(

                isUser

                    ? b.dataset.sgRemoveUser

                    : b.dataset.sgRemoveMission
            );

            if (

                !Number.isSafeInteger(id) ||

                id <= 0

            ) {

                return;
            }

            b.disabled = true;

            try {

                if (isUser) {

                    const u = obj(

                        await api(
                            '/admin/users/' + id
                        )
                    );

                    if (

                        !u ||

                        u.role !== 'USER' ||

                        Number(admin.id) === id

                    ) {

                        throw new Error(

                            'Ce compte ne peut pas être supprimé.'
                        );
                    }

                    const answer = prompt(

                        'Suppression définitive du compte ' +

                        u.username +

                        '. Ses positions, plannings, messages, ' +

                        'fichiers et signalements seront supprimés. ' +

                        'Ses missions attribuées seront conservées ' +

                        'et désaffectées.\n\n' +

                        'Saisissez son identifiant pour confirmer : ' +

                        u.username
                    );

                    if (answer !== u.username) {

                        return;
                    }

                    if (!confirm(

                        'Confirmer la suppression définitive de ce compte ?'

                    )) {

                        return;
                    }

                    await api(

                        '/admin/user-removal/' + id,

                        'DELETE'
                    );

                    if (page === 'users') {

                        await usersPage();
                    }

                    notify(
                        'Utilisateur supprimé.'
                    );

                } else {

                    if (!confirm(

                        'Supprimer définitivement la mission #' +

                        id +

                        ' et ses signalements associés ?'

                    )) {

                        return;
                    }

                    await api(

                        '/admin/mission-management/' + id,

                        'DELETE'
                    );

                    if (page === 'missions') {

                        await missionsPage();
                    }

                    notify(
                        'Mission supprimée.'
                    );
                }

            } catch (error) {

                console.error(error);

                notify(

                    error.message ||

                    'Suppression impossible.',

                    true
                );

            } finally {

                b.disabled = false;
            }
        }
    );

    // =========================================
    // CONVERSION DES DATES GPS
    // =========================================

    function timeOf(value) {

        if (

            Array.isArray(value) &&

            value.length >= 3

        ) {

            return Date.UTC(

                value[0],

                value[1] - 1,

                value[2],

                value[3] || 0,

                value[4] || 0,

                value[5] || 0,

                Math.floor(
                    (value[6] || 0) / 1e6
                )
            );
        }

        if (typeof value !== 'string') {

            return NaN;
        }

        let text = value.replace(' ', 'T');

        if (

            /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/
                .test(text)

        ) {

            text += 'Z';
        }

        return Date.parse(text);
    }

    // =========================================
    // DISTANCE ENTRE DEUX POSITIONS
    // =========================================

    function distance(a, b) {

        const rad = Math.PI / 180;

        const h =

            Math.sin(
                (b[0] - a[0]) * rad / 2
            ) ** 2 +

            Math.cos(a[0] * rad) *

            Math.cos(b[0] * rad) *

            Math.sin(
                (b[1] - a[1]) * rad / 2
            ) ** 2;

        return 6371000 * 2 *

            Math.asin(

                Math.sqrt(

                    Math.min(
                        1,
                        Math.max(0, h)
                    )
                )
            );
    }

    // =========================================
    // SEPARER LES TRAJETS INCOHERENTS
    // =========================================

    function recentTrack(history) {

        // L'API renvoie l'historique
        // du plus recent au plus ancien.

        const points = history
            .slice()
            .reverse()
            .filter(p =>

                p.latitude != null &&

                p.longitude != null &&

                String(p.latitude).trim() !== '' &&

                String(p.longitude).trim() !== '' &&

                Number.isFinite(+p.latitude) &&

                Number.isFinite(+p.longitude) &&

                Math.abs(+p.latitude) <= 90 &&

                Math.abs(+p.longitude) <= 180

            ).map(p => ({

                raw: p,

                coords: [
                    +p.latitude,
                    +p.longitude
                ],

                time: timeOf(p.recorded_at)
            }));

        let start = 0;

        for (

            let i = 1;

            i < points.length;

            i++

        ) {

            const a = points[i - 1];

            const b = points[i];

            const dt =
                (b.time - a.time) / 1000;

            if (

                !Number.isFinite(dt) ||

                dt < 0 ||

                dt > 900 ||

                distance(a.coords, b.coords) >

                100 + dt * (300 / 3.6)

            ) {

                start = i;
            }
        }

        return {

            count: points.length,

            recent: points.slice(start),

            split: start > 0
        };
    }

    // =========================================
    // CORRECTION FINALE DE LA CARTE ADMIN
    // =========================================

    drawGps = async function () {

        if (

            page !== 'gps' ||

            !map ||

            !gpsUser ||

            !$('gpsUserId')?.options.length

        ) {

            return;
        }

        const id = gpsUser;

        const targetMap = map;

        const version = ++drawVersion;

        const history = arr(

            await api(

                '/positions/user/' +

                id +

                '/history?limit=500'
            )
        );

        // Eviter l'affichage d'une ancienne requete.

        if (

            page !== 'gps' ||

            map !== targetMap ||

            gpsUser !== id ||

            version !== drawVersion

        ) {

            return;
        }

        // Supprimer les anciennes lignes et marqueurs.

        targetMap.eachLayer(layer => {

            if (!(layer instanceof L.TileLayer)) {

                targetMap.removeLayer(layer);
            }
        });

        const route = recentTrack(history);

        const coords = route.recent.map(
            p => p.coords
        );

        if (coords.length) {

            const moved = coords.some(p =>

                p[0] !== coords[0][0] ||

                p[1] !== coords[0][1]
            );

            // Dessiner uniquement le trajet recent.

            if (moved) {

                L.polyline(

                    coords,

                    {

                        color: '#1666d4',

                        weight: 5
                    }

                ).addTo(targetMap);

                L.circleMarker(

                    coords[0],

                    {

                        color: '#16a34a',

                        radius: 8
                    }

                ).addTo(targetMap)

                    .bindPopup(
                        'Début du trajet récent'
                    );
            }

            const latest =
                route.recent[
                route.recent.length - 1
                    ].raw;

            const last =
                coords[coords.length - 1];

            // Derniere localisation enregistree.

            L.circleMarker(

                last,

                {

                    color: '#dc2626',

                    radius: 9
                }

            ).addTo(targetMap)

                .bindPopup(

                    'Dernier relevé enregistré<br>' +

                    esc(fmt(latest.recorded_at)) +

                    '<br>Précision : ' +

                    esc(latest.accuracy ?? '—') +

                    ' m'
                );

            targetMap.invalidateSize();

            // Centrer sur le trajet recent.

            if (moved) {

                targetMap.fitBounds(

                    L.latLngBounds(coords).pad(.15),

                    {

                        maxZoom: 16
                    }
                );

            } else {

                targetMap.setView(last, 16);
            }

        } else {

            // Vue mondiale neutre si aucun GPS.

            targetMap.setView([0, 0], 2);
        }

        $('gpsSummary').textContent =

            'GPS corrigé · Agent #' +

            id +

            ' · ' +

            coords.length +

            ' / ' +

            route.count +

            ' relevés dans le trajet récent. ' +

            (

                route.split

                    ? 'Les anciens trajets sont séparés. '

                    : ''
            ) +

            'Actualisation : ' +

            new Date().toLocaleTimeString('fr-FR');

        // Conserver tout l'historique dans le tableau.

        $('gpsHistory').innerHTML = table(

            [

                'Date',

                'Latitude',

                'Longitude',

                'Précision (m)'
            ],

            history.slice(0, 80).map(p =>

                tr(

                    esc(fmt(p.recorded_at)),

                    esc(p.latitude),

                    esc(p.longitude),

                    esc(p.accuracy ?? '—')
                )
            )
        );
    };

    console.info(

        'SmartGeo : module ADMIN final GPS et suppressions chargé.'
    );

})();