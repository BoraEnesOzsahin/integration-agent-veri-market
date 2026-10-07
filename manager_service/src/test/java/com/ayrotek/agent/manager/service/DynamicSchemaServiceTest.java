package com.ayrotek.agent.manager.service;

import com.ayrotek.agent.manager.model.DynamicPredicateDto;
import com.ayrotek.agent.manager.model.PredicateRequest;
import com.ayrotek.agent.manager.model.PredicateSchemaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicSchemaServiceTest {

    private DynamicSchemaService service;

    @BeforeEach
    void setUp() {
        service = new DynamicSchemaService();
        ReflectionTestUtils.setField(service, "nodeId", "Banka_A");
        ReflectionTestUtils.setField(service, "nodeName", "Banka A Kurum İçi Yönetici");
        ReflectionTestUtils.setField(service, "anoncredsUrl", "http://localhost:3000");
    }

    @Test
    void testResolveDynamicSchema_WithFullPredicateRequest() {
        PredicateRequest request = new PredicateRequest(
                "job-test-123",
                "bankalar",
                "bakiye",
                List.of(
                        new DynamicPredicateDto("income", ">=", 30000),
                        new DynamicPredicateDto("credit_score", ">", 1200)
                ),
                List.of("dataset_id", "institution_id"),
                Map.of("region", "TR_MARMARA"),
                "SELECT SUM(bakiye) FROM bankalar WHERE income >= 30000 AND credit_score > 1200",
                "mpc-coordinator"
        );

        PredicateSchemaResponse response = service.resolveDynamicSchema(request);

        assertNotNull(response);
        assertEquals("SUCCESS", response.status());
        assertEquals("Banka_A", response.nodeId());
        assertEquals("job-test-123", response.jobId());
        assertEquals("bankalar", response.datasetName());
        assertEquals("bakiye", response.targetMetric());
        assertEquals("bankalar_bakiye_dynamic_zkp_schema", response.schemaName());

        // ZKP Schema Assertions
        assertNotNull(response.zkpSchema());
        assertEquals("bankalar_bakiye_dynamic_zkp_schema", response.zkpSchema().schemaName());
        assertEquals("did:ayrotek:banka_a", response.zkpSchema().issuerDid());
        assertTrue(response.zkpSchema().attributeNames().contains("dataset_id"));
        assertTrue(response.zkpSchema().attributeNames().contains("institution_id"));
        assertTrue(response.zkpSchema().attributeNames().contains("bakiye"));
        assertTrue(response.zkpSchema().attributeNames().contains("income"));
        assertTrue(response.zkpSchema().attributeNames().contains("credit_score"));
        assertTrue(response.zkpSchema().attributeNames().contains("region"));
        assertEquals(2, response.zkpSchema().predicates().size());

        // VC Schema Assertions
        assertNotNull(response.vcSchema());
        assertEquals("https://schema.ayrotek.com/vc/bankalar_bakiye_dynamic_zkp_schema.json", response.vcSchema().schemaId());
        assertEquals("JsonSchemaValidator2018", response.vcSchema().schemaType());
        assertTrue(response.vcSchema().context().contains("https://www.w3.org/2018/credentials/v1"));
        assertTrue(response.vcSchema().context().contains("https://schema.ayrotek.com/vc/bankalar"));
        assertTrue(response.vcSchema().credentialTypes().contains("VerifiableCredential"));
        assertTrue(response.vcSchema().credentialTypes().contains("DynamicMpcAggregatedProofCredential"));
        assertTrue(response.vcSchema().predicateRulesSummary().contains("income >= 30000"));
        assertTrue(response.vcSchema().predicateRulesSummary().contains("credit_score > 1200"));

        Map<String, Object> jsonSchema = response.vcSchema().credentialSubjectJsonSchema();
        assertNotNull(jsonSchema);
        assertEquals("https://json-schema.org/draft/2020-12/schema", jsonSchema.get("$schema"));
    }

    @Test
    void testResolveDynamicSchema_WithNullOrEmptyRequest_ShouldFallbackGracefully() {
        PredicateSchemaResponse response = service.resolveDynamicSchema(null);

        assertNotNull(response);
        assertEquals("SUCCESS", response.status());
        assertEquals("consortium", response.datasetName());
        assertEquals("balance", response.targetMetric());
        assertEquals("consortium_balance_dynamic_zkp_schema", response.schemaName());
        assertNotNull(response.zkpSchema());
        assertNotNull(response.vcSchema());
    }
}
