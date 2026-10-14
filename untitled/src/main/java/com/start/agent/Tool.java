package com.start.agent;

import java.util.Map;
import java.util.List;

public interface Tool {
    String getName();
    String getDescription();
    Map<String, Object> getParameters(); // JSON Schema

    /**
     * 返回符合百炼 API 要求的 function spec
     */
    default Map<String, Object> getFunctionSpec() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", getName(),
                        "description", getDescription(),
                        "parameters", getParameters()
                )
        );
    }

    String execute(Map<String, Object> args);

    /** Text-only tools keep their existing behavior; multimodal tools may attach request-local images. */
    default ExecutionResult executeForModel(Map<String, Object> args) {
        return new ExecutionResult(execute(args), List.of());
    }

    record ImageAttachment(String label, String dataUri) {}

    record ExecutionResult(String text, List<ImageAttachment> images) {
        public ExecutionResult {
            text = text == null ? "" : text;
            images = images == null ? List.of() : List.copyOf(images);
        }
    }
}
