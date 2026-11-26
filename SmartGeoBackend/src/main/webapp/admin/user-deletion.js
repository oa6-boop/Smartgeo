
"use strict";

// SmartGeo - Suppression des utilisateurs
// Charger ce fichier APRES admin/dashboard.js.

(function () {

    if (
        typeof usersPage !== "function" ||
        typeof api !== "function" ||
        typeof notify !== "function"
    ) {
        console.error(
            "SmartGeo : chargez dashboard.js avant user-deletion.js"
        );
        return;
    }

    const usersPageOriginale = usersPage;

    // Ajouter les boutons apres chaque chargement.
    usersPage = async function () {

        await usersPageOriginale();

        if (page !== "users") {
            return;
        }

        document.querySelectorAll(
            '#adminContent button[data-edit-user]'
        ).forEach(function (editButton) {

            const row = editButton.closest("tr");

            if (!row || row.cells.length < 7) {
                return;
            }

            const roleBadge =
                row.cells[4].querySelector(".admin-badge");

            if (
                !roleBadge ||
                !roleBadge.classList.contains("USER")
            ) {
                return;
            }

            const actions =
                editButton.closest(".admin-actions");

            if (
                !actions ||
                actions.querySelector("[data-delete-user]")
            ) {
                return;
            }

            const button = document.createElement("button");

            button.type = "button";
            button.className = "admin-danger";
            button.textContent = "Supprimer définitivement";

            button.dataset.deleteUser =
                editButton.dataset.editUser;

            button.setAttribute(
                "aria-label",
                "Supprimer cet utilisateur et ses données"
            );

            actions.appendChild(button);
        });
    };

    // Gerer le clic sur le bouton de suppression.
    document.addEventListener("click", async function (event) {

        const button = event.target.closest(
            "button[data-delete-user]"
        );

        if (!button || button.disabled) {
            return;
        }

        const id = Number(button.dataset.deleteUser);

        if (!Number.isSafeInteger(id) || id <= 0) {
            return;
        }

        button.disabled = true;

        try {

            // Relire les donnees actuelles du compte.
            const response = await api(
                "/admin/users/" + id
            );

            const user = response?.data;

            if (!user || user.role !== "USER") {
                throw new Error(
                    "La suppression est reservee aux comptes USER."
                );
            }

            const username = String(user.username);

            const message =
                "SUPPRESSION DEFINITIVE du compte : " +
                username +
                " (#" + id + ").\n\n" +

                "Seront supprimes de MySQL :\n" +

                "- positions et historique GPS ;\n" +
                "- consentements, plannings et demandes GPS ;\n" +
                "- missions attribuees ou creees ;\n" +
                "- rapports et signalements lies ;\n" +
                "- messages envoyes et recus ;\n" +
                "- compte utilisateur et jetons d'appareil.\n\n" +

                "Cette action est IRREVERSIBLE.\n" +
                "Pour confirmer, saisissez exactement : " +
                username;

            const confirmation = window.prompt(message);

            if (confirmation === null) {
                return;
            }

            if (confirmation !== username) {

                notify(
                    "Suppression annulee : identifiant incorrect.",
                    true
                );

                return;
            }

            const finalConfirmation = window.confirm(
                "Derniere confirmation : supprimer " +
                username +
                " et toutes ses donnees ?"
            );

            if (!finalConfirmation) {
                return;
            }

            // Appel de l'API Java securisee.
            await api(
                "/admin/user-removal/" + id,
                "DELETE"
            );

            // Recharger la liste.
            await usersPage();

            notify(
                "Le compte " +
                username +
                " a ete supprime de MySQL."
            );

        } catch (error) {

            console.error(error);

            notify(
                error.message || "Suppression impossible.",
                true
            );

        } finally {

            button.disabled = false;
        }
    });

})();
