package com.healthy.agent.security;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.AgentSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalDevAuthenticationTest {
    @Test
    void issuesAdminTokenAndAllowsInterceptorWithoutGatewayHeaders() {
        LocalDevAuthentication authentication = localDevAuthentication();
        LocalDevLoginResult login = authentication.login("STAFF");
        ObjectProvider<LocalDevAuthentication> provider = mock();
        when(provider.getIfAvailable()).thenReturn(authentication);
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(provider);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + login.token());

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID)).isEqualTo(900002L);
        assertThat(request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ROLE)).isEqualTo("STAFF");
        assertThat(login.token()).startsWith("local-dev.");
    }

    @Test
    void keepsPatientAndAdminTokensRoleIsolated() {
        LocalDevAuthentication authentication = localDevAuthentication();

        LocalDevLoginResult patient = authentication.login("PATIENT");
        LocalDevLoginResult admin = authentication.login("STAFF");

        assertThat(patient.role()).isEqualTo("PATIENT");
        assertThat(patient.userId()).isEqualTo(900001L);
        assertThat(admin.role()).isEqualTo("STAFF");
        assertThat(admin.userId()).isEqualTo(900002L);
        assertThat(patient.token()).isNotEqualTo(admin.token());
    }

    @Test
    void gatewayModeDoesNotIssueLocalToken() {
        LocalDevAuthentication authentication = new LocalDevAuthentication(new AgentSecurityProperties(
                "gateway", 900001, "patient", 900002, "admin"));

        assertThatThrownBy(() -> authentication.login("STAFF"))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.LOCAL_DEV_AUTH_DISABLED));
    }

    @Test
    void rejectsUnknownLocalRole() {
        assertThatThrownBy(() -> localDevAuthentication().login("DOCTOR"))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_LOCAL_DEV_ROLE));
    }

    private LocalDevAuthentication localDevAuthentication() {
        return new LocalDevAuthentication(new AgentSecurityProperties(
                "local-dev", 900001, "本地患者", 900002, "本地管理员"));
    }
}
