
"use strict";

/*
 * SMARTGEO - MESSAGERIE ADMIN
 * Interface modernisee avec pieces jointes.
 *
 * Conserve les autres fonctionnalites
 * du dashboard.js existant.
 */

(function () {

    const MAX_BYTES = 2 * 1024 * 1024;

    const MIME = {
        jpg: "image/jpeg",
        jpeg: "image/jpeg",
        png: "image/png",
        pdf: "application/pdf",
        txt: "text/plain"
    };

    let contacts = [];
    let selectedId = 0;
    let busy = false;
    let requestVersion = 0;

    const $chat = id => document.getElementById(id);

    function status(text, type = "") {

        const box = $chat("sgInlineStatus");

        if (!box) return;

        box.textContent = text;
        box.className = "sg-inline-status";

        if (type) {
            box.classList.add(type);
        }
    }

    function errorMessage(error) {

        const message = String(
            error?.message || "Erreur inconnue."
        );

        if (
            message.includes("404") ||
            message.includes("Not Found")
        ) {
            return (
                "Erreur HTTP 404 : service introuvable. " +
                "Verifiez que MessageFileResource est " +
                "deployee dans WildFly."
            );
        }

        if (message.includes("413")) {
            return "Le fichier depasse la taille autorisee.";
        }

        if (message.includes("403")) {
            return (
                "Acces refuse : verifiez votre compte " +
                "et le destinataire."
            );
        }

        return message;
    }

    function report(error) {

        console.error(
            "SmartGeo Messagerie :",
            error
        );

        status(
            errorMessage(error),
            "error"
        );
    }

    function setBusy(value) {

        busy = value;

        const textButton = $chat("sgSendMessage");
        const fileButton = $chat("sgSendAttachment");
        const picker = $chat("sgPickFile");

        if (textButton) {
            textButton.disabled = value || selectedId <= 0;
        }

        if (picker) {
            picker.disabled = value || selectedId <= 0;
        }

        if (fileButton) {
            fileButton.disabled =
                value ||
                selectedId <= 0 ||
                !$chat("sgFileInput")?.files?.length;
        }
    }

    function contactName(id) {

        const item = contacts.find(
            c => Number(c.id) === Number(id)
        );

        return (
            item?.fullName ||
            item?.username ||
            "Agent SmartGeo"
        );
    }

    function renderContacts() {

        const list = $chat("sgContactList");

        if (!list) return;

        list.replaceChildren();

        if (!contacts.length) {

            const empty = document.createElement("p");

            empty.className = "sg-empty";
            empty.textContent = "Aucun agent disponible.";

            list.appendChild(empty);
            return;
        }

        contacts.forEach(contact => {

            const id = Number(contact.id);
            const name = contactName(id);

            const button = document.createElement("button");

            button.type = "button";
            button.className = "sg-contact";
            button.dataset.sgContact = String(id);

            if (id === selectedId) {
                button.classList.add("active");
            }

            const avatar = document.createElement("span");

            avatar.className = "sg-contact-avatar";
            avatar.textContent =
                name.charAt(0).toUpperCase();

            const info = document.createElement("span");

            const title = document.createElement("strong");

            title.className = "sg-contact-name";
            title.textContent = name;

            const role = document.createElement("small");

            role.className = "sg-contact-role";
            role.textContent = "Agent SmartGeo";

            info.appendChild(title);
            info.appendChild(role);

            button.appendChild(avatar);
            button.appendChild(info);

            list.appendChild(button);
        });
    }

    async function messagesPageModern() {

        const [contactResult, inboxResult] =
            await Promise.all([
                api("/messages/contacts"),
                api("/messages/inbox")
            ]);

        if (page !== "messages") return;

        contacts = arr(contactResult);

        const inbox = arr(inboxResult);

        const unread = inbox.filter(
            item => !item.read_at
        ).length;

        $chat("adminContent").innerHTML = `
            <section class="sg-messenger sg-modern-messenger">

                <aside class="sg-contacts">

                    <div class="sg-contacts-heading">
                        <h2>Conversations</h2>
                        <span class="sg-unread-count">
                            ${unread} non lus
                        </span>
                    </div>

                    <div id="sgContactList"></div>

                </aside>

                <div class="sg-chat-panel">

                    <header class="sg-chat-header">

                        <div>
                            <span class="sg-chat-eyebrow">
                                CONVERSATION
                            </span>

                            <strong id="sgContactTitle">
                                Choisissez un agent
                            </strong>
                        </div>

                        <button
                            type="button"
                            class="sg-refresh-btn"
                            id="sgRefreshMessages">
                            Actualiser
                        </button>

                    </header>

                    <div
                        class="sg-thread"
                        id="sgThread">

                        <p class="sg-empty">
                            Selectionnez une conversation.
                        </p>

                    </div>

                    <section class="sg-chat-compose">

                        <form
                            id="sgTextForm"
                            class="sg-composer-form">

                            <div class="sg-compose-heading">
                                <div>
                                    <strong>Nouveau message</strong>
                                    <span>
                                        Communiquer avec l'agent
                                        selectionne
                                    </span>
                                </div>
                            </div>

                            <label
                                class="sg-visually-hidden"
                                for="sgMessageText">
                                Contenu du message
                            </label>

                            <textarea
                                id="sgMessageText"
                                rows="2"
                                maxlength="4000"
                                placeholder="Ecrivez votre message..."
                                required></textarea>

                            <div class="sg-composer-toolbar">

                                <div class="sg-file-zone">

                                    <button
                                        type="button"
                                        id="sgPickFile"
                                        class="sg-attach-button">
                                        &#128206;
                                        Joindre un fichier
                                    </button>

                                    <input
                                        id="sgFileInput"
                                        type="file"
                                        accept=".jpg,.jpeg,.png,.pdf,.txt"
                                        hidden>

                                </div>

                                <div class="sg-send-actions">

                                    <button
                                        type="button"
                                        id="sgSendAttachment"
                                        class="sg-file-send"
                                        disabled>
                                        Envoyer fichier
                                    </button>

                                    <button
                                        type="submit"
                                        id="sgSendMessage"
                                        class="sg-text-send">
                                        Envoyer message
                                    </button>

                                </div>

                            </div>

                            <div
                                id="sgSelectedFile"
                                class="sg-selected-file"
                                hidden></div>

                            <p
                                id="sgInlineStatus"
                                class="sg-inline-status"
                                role="status"
                                aria-live="polite">
                                Formats : JPG, PNG, PDF, TXT.
                                Taille maximale : 2 Mo.
                            </p>

                        </form>

                    </section>

                </div>

            </section>
        `;

        if (contacts.length) {

            const stillExists = contacts.some(
                item => Number(item.id) === selectedId
            );

            if (!stillExists) {
                selectedId = Number(contacts[0].id);
            }

            renderContacts();

            await showChat(selectedId);

        } else {

            selectedId = 0;
            renderContacts();
            setBusy(false);
        }
    }

    function createBubble(message) {

        const mine =
            Number(message.sender_id) ===
            Number(admin?.id);

        const content = String(
            message.content || ""
        );

        const isFile = content.startsWith(
            "[FICHIER] "
        );

        const bubble = document.createElement("div");

        bubble.className =
            "sg-bubble" +
            (mine ? " mine" : "");

        const author = document.createElement("small");

        author.textContent =
            mine ? "Moi - ADMIN" : "Agent";

        bubble.appendChild(author);

        if (isFile) {

            const download = document.createElement("button");

            download.type = "button";
            download.className = "sg-download";

            download.dataset.sgDownload =
                String(message.id);

            download.textContent =
                "\u{1F4CE} " +
                content.substring("[FICHIER] ".length);

            bubble.appendChild(download);

        } else {

            const text = document.createElement("div");

            text.textContent = content;
            bubble.appendChild(text);
        }

        return bubble;
    }

    async function showChat(otherId) {

        const id = Number(otherId);

        if (
            !Number.isSafeInteger(id) ||
            id <= 0
        ) {
            return;
        }

        const version = ++requestVersion;

        selectedId = id;
        chatId = id;

        renderContacts();

        const title = $chat("sgContactTitle");

        if (title) {
            title.textContent = contactName(id);
        }

        const thread = $chat("sgThread");

        if (thread) {
            thread.textContent = "Chargement des messages...";
        }

        const result = await api(
            "/messages/conversation/" + id
        );

        if (
            version !== requestVersion ||
            page !== "messages" ||
            selectedId !== id
        ) {
            return;
        }

        const messages = arr(result);
        const currentThread = $chat("sgThread");

        if (!currentThread) return;

        currentThread.replaceChildren();

        if (!messages.length) {

            const empty = document.createElement("p");

            empty.className = "sg-empty";

            empty.textContent =
                "Aucun message. Commencez la conversation.";

            currentThread.appendChild(empty);

        } else {

            messages.forEach(item => {
                currentThread.appendChild(
                    createBubble(item)
                );
            });
        }

        currentThread.scrollTop =
            currentThread.scrollHeight;

        const unread = messages.filter(
            item =>
                Number(item.receiver_id) ===
                Number(admin?.id) &&
                !item.read_at
        );

        // La lecture ne doit pas bloquer
        // l'interface des messages.

        unread.slice(0, 30).forEach(item => {

            api(
                "/messages/" + item.id + "/read",
                "PUT"
            ).catch(console.warn);
        });

        setBusy(busy);
    }

    function updateSelectedFile() {

        const input = $chat("sgFileInput");
        const preview = $chat("sgSelectedFile");

        if (!input || !preview) return;

        const file = input.files?.[0];

        preview.replaceChildren();

        if (!file) {
            preview.hidden = true;
            setBusy(busy);
            return;
        }

        const extension = file.name
            .split(".")
            .pop()
            .toLowerCase();

        if (!MIME[extension]) {

            input.value = "";
            preview.hidden = true;

            status(
                "Format non accepte. Choisissez JPG, PNG, PDF ou TXT.",
                "error"
            );

            setBusy(busy);
            return;
        }

        if (
            file.size <= 0 ||
            file.size > MAX_BYTES
        ) {

            input.value = "";
            preview.hidden = true;

            status(
                "Ce fichier depasse 2 Mo ou est vide.",
                "error"
            );

            setBusy(busy);
            return;
        }

        const icon = document.createElement("span");

        icon.textContent = "\u{1F4C4}";

        const name = document.createElement("span");

        name.className = "sg-file-name";

        name.textContent =
            file.name +
            " - " +
            Math.ceil(file.size / 1024) +
            " Ko";

        const remove = document.createElement("button");

        remove.type = "button";
        remove.className = "sg-remove-file";

        remove.id = "sgRemoveFile";
        remove.textContent = "\u00D7";

        remove.setAttribute(
            "aria-label",
            "Retirer la piece jointe"
        );

        preview.append(icon, name, remove);

        preview.hidden = false;

        status(
            "Fichier pret. Cliquez sur Envoyer fichier.",
            "ready"
        );

        setBusy(busy);
    }

    async function sendText() {

        if (busy) return;

        const receiverId = selectedId;

        const input = $chat("sgMessageText");

        const content = input?.value.trim() || "";

        if (receiverId <= 0) {
            status("Choisissez un agent.", "error");
            return;
        }

        if (!content || content.length > 4000) {
            status(
                "Le message doit contenir entre 1 et 4000 caracteres.",
                "error"
            );
            return;
        }

        setBusy(true);

        status("Envoi du message...", "loading");

        try {

            await api(
                "/messages",
                "POST",
                {
                    receiverId,
                    content
                }
            );

            input.value = "";

            await showChat(receiverId);

            status("Message envoye avec succes.", "success");

        } catch (error) {
            report(error);

        } finally {
            setBusy(false);
        }
    }

    function readBase64(file) {

        return new Promise((resolve, reject) => {

            const reader = new FileReader();

            reader.onload = () => {

                const value = String(
                    reader.result || ""
                );

                const separator = value.indexOf(",");

                if (separator < 0) {
                    reject(
                        new Error(
                            "Encodage du fichier invalide."
                        )
                    );
                    return;
                }

                resolve(value.substring(separator + 1));
            };

            reader.onerror = () => {

                reject(
                    new Error(
                        "Lecture du fichier impossible."
                    )
                );
            };

            reader.readAsDataURL(file);
        });
    }

    async function sendFile() {

        if (busy) return;

        const input = $chat("sgFileInput");
        const file = input?.files?.[0];
        const receiverId = selectedId;

        if (!file) {
            status("Choisissez un fichier.", "error");
            return;
        }

        if (receiverId <= 0) {
            status("Choisissez un agent.", "error");
            return;
        }

        if (
            file.size <= 0 ||
            file.size > MAX_BYTES
        ) {
            status("Taille maximale : 2 Mo.", "error");
            return;
        }

        const extension = file.name
            .split(".")
            .pop()
            .toLowerCase();

        const mimeType = MIME[extension];

        if (!mimeType) {
            status("Format non accepte.", "error");
            return;
        }

        setBusy(true);

        status(
            "Envoi de la piece jointe en cours...",
            "loading"
        );

        try {

            const base64 = await readBase64(file);

            await api(
                "/message-files",
                "POST",
                {
                    receiverId,
                    fileName: file.name,
                    mimeType,
                    base64
                }
            );

            input.value = "";

            updateSelectedFile();

            await showChat(receiverId);

            status(
                "Piece jointe envoyee avec succes.",
                "success"
            );

        } catch (error) {
            report(error);

        } finally {
            setBusy(false);
        }
    }

    async function downloadFile(messageId) {

        const id = Number(messageId);

        if (
            !Number.isSafeInteger(id) ||
            id <= 0
        ) {
            throw new Error("Identifiant invalide.");
        }

        status("Telechargement en cours...", "loading");

        const result = await api(
            "/message-files/" + id
        );

        const file = obj(result);

        if (!file?.base64) {
            throw new Error(
                "Le serveur n'a retourne aucun fichier."
            );
        }

        const binary = atob(file.base64);

        const bytes = new Uint8Array(binary.length);

        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }

        const blob = new Blob(
            [bytes],
            {
                type: file.mimeType ||
                    "application/octet-stream"
            }
        );

        const url = URL.createObjectURL(blob);

        const link = document.createElement("a");

        link.href = url;

        link.download = String(
            file.fileName || "piece-jointe"
        )
            .split(/[\\/]/)
            .pop();

        document.body.appendChild(link);
        link.click();
        link.remove();

        setTimeout(() => {
            URL.revokeObjectURL(url);
        }, 30000);

        status("Fichier telecharge.", "success");
    }

    // Remplace exclusivement la messagerie
    // du dashboard ADMIN existant.

    messagesPage = messagesPageModern;
    showConversation = showChat;

    document.addEventListener("click", event => {

        const button = event.target.closest("button");

        if (!button) return;

        if (button.dataset.sgContact) {

            showChat(
                button.dataset.sgContact
            ).catch(report);

            return;
        }

        if (button.id === "sgRefreshMessages") {

            showChat(selectedId).catch(report);
            return;
        }

        if (button.id === "sgPickFile") {

            $chat("sgFileInput")?.click();
            return;
        }

        if (button.id === "sgRemoveFile") {

            const input = $chat("sgFileInput");

            if (input) {
                input.value = "";
            }

            updateSelectedFile();

            status("Piece jointe retiree.");
            return;
        }

        if (button.id === "sgSendAttachment") {

            sendFile().catch(report);
            return;
        }

        if (button.dataset.sgDownload) {

            downloadFile(
                button.dataset.sgDownload
            ).catch(report);
        }
    });

    document.addEventListener("change", event => {

        if (event.target.id === "sgFileInput") {
            updateSelectedFile();
        }
    });

    document.addEventListener("submit", event => {

        if (event.target.id !== "sgTextForm") {
            return;
        }

        event.preventDefault();

        sendText().catch(report);
    });

    const pageTitles = {
        home: "Vue d'ensemble",
        users: "Utilisateurs",
        missions: "Missions",
        gps: "Carte GPS",
        schedules: "Plannings GPS",
        messages: "Messagerie",
        requests: "Localisations",
        reports: "Signalements"
    };

    document.addEventListener(
        "click",
        event => {

            const nav = event.target.closest(
                "button[data-page]"
            );

            if (!nav) return;

            const title = $chat("sgPageTitle");

            if (title) {
                title.textContent =
                    pageTitles[nav.dataset.page] ||
                    "Administration";
            }
        },
        true
    );

})();
