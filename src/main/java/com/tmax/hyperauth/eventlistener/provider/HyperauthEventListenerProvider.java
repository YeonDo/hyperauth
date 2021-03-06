package com.tmax.hyperauth.eventlistener.provider;

import java.util.*;
import javax.ws.rs.core.Context;
import com.tmax.hyperauth.authenticator.AuthenticatorConstants;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import com.google.gson.JsonObject;
import com.tmax.hyperauth.caller.HyperAuthCaller;
import com.tmax.hyperauth.caller.HypercloudOperatorCaller;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.timer.TimerProvider;
import org.keycloak.timer.TimerSpi;

/**
 * @author taegeon_woo@tmax.co.kr
 */

public class HyperauthEventListenerProvider extends TimerSpi implements EventListenerProvider {

    private static final Logger logger = Logger.getLogger(HyperauthEventListenerProvider.class);
    @Context
    private KeycloakSession session;
    public HyperauthEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        String userName = "";
//        System.out.println("Event Occurred:" + toString(event));
        logger.info("Event Occurred:" + toString(event));

        if (event.getRealmId().equalsIgnoreCase("tmax")) {

            TopicEvent topicEvent = TopicEvent.makeTopicEvent(event, null);

            switch (event.getType().toString()) {
                case "REGISTER":
                    topicEvent = TopicEvent.makeTopicEvent(event, event.getDetails().get("username"));

                    // when user registered, operator call for new role
                    System.out.println("New User Registered in tmax Realm, Give New role for User in Kubernetes");
                    try {
                        HypercloudOperatorCaller.createNewUserRole(event.getDetails().get("username"));   //FIXME : Delete Later !!!!
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                case "LOGIN":
                    topicEvent = TopicEvent.makeTopicEvent(event, event.getDetails().get("username"));

                    // For Session-Restrict Policy
                    if (!event.getDetails().get("username").equalsIgnoreCase("admin@tmax.co.kr")) { //FIXME : Delete Later !!!!
                        System.out.println("User [ " + event.getDetails().get("username") + " ], Client [ " + event.getClientId() + " ] Session-Restrict Start");
                        UserModel user = session.users().getUserById(event.getUserId(), session.realms().getRealmByName(event.getRealmId()));
                        RealmModel realm = session.realms().getRealmByName(event.getRealmId());
                        session.sessions().getUserSessions(realm, session.clients().getClientByClientId(realm, event.getClientId())).forEach(userSession -> {
                            if( userSession.getUser().getUsername().equalsIgnoreCase(event.getDetails().get("username"))
                                    && !userSession.getId().equals(event.getSessionId()) ) {
                                session.sessions().removeUserSession(realm, userSession);
                                System.out.println("Remove user session [ " + userSession.getId() + " ]");
                            }
                        } );
                        System.out.println("User [ " + event.getDetails().get("username") + " ], Client [ " + event.getClientId() + " ] Session-Restrict Success");
                    }
                    break;
                case "LOGIN_ERROR":
                    if (event.getDetails() != null && event.getDetails().get("username")!= null){
                        topicEvent = TopicEvent.makeTopicEvent(event, event.getDetails().get("username"));
                    }
                    break;
                case "LOGOUT":
                    topicEvent = TopicEvent.makeTopicEvent(event, session.users().getUserById(event.getUserId(), session.realms().getRealmByName("tmax")).getUsername());
                    break;
                case "SEND_VERIFY_EMAIL":
                    String email = event.getDetails().get("email");
                    topicEvent = TopicEvent.makeTopicEvent(event, email);
                    long interval = 1000 * 60 * 10;
                    TimerProvider timer = session.getProvider(TimerProvider.class);
                    timer.scheduleTask((KeycloakSession keycloakSession) -> {
                        System.out.println("Check If not User Email [ " + email + " ] verified, Delete user");
                        try {
                            timer.cancelTask(email);
                            UserModel user = keycloakSession.users().getUserById(event.getUserId(), keycloakSession.realms().getRealmByName(event.getRealmId()));
                            if (user != null) {
                                if (!user.isEmailVerified()) {
                                    keycloakSession.users().removeUser(keycloakSession.realms().getRealmByName(event.getRealmId()), user);
                                    System.out.println("User [" + event.getDetails().get("username") + " ] Deleted");
                                    System.out.println("Delete user role in k8s");
                                    HypercloudOperatorCaller.deleteNewUserRole(user.getUsername());
                                } else {
                                    System.out.println("Already Verified, Nothing to do");
                                }
                            } else {
                                System.out.println("User [" + event.getDetails().get("username") + " ] Already Deleted, nothing to do");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, interval, email);
                    break;
                case "UPDATE_PASSWORD" :
                        UserModel user = session.users().getUserById(event.getUserId(), session.realms().getRealmByName(event.getRealmId()));
                        user.setAttribute(AuthenticatorConstants.USER_ATTR_LAST_PW_UPDATE_DATE, Arrays.asList(Long.toString(event.getTime())));
            }
            // TOPIC Event Publish !!
            try {
                Producer.publishEvent("tmax", topicEvent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        System.out.println("Admin Event Occurred:" + toString(adminEvent));

        switch (adminEvent.getOperationType().toString()) {
            case "CREATE":
                if (adminEvent.getResourcePath().startsWith("users") && adminEvent.getResourcePath().toString().length() == 42){
                    // when user registered by admin, operator call for new role   //FIXME : Delete Later !!!!
                    System.out.println("New User Registered in tmax Realm by Admin, Give New role for User in Kubernetes");
                    try {
                        String userName = session.users().getUserById(adminEvent.getResourcePath().toString().substring(6), session.realms().getRealmByName("tmax")).getUsername();
                        System.out.println("userName : " + userName);

                        HypercloudOperatorCaller.createNewUserRole(userName);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                break;
            case "DELETE":
                if (adminEvent.getResourcePath().startsWith("users")){ //FIXME : Delete Later !!!!
                    System.out.println("User Deleted in tmax Realm by Admin, Delete user role for new User in Kubernetes");
                    try {
                        // important : session에는 이미 user가 지워져서 user 정보를 들고 올수 없음 그래서 http콜로 한다!
                        String accessToken = HyperAuthCaller.loginAsAdmin();
                        JsonObject user = HyperAuthCaller.getUser(adminEvent.getResourcePath().toString().substring(6), accessToken.replaceAll("\"", ""));
                        HypercloudOperatorCaller.deleteNewUserRole(user.get("username").toString().replaceAll("\"", ""));

                        // Topic Event
                        TopicEvent topicEvent = TopicEvent.makeOtherTopicEvent("USER_DELETE", user.get("username").toString().replaceAll("\"", ""), adminEvent.getTime());
                        Producer.publishEvent("tmax", topicEvent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                break;
            case "ACTION":
                if (adminEvent.getResourcePath().startsWith("users") && adminEvent.getResourcePath().endsWith("reset-password")){
                    UserModel user = session.users().getUserById(adminEvent.getResourcePath().split("/")[1], session.realms().getRealmByName("tmax"));
                    user.setAttribute(AuthenticatorConstants.USER_ATTR_LAST_PW_UPDATE_DATE, Arrays.asList(Long.toString(adminEvent.getTime())));
                }
                break;
        }

    }

    @Override
    public void close() {

    }

    private String toString(Event event) {
        StringBuilder sb = new StringBuilder();
        sb.append("type=");
        sb.append(event.getType());
        sb.append(", realmId=");
        sb.append(event.getRealmId());
        sb.append(", clientId=");
        sb.append(event.getClientId());
        sb.append(", userId=");
        sb.append(event.getUserId());
        sb.append(", ipAddress=");
        sb.append(event.getIpAddress());
        sb.append(", sessionId=");
        sb.append(event.getSessionId());
        sb.append(", time=");
        sb.append(event.getTime());
        if (event.getError() != null) {
            sb.append(", error=");
            sb.append(event.getError());
        }

        if (event.getDetails() != null) {
            for (Map.Entry<String, String> e : event.getDetails().entrySet()) {
                sb.append(", ");
                sb.append(e.getKey());
                if (e.getValue() == null || e.getValue().indexOf(' ') == -1) {
                    sb.append("=");
                    sb.append(e.getValue());
                } else {
                    sb.append("='");
                    sb.append(e.getValue());
                    sb.append("'");
                }
            }
        }
        return sb.toString();
    }

    private String toString(AdminEvent adminEvent) {
        StringBuilder sb = new StringBuilder();
        sb.append("operationType=");
        sb.append(adminEvent.getOperationType());
        sb.append(", realmId=");
        sb.append(adminEvent.getAuthDetails().getRealmId());
        sb.append(", clientId=");
        sb.append(adminEvent.getAuthDetails().getClientId());
        sb.append(", userId=");
        sb.append(adminEvent.getAuthDetails().getUserId());
        sb.append(", ipAddress=");
        sb.append(adminEvent.getAuthDetails().getIpAddress());
        sb.append(", resourcePath=");
        sb.append(adminEvent.getResourcePath());

        if (adminEvent.getError() != null) {
            sb.append(", error=");
            sb.append(adminEvent.getError());
        }
        return sb.toString();
    }
}