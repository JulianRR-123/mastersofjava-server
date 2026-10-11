/*
   Copyright 2020 First Eight BV (The Netherlands)


   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file / these files except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.moj.server.submit.json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nl.moj.server.submit.model.SourceMessage;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.node.ArrayNode;

public class SourceMessageDeserializer extends ValueDeserializer<SourceMessage> {

    public SourceMessageDeserializer() {
    }

    @Override
    public SourceMessage deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) {
        JsonNode node = deserializationContext.readTree(jsonParser);
        Map<String, String> sources = new HashMap<>();
        if (node.get("sources") != null && node.get("sources").isArray()) {
            ArrayNode sourceArray = (ArrayNode) node.get("sources");
            for (int i = 0; i < sourceArray.size(); i++) {
                JsonNode sourceElement = sourceArray.get(i);
                sources.put(sourceElement.get("uuid").textValue(), sourceElement.get("content").textValue());
            }
        }
        List<String> tests = new ArrayList<>();
        if (node.get("tests") != null && node.get("tests").isArray()) {
            ArrayNode jsonTests = (ArrayNode) node.get("tests");
            jsonTests.forEach(t -> tests.add(t.asText()));
        }
        String assignmentNameForAdminPurpose = null;
        if (node.get("assignmentName") != null && node.get("assignmentName").isTextual()) {
            assignmentNameForAdminPurpose = node.get("assignmentName").asText();
        }
        String uuid = null;
        if (node.get("uuid") != null && node.get("uuid").isTextual()) {
            uuid = node.get("uuid").asText();
        }
        String timeLeft = null;
        if (node.get("timeLeft") != null && node.get("timeLeft").isTextual()) {
            timeLeft = node.get("timeLeft").asText();
        }
        Long arrivalTime = Instant.now().toEpochMilli();
        return new SourceMessage(sources, tests, assignmentNameForAdminPurpose, uuid, timeLeft, arrivalTime);
    }
}
