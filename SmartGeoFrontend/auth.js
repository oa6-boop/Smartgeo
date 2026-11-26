
"use strict";

const API_BASE =
    window.SMARTGEO?.API_BASE ||
    "http://localhost:8080/smartgeo/api";

const Auth = {
    getToken() {
        return localStorage.getItem("smartgeo_token");
    },

    getUser() {
        try {
            return JSON.parse(
                localStorage.getItem("smartgeo_user") || "null"
            );
        } catch {
            return null;
        }
    },

    logout() {
        localStorage.removeItem("smartgeo_token");
        localStorage.removeItem("smartgeo_user");
        window.location.replace("index.html");
    },

    async request(path, options = {}) {
        const headers = {
            Accept: "application/json",
            ...(options.headers || {})
        };

        const token = this.getToken();

        if (token) {
            headers.Authorization = "Bearer " + token;
        }

        const requestOptions = {
            ...options,
            headers
        };

        if (options.body !== undefined &&
            options.body !== null) {

            if (typeof options.body === "string") {
                requestOptions.body = options.body;
            } else if (options.body instanceof FormData) {
                requestOptions.body = options.body;
            } else {
                requestOptions.body =
                    JSON.stringify(options.body);
            }

            if (!(options.body instanceof FormData)) {
                headers["Content-Type"] =
                    "application/json";
            }
        }

        let response;

        try {
            response = await fetch(
                API_BASE + path,
                requestOptions
            );
        } catch (error) {
            console.error("Erreur réseau :", error);
            throw new Error(
                "Impossible de contacter SmartGeo. " +
                "Vérifiez que WildFly est démarré."
            );
        }

        const raw = await response.text();
        let result = null;

        if (raw) {
            try {
                result = JSON.parse(raw);
            } catch {
                result = { message: raw };
            }
        }

        if (!response.ok) {
            if (response.status === 401 && token) {
                localStorage.removeItem("smartgeo_token");
                localStorage.removeItem("smartgeo_user");
            }

            throw new Error(
                result?.message ||
                result?.error ||
                "Erreur HTTP " + response.status
            );
        }

        if (result?.success === false) {
            throw new Error(
                result.message ||
                "L'opération a échoué."
            );
        }

        return result;
    },

    async login(username, password) {
        const result = await this.request(
            "/auth/login",
            {
                method: "POST",
                body: {
                    username: username.trim(),
                    password: password
                }
            }
        );

        const token = result?.data?.token;
        const user = result?.data?.user;

        if (!token || !user) {
            console.error(
                "Réponse API inattendue :",
                result
            );

            throw new Error(
                "Réponse de connexion incomplète."
            );
        }

        localStorage.setItem(
            "smartgeo_token",
            token
        );

        localStorage.setItem(
            "smartgeo_user",
            JSON.stringify(user)
        );

        if (String(user.role).toUpperCase() === "ADMIN") {
            window.location.replace(
                "dashboard-admin.html"
            );
        } else {
            window.location.replace("dashboard.html");
        }

        return result;
    }
};

// Compatibilité avec les appels existants.
function getToken() {
    return Auth.getToken();
}

function logout() {
    Auth.logout();
}

function apiRequest(path, options = {}) {
    return Auth.request(path, options);
}

function login(username, password) {
    return Auth.login(username, password);
}

// Initialisation du formulaire de connexion.
document.addEventListener("DOMContentLoaded", () => {
    const form =
        document.getElementById("loginForm");

    if (!form) {
        return;
    }

    const usernameInput =
        document.getElementById("loginIdentifier");

    const passwordInput =
        document.getElementById("loginPassword");

    const errorElement =
        document.getElementById("loginError");

    const loginButton =
        document.getElementById("loginButton");

    if (!usernameInput || !passwordInput) {
        console.error(
            "Champs de connexion introuvables."
        );
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        const username = usernameInput.value.trim();
        const password = passwordInput.value;

        if (errorElement) {
            errorElement.textContent = "";
        }

        if (!username || !password) {
            if (errorElement) {
                errorElement.textContent =
                    "Veuillez remplir tous les champs.";
            }
            return;
        }

        try {
            if (loginButton) {
                loginButton.disabled = true;
                loginButton.textContent = "Connexion...";
            }

            await Auth.login(username, password);

        } catch (error) {
            console.error(
                "Erreur de connexion :",
                error
            );

            if (errorElement) {
                errorElement.textContent = error.message;
            } else {
                alert(error.message);
            }

        } finally {
            if (loginButton) {
                loginButton.disabled = false;
                loginButton.textContent =
                    "Se connecter";
            }
        }
    });
});
