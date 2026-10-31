package com.ayrotek.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {"agent.mpc.allow-plain-share-receive=true", "agent.crypto.allow-ephemeral-key=true"})
class IntegrationAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetAgentStatus() throws Exception {
        mockMvc.perform(get("/api/v1/agent/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionId").value("Banka_A"))
                .andExpect(jsonPath("$.status").value("ACTIVE_ONLINE"));
    }

    @Test
    void testGenerateZkpProof() throws Exception {
        String reqJson = """
                {
                  "presentationRequestId": "req-test-1",
                  "requestedPredicates": [
                    { "name": "income", "pType": ">=", "pValue": 30000 },
                    { "name": "credit_score", "pType": ">=", "pValue": 1200 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/agent/zkp/generate-proof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionId").value("Banka_A"))
                .andExpect(jsonPath("$.rawAttributesExposed").value(false))
                .andExpect(jsonPath("$.status").value("PROOF_GENERATED_ON_PREMISE"))
                .andExpect(jsonPath("$.predicatesProved['income >= 30000']").value(true));
    }

    @Test
    void testComputeAndEncryptShare() throws Exception {
        String reqJson = """
                {
                  "jobId": "job-test-1",
                  "partyId": 1,
                  "threshold": 2,
                  "totalParties": 3
                }
                """;

        mockMvc.perform(post("/api/v1/agent/mpc/compute-and-encrypt-share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionId").value("Banka_A"))
                .andExpect(jsonPath("$.partyId").value(1))
                .andExpect(jsonPath("$.consumerEncryptedShare.partyId").value(1))
                .andExpect(jsonPath("$.status").value("SHARE_COMPUTED_AND_ENCRYPTED_ON_PREMISE"));
    }

    @Test
    void testReceiveShamirShare() throws Exception {
        String reqJson = """
                {
                  "jobId": "job-mpc-sum-101",
                  "sourceInstitutionId": "Banka_B",
                  "senderPartyId": 2,
                  "targetPartyId": 1,
                  "shareValue": 123456789,
                  "protocol": "SHAMIR_SECRET_SHARING"
                }
                """;

        mockMvc.perform(post("/api/v1/agent/mpc/receive-share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-mpc-sum-101"))
                .andExpect(jsonPath("$.targetInstitutionId").value("Banka_A"))
                .andExpect(jsonPath("$.sourceInstitutionId").value("Banka_B"))
                .andExpect(jsonPath("$.registeredSenderPartyId").value(2))
                .andExpect(jsonPath("$.status").value("SHARE_RECEIVED_AND_REGISTERED"));

        // Şimdi toplanan payları kontrol et
        mockMvc.perform(get("/api/v1/agent/mpc/received-shares/job-mpc-sum-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderPartyId").value(2));
    }

    @Test
    void testExecutePipeline() throws Exception {
        String reqJson = """
                {
                  "jobId": "job-pipeline-202",
                  "partyId": 1,
                  "threshold": 2,
                  "totalParties": 3,
                  "requestedPredicates": [
                    { "name": "income", "pType": ">=", "pValue": 30000 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/agent/pipeline/process-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionId").value("Banka_A"))
                .andExpect(jsonPath("$.zkpProof.rawAttributesExposed").value(false))
                .andExpect(jsonPath("$.consumerEncryptedShare.partyId").value(1))
                .andExpect(jsonPath("$.status").value("ON_PREMISE_PIPELINE_EXECUTED_SUCCESSFULLY"));
    }
}
