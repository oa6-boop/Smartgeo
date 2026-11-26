package com.smartgeo.services;

/*
 * Point d'extension pour Firebase Cloud Messaging (FCM).
 * L'envoi réel de notifications push sera configuré avec Firebase Admin SDK
 * et les identifiants privés du projet Firebase. Ne pas placer de clé privée ici.
 */
public class NotificationService {
    public void sendPushNotification(String deviceToken,String title,String body) {
        throw new UnsupportedOperationException(
                "FCM n'est pas encore configuré. Ajouter Firebase Admin SDK et sa configuration serveur.");
    }
}
