package com.tmax.hyperauth.eventlistener.provider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Context;

import com.tmax.hyperauth.caller.HypercloudWebhookCaller;
import org.jboss.logging.Logger;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import com.google.gson.JsonObject;
import com.tmax.hyperauth.caller.HyperAuthCaller;
import com.tmax.hyperauth.caller.HypercloudOperatorCaller;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * @author taegeon_woo@tmax.co.kr
 */

public class HyperauthEventListenerProvider implements EventListenerProvider {

    private static final Logger logger = Logger.getLogger(HyperauthEventListenerProvider.class);
    @Context
    private KeycloakSession session;

    public HyperauthEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        System.out.println("Event Occurred:" + toString(event));

        if (event.getRealmId().equalsIgnoreCase("tmax")) {
            switch (event.getType().toString()) {
                case "REGISTER":
                    // when user registered, operator call for new role
                    System.out.println("New User Registered in tmax Realm, Give New role for User in Kubernetes");
                    try {
                        HypercloudOperatorCaller.createNewUserRole(event.getDetails().get("username"));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;

                case "LOGIN":
                    if (event.getClientId().equalsIgnoreCase("hypercloud4")
                        && event.getDetails().get("response_type") == null) {
                        EventDataObject.Eventlists loginEvent = makeAuditEvent("login", event.getDetails().get("username"), "success", 200);
                        try {
//                            HypercloudWebhookCaller.auditAuthentication(loginEvent);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                case "LOGIN_ERROR":
                    if (event.getClientId() != null && event.getClientId().equalsIgnoreCase("hypercloud4")) {
                        EventDataObject.Eventlists loginErrorEvent = makeAuditEvent("login failed", event.getDetails().get("username"), event.getError(), 400);
                        try {
//                            HypercloudWebhookCaller.auditAuthentication(loginErrorEvent);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                case "LOGOUT":
                    String userName = session.users().getUserById(event.getUserId(), session.realms().getRealmByName("tmax")).getUsername();
                    EventDataObject.Eventlists logoutEvent = makeAuditEvent("logout", userName, "success", 200);
                    try {
//                        HypercloudWebhookCaller.auditAuthentication(logoutEvent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
            }
        }


    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean b) {

        System.out.println("Admin Event Occurred:" + toString(adminEvent));

        // when user registered by admin, operator call for new role 
        if (adminEvent.getOperationType().toString().equalsIgnoreCase("CREATE")
                && adminEvent.getResourcePath().toString().startsWith("users")
                && adminEvent.getResourcePath().toString().length() == 42) {
            System.out.println("New User Registered in tmax Realm by Admin, Give New role for User in Kubernetes");
            try {
                String userName = session.users().getUserById(adminEvent.getResourcePath().toString().substring(6), session.realms().getRealmByName("tmax")).getUsername();
                System.out.println("userName : " + userName);

                HypercloudOperatorCaller.createNewUserRole(userName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // when user deleted by admin, operator call for delete role
        if (adminEvent.getOperationType().toString().equalsIgnoreCase("DELETE") && adminEvent.getResourcePath().toString().startsWith("users")) {
            System.out.println("User Deleted in tmax Realm by Admin, Delete user role for new User in Kubernetes");
            try {
                // important : session에는 이미 user가 지워져서 user 정보를 들고 올수 없음 그래서 http콜로 한다!
                String accessToken = HyperAuthCaller.loginAsAdmin();
                JsonObject user = HyperAuthCaller.getUser(adminEvent.getResourcePath().toString().substring(6), accessToken.replaceAll("\"", ""));
                HypercloudOperatorCaller.deleteNewUserRole(user.get("username").toString().replaceAll("\"", ""));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void close() {

    }

    private EventDataObject.Eventlists makeAuditEvent(String verb, String username, String reason, int code) {
        EventDataObject.Eventlists event = new EventDataObject.Eventlists();
        List<EventDataObject.Item> items = new ArrayList<>();
        EventDataObject.Item item = new EventDataObject.Item();
        item.setVerb(verb);
        EventDataObject.User user = new EventDataObject.User();
        user.setUsername(username);
        item.setUser(user);
        EventDataObject.ResponseStatus responseStatus = new EventDataObject.ResponseStatus();
        responseStatus.setReason(reason);
        responseStatus.setCode(code);
        item.setResponseStatus(responseStatus);
        items.add(item);
        event.setItems(items);
        return event;
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