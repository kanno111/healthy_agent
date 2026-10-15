package com.healthy.agent.chat.history;

import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import com.healthy.agent.state.AgentStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientConversationController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class PatientConversationControllerTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String SCOPED = "patient:8:" + ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientConversationHistoryService historyService;

    @MockitoBean
    private AgentStateService stateService;

    @MockitoBean
    private PatientActionService actionService;

    @Test
    void listsOnlyAuthenticatedPatientsConversations() throws Exception {
        when(historyService.list(8L)).thenReturn(List.of(new ConversationSummary(
                ID, "查询下周号源", Instant.parse("2026-10-10T01:00:00Z"),
                Instant.parse("2026-10-10T02:00:00Z"), 4)));

        mockMvc.perform(get("/api/agent/patient/conversations")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conversationId").value(ID))
                .andExpect(jsonPath("$.data[0].messageCount").value(4));

        verify(historyService).list(8L);
    }

    @Test
    void createsAndPagesConversationHistory() throws Exception {
        when(historyService.create(8L)).thenReturn(new ConversationSummary(
                ID, "新对话", Instant.EPOCH, Instant.EPOCH, 0));
        when(historyService.messages(8L, ID, 42L, 20))
                .thenReturn(new ConversationMessagePage(List.of(), null, false));

        mockMvc.perform(post("/api/agent/patient/conversations")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(ID));

        mockMvc.perform(get("/api/agent/patient/conversations/{id}/messages", ID)
                        .param("before", "42")
                        .param("size", "20")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void deleteClearsPendingActionHistoryAndAgentState() throws Exception {
        when(historyService.requireOwned(8L, ID)).thenReturn(SCOPED);

        mockMvc.perform(delete("/api/agent/patient/conversations/{id}", ID)
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk());

        verify(actionService).discardConversation(8L, SCOPED);
        verify(historyService).delete(8L, ID);
        verify(stateService).clearConversation(SCOPED);
    }

    @Test
    void renamesOwnedConversation() throws Exception {
        when(historyService.rename(8L, ID, "复诊安排")).thenReturn(new ConversationSummary(
                ID, "复诊安排", Instant.EPOCH, Instant.EPOCH, 2));

        mockMvc.perform(patch("/api/agent/patient/conversations/{id}", ID)
                        .contentType("application/json")
                        .content("{\"title\":\"复诊安排\"}")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("复诊安排"));

        verify(historyService).rename(8L, ID, "复诊安排");
    }
}
