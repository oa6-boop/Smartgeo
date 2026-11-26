
"use strict";

(function () {
    const surWildFly =
        window.location.pathname.startsWith("/smartgeo/");

    const API_BASE = surWildFly
        ? window.location.origin + "/smartgeo/api"
        : "http://" +
          window.location.hostname +
          ":8080/smartgeo/api";

    window.SMARTGEO = Object.freeze({
        API_BASE: API_BASE
    });
})();
