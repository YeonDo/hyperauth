package com.tmax.hyperauth.authenticator.passwordUpdateAlert;

import com.tmax.hyperauth.authenticator.AuthenticatorConstants;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.authentication.ConfigurableAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * @author taegeon_woo@tmax.co.kr
 */
public class PasswordUpdateAlertAuthenticatorFactory implements AuthenticatorFactory, ConfigurableAuthenticatorFactory {

    public static final String PROVIDER_ID = "password-update-alert-authenticator ";
    private static final PasswordUpdateAlertAuthenticator SINGLETON = new PasswordUpdateAlertAuthenticator();
    private static final List<ProviderConfigProperty> configProperties = new ArrayList<ProviderConfigProperty>();

    static {
        ProviderConfigProperty property;
        property = new ProviderConfigProperty();
        property.setName(AuthenticatorConstants.CONF_PW_UPDATE_PERIOD);
        property.setLabel("Password Update Period in Month");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setHelpText("User will be asked to change password after certain month since last password update Date");
        configProperties.add(property);

        property = new ProviderConfigProperty();
        property.setName(AuthenticatorConstants.CONF_PW_UPDATE_SKIP_PERIOD);
        property.setLabel("Password Update Skip Period in Month");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setHelpText("Password Update Will be asked certain Month later, Shold Less than Password Update Period");
        configProperties.add(property);
    }

    @Override
    public String getId() { return PROVIDER_ID; }

    @Override
    public Authenticator create(KeycloakSession session) { return SINGLETON; }

    private static AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.DISABLED
    };
    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() { return REQUIREMENT_CHOICES; }

    @Override
    public boolean isUserSetupAllowed() { return false; }

    @Override
    public boolean isConfigurable() { return true; }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder
                .create()
                .property().name("aaaa")
                .type(ProviderConfigProperty.PASSWORD)
                .label("Encryption Key")
                .defaultValue("changeme")
                .helpText("Encryption key")
                .add()
                .property().name("bbbb")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Session Reference Mag Age")
                .defaultValue("30")
                .helpText("Maximum age of session reference in seconds")
                .add().property().name("cccc")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Session Validation URL")
                .defaultValue("")
                .helpText("Url to validate the encrypted session token against. " +
                        "The URI placeholder {sessionHandle} will be replaced with the encrypted sessionHandle. " +
                        "The URI placeholder {sessionHandleSalt} will be replaced with the salt used for the encrypted sessionHandle. " +
                        "An example URI can look like this: http://myserver/myapp/sessions/keycloak?sessionHandle={sessionHandle}&sessionHandleSalt={sessionHandleSalt}")
                .add().build();
    }

    @Override
    public String getHelpText() { return "Password Update Alert When User Login After Certain Month Since User Update Password"; }

    @Override
    public String getDisplayType() { return "Password Update Alert"; }

    @Override
    public String getReferenceCategory() { return "password-update-alert"; }

    @Override
    public void init(Config.Scope config) { }

    @Override
    public void postInit(KeycloakSessionFactory factory) { }

    @Override
    public void close() { }
}