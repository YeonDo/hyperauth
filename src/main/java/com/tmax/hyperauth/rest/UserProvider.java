package com.tmax.hyperauth.rest;

import java.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.jboss.resteasy.spi.HttpResponse;
import org.keycloak.OAuthErrorException;
import org.keycloak.TokenVerifier;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.ObjectUtil;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.SignatureProvider;
import org.keycloak.crypto.SignatureVerifierContext;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.*;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.policy.PasswordPolicyNotMetException;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.TokenManager.NotBeforeCheck;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.services.resource.RealmResourceProvider;

import com.tmax.hyperauth.caller.HypercloudOperatorCaller;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.UserResource;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;

/**
 * @author taegeon_woo@tmax.co.kr
 */

public class UserProvider implements RealmResourceProvider {
    @Context
    private KeycloakSession session;
   
    @Context
    private HttpResponse response;
   
    @Context
    private ClientConnection clientConnection;

    public UserProvider(KeycloakSession session) {
        this.session = session;
    }
    private AccessToken token;
    private ClientModel clientModel;

    @Override
    public Object getResource() {
        return this;
    }

    Status status = null;
	String out = null;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response POST(UserRepresentation rep) {
        System.out.println("***** POST /User");

        RealmModel realm = session.realms().getRealmByName("tmax");

        clientConnection = session.getContext().getConnection();
        EventBuilder event = new EventBuilder(realm, session, clientConnection); // FIXME

        String username = rep.getUsername();
        if(realm.isRegistrationEmailAsUsername()) {
            username = rep.getEmail();
        }
        if (ObjectUtil.isBlank(username)) {
            return ErrorResponse.error("User name is missing", Response.Status.BAD_REQUEST);
        }

        // Double-check duplicated username and email here due to federation
        if (session.users().getUserByUsername(username, realm) != null) {
            return ErrorResponse.exists("User exists with same username");
        }
        if (rep.getEmail() != null && !realm.isDuplicateEmailsAllowed()) {
            try {
                if(session.users().getUserByEmail(rep.getEmail(), realm) != null) {
                    return ErrorResponse.exists("User exists with same email");
                }
            } catch (ModelDuplicateException e) {
                return ErrorResponse.exists("User exists with same email");
            }
        }

        try {
            UserModel user = session.users().addUser(realm, username);
            Set<String> emptySet = Collections.emptySet();

            UserResource.updateUserFromRep(user, rep, emptySet, realm, session, false);
            RepresentationToModel.createFederatedIdentities(rep, session, realm, user);
            RepresentationToModel.createGroups(rep, realm, user);
            RepresentationToModel.createCredentials(rep, session, realm, user, true);

            event.event(EventType.REGISTER).user(user).realm("tmax").detail("username", username).success(); // FIXME

            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().commit();
            }

            return Response.created(session.getContext().getUri().getAbsolutePathBuilder().path(user.getId()).build()).build();
        } catch (ModelDuplicateException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            return ErrorResponse.exists("User exists with same username or email");
        } catch (PasswordPolicyNotMetException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            return ErrorResponse.error("Password policy not met", Response.Status.BAD_REQUEST);
        } catch (ModelException me){
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            System.out.println("Could not create user");
            return ErrorResponse.error("Could not create user", Response.Status.BAD_REQUEST);
        }
    }


	@GET
    @Path("{userName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("userName") final String userName) {
        System.out.println("***** GET /User");

        UserRepresentation userOut = new UserRepresentation();
    	System.out.println("userName : " + userName);
        RealmModel realm = session.getContext().getRealm();
        String realmName = realm.getDisplayName();
        if (realmName == null) {
        	realmName = realm.getName();
        }
        List <String> groupName = null;
        try {
            UserModel user = session.users().getUserByUsername(userName, session.realms().getRealmByName(realmName));
        	System.out.println("email : " + user.getEmail());

        	for( GroupModel group : user.getGroups()) {
        		if(groupName == null) groupName = new ArrayList<>();
        		groupName.add(group.getName());
            	System.out.println("groupName : " + group.getName());
        	}

        	userOut.setUsername(userName);
        	userOut.setEmail(user.getEmail());
        	userOut.setGroups(groupName);
            userOut.setEnabled(user.isEnabled());

        	// Login Failure Data
            UserLoginFailureModel loginFailureModel = session.sessions().getUserLoginFailure(realm, user.getId());
            if ( loginFailureModel != null ){
                boolean disabled;
                if (user == null) {
                    disabled = Time.currentTime() < loginFailureModel.getFailedLoginNotBefore();
                } else {
                    disabled = session.getProvider(BruteForceProtector.class).isTemporarilyDisabled(session, realm, user);
                }
                Map<String, List<String>> data = new HashMap<>();
                data.put("temporarilyDisabled", new ArrayList<>(Arrays.asList(String.valueOf(disabled))));
                data.put("numFailures", new ArrayList<>(Arrays.asList(Integer.toString(loginFailureModel.getNumFailures()))));
                data.put("lastIPFailure", new ArrayList<>(Arrays.asList(loginFailureModel.getLastIPFailure())));
                data.put("lastFailure", new ArrayList<>(Arrays.asList(Long.toString(loginFailureModel.getLastFailure()))));
                data.put("failedLoginNotBefore", new ArrayList<>(Arrays.asList(Integer.toString(loginFailureModel.getFailedLoginNotBefore()))));
                data.put("remainSecond", new ArrayList<>(Arrays.asList( Long.toString( loginFailureModel.getFailedLoginNotBefore() - loginFailureModel.getLastFailure()/1000 ))));
                userOut.setAttributes(data);
            }

            status = Status.OK;
        	return Util.setCors(status, userOut);        
        }catch (Exception e) {
            e.printStackTrace();
            System.out.println("Exception " + e.getMessage());
            System.out.println("No Corresponding UserName");
        	status = Status.BAD_REQUEST;
        	out = "No Corresponding UserName";
        	return Util.setCors(status, out);
        }  
    }
    
    @SuppressWarnings("unchecked")
	@DELETE
    @Path("{userName}")
    @QueryParam("token")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("userName") final String userName, @QueryParam("token") String tokenString ) {
        System.out.println("***** DELETE /User");

        System.out.println("userName : " + userName);
        System.out.println("token : " + tokenString);
        RealmModel realm = session.getContext().getRealm();
        clientConnection = session.getContext().getConnection();
        EventBuilder event = new EventBuilder(realm, session, clientConnection).detail("username", userName); // FIXME

        try {
            verifyToken(tokenString, realm);
            if (!token.getPreferredUsername().equalsIgnoreCase(userName)) {
                out = "Cannot delete other user";
                throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Cannot delete other user", Response.Status.BAD_REQUEST);
            }
        } catch (Exception e) {
            e.printStackTrace();
            status = Status.BAD_REQUEST;
            return Util.setCors(status, out);
        }

        session.getContext().setClient(clientModel);

        if (!clientModel.isEnabled()) {
            status = Status.BAD_REQUEST;
            out = "Disabled Client ";
        } else {
            String realmName = realm.getDisplayName();
            if (realmName == null) {
                realmName = session.getContext().getRealm().getName();
            }
            UserModel userModel = session.users().getUserByUsername(userName, session.realms().getRealmByName(realmName));

            try {
                if (userModel == null) {
                    out = "User not found";
                    throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "User not found", Response.Status.BAD_REQUEST);
                }
            } catch (Exception e) {
                status = Status.BAD_REQUEST;
                out = "User not found";
                return Util.setCors(status, out);
            }

            try {
                session.users().removeUser(realm, userModel);
                event.event(EventType.REMOVE_FEDERATED_IDENTITY).user(userModel).realm("tmax").success();
                System.out.println("Delete user role in k8s");
                HypercloudOperatorCaller.deleteNewUserRole(userName);

                status = Status.OK;
                out = " User [" + userName + "] Delete Success ";
            } catch (Exception e) {
                status = Status.BAD_REQUEST;
                out = "User [" + userName + "] Delete Falied  ";
            }
        }
        return Util.setCors(status, out);
    }


    @PUT
    @Path("{userName}")
    @QueryParam("token")
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("userName") final String userName, @QueryParam("token") String tokenString, UserRepresentation rep) {
        System.out.println("***** PUT /User");

        System.out.println("userName : " + userName);
        System.out.println("token : " + tokenString);
        RealmModel realm = session.getContext().getRealm();
        clientConnection = session.getContext().getConnection();
        EventBuilder event = new EventBuilder(realm, session, clientConnection); // FIXME

        try {
            verifyToken(tokenString, realm);

            if (!token.getPreferredUsername().equalsIgnoreCase(userName)) {
                out = "Cannot update other user";
                throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Cannot update other user", Response.Status.BAD_REQUEST);
            }
        } catch (Exception e) {
            e.printStackTrace();
            status = Status.BAD_REQUEST;
            return Util.setCors(status, out);
        }

        session.getContext().setClient(clientModel);

        if (!clientModel.isEnabled()) {
            status = Status.BAD_REQUEST;
            out = "Disabled Client ";
        } else {
            String realmName = realm.getDisplayName();
            if (realmName == null) {
                realmName = session.getContext().getRealm().getName();
            }
            UserModel userModel = session.users().getUserByUsername(userName, session.realms().getRealmByName(realmName));

            try {
                if (userModel == null) {
                    out = "User not found";
                    throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "User not found", Response.Status.BAD_REQUEST);
                }
            } catch (Exception e) {
                status = Status.BAD_REQUEST;
                out = "User not found";
                return Util.setCors(status, out);
            }

            try {
                for ( String key : rep.getAttributes().keySet()){
                    userModel.removeAttribute(key);
                    userModel.setAttribute(key, rep.getAttributes().get(key));
                }
                event.event(EventType.UPDATE_PROFILE).user(userModel).realm("tmax").detail("username", userName).success();
                status = Status.OK;
               out = " User [" + userName + "] Update Success ";
            } catch (Exception e) {
                status = Status.BAD_REQUEST;
                out = "User [" + userName + "] Update Falied  ";
            }
        }
        return Util.setCors(status, out);
    }

    @OPTIONS
    @Path("{path : .*}")
    public Response other() {
        System.out.println("***** OPTIONS /User");
        return Util.setCors( Status.OK, null);
    }

    private void verifyToken(String tokenString, RealmModel realm) throws VerificationException {
        if (tokenString == null) {
            out = "Token not provided";
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Token not provided", Status.BAD_REQUEST);
        }
        TokenVerifier<AccessToken> verifier = TokenVerifier.create(tokenString, AccessToken.class).withDefaultChecks()
                .realmUrl(Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));

        SignatureVerifierContext verifierContext = session.getProvider(SignatureProvider.class,
                verifier.getHeader().getAlgorithm().name()).verifier(verifier.getHeader().getKeyId());
        verifier.verifierContext(verifierContext);
        try {
            token = verifier.verify().getToken();
        } catch (Exception e) {
            out = "token invalid";
        }
        clientModel = realm.getClientByClientId(token.getIssuedFor());
        if (clientModel == null) {
            out = "Client not found";
            throw new ErrorResponseException(OAuthErrorException.INVALID_REQUEST, "Client not found", Status.NOT_FOUND);
        }

        TokenVerifier.createWithoutSignature(token)
                .withChecks(NotBeforeCheck.forModel(clientModel))
                .verify();
    }

    @Override
    public void close() {
    }

}