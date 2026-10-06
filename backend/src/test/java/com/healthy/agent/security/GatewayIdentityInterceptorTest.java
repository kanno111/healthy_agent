package com.healthy.agent.security;

import com.healthy.agent.common.AgentException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayIdentityInterceptorTest {
    private final GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor();

    @Test
    void acceptsValidGatewayIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayIdentityInterceptor.USER_ID_HEADER, "12");
        request.addHeader(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF");

        boolean accepted = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(accepted).isTrue();
        assertThat(request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID)).isEqualTo(12L);
        assertThat(request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ROLE)).isEqualTo("STAFF");
    }

    @Test
    void rejectsMissingGatewayIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void rejectsInvalidUserId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayIdentityInterceptor.USER_ID_HEADER, "not-a-number");
        request.addHeader(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AgentException.class);
    }
}
