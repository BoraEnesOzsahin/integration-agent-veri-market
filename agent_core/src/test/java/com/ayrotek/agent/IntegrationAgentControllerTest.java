package com.ayrotek.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.institution-id=Kurum_Test",
        "agent.institution-name=Test Kurumu",
        "agent.coordinator.public-key=302a300506032b657003210063a2c47a626dae0de093c9880d2bb217a54b44bd617ff402f43f6bf2243025ad"
})
class IntegrationAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusIdentifiesInstitution() throws Exception {
        mockMvc.perform(get("/api/v1/agent/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionId").value("Kurum_Test"))
                .andExpect(jsonPath("$.mpcEngine").value("MP-SPDZ"));
    }

    @Test
    void unsignedQueriesAreRejected() throws Exception {
        String query = """
                {"jobId":"j1","consumerPublicKey":"k","datasetName":"t","targetMetric":"m","operation":"SUM","outputMode":"VALUE"}
                """;
        mockMvc.perform(post("/api/v1/agent/pipeline/process-request").contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isForbidden());
        String prepare = """
                {"protocol":"semi2k","init":{"jobId":"j2","partyId":1,"threshold":2,"totalParties":2,"datasetName":"t","targetMetric":"m"}}
                """;
        mockMvc.perform(post("/api/v1/agent/mpc/engine/prepare").contentType(MediaType.APPLICATION_JSON).content(prepare))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedEndpointsAreGone() throws Exception {
        for (String path : new String[]{"/mpc/compute-and-encrypt-share", "/mpc/init-and-split", "/mpc/receive-share",
                "/mpc/receive-encrypted-share", "/mpc/aggregate-and-encrypt", "/zkp/generate-proof", "/chamber/mint-custom"}) {
            mockMvc.perform(post("/api/v1/agent" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v1/agent/db/overview")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/agent/mpc/peer-public-key")).andExpect(status().isNotFound());
    }
}
