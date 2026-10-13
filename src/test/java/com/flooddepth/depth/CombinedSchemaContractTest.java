package com.flooddepth.depth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class CombinedSchemaContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void providerSchemaHasTypedNestedObjectsNonemptyUniqueRequiredAndNoUnsupportedCombinators() throws Exception {
        JsonNode schema=mapper.valueToTree(CombinedAnalysisPrompt.schema()); validate(schema);
        var properties=schema.path("properties");
        assertEquals(5,properties.size());
        for(String field:Set.of("sceneMatch","floodWaterVisible"))assertEquals("BOOLEAN",properties.path(field).path("type").asText());
        assertEquals("NUMBER",properties.path("approxDepthCm").path("type").asText());
        assertTrue(properties.path("approxDepthCm").path("nullable").asBoolean());
        assertEquals("STRING",properties.path("confidence").path("type").asText());
        assertEquals("STRING",properties.path("reasoning").path("type").asText());
        assertNoProviderBounds(schema);
    }
    void assertNoProviderBounds(JsonNode node) {
        if(node.isObject()) {
            for(String constraint:Set.of("minimum","maximum","maxItems")) assertFalse(node.has(constraint));
            node.elements().forEachRemaining(this::assertNoProviderBounds);
        } else if(node.isArray()) node.elements().forEachRemaining(this::assertNoProviderBounds);
    }
    void validate(JsonNode node) {
        assertTrue(node.isObject());
        var allowed=Set.of("type","properties","items","required","enum","minimum","maximum","maxItems","nullable");
        node.fieldNames().forEachRemaining(key->assertTrue(allowed.contains(key),key));
        String type=node.path("type").asText();assertTrue(Set.of("OBJECT","ARRAY","STRING","NUMBER","BOOLEAN").contains(type));
        if(type.equals("OBJECT")) {
            assertTrue(node.path("properties").isObject());assertTrue(node.path("required").isArray());assertFalse(node.path("required").isEmpty());
            var names=new HashSet<String>();
            for(var name:node.get("required")) {assertTrue(names.add(name.asText()));assertTrue(node.get("properties").has(name.asText()));}
            assertEquals(node.get("properties").size(),names.size());node.get("properties").elements().forEachRemaining(this::validate);
        } else if(type.equals("ARRAY")) validate(node.get("items"));
        if(node.has("enum")) {assertTrue(node.get("enum").isArray());assertFalse(node.get("enum").isEmpty());for(var e:node.get("enum"))assertTrue(e.isTextual());}
    }
}
