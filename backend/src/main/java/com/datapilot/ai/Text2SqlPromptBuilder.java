package com.datapilot.ai;

import com.datapilot.query.MetricCatalog;
import com.datapilot.query.QueryController;
import com.datapilot.query.SqlPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class Text2SqlPromptBuilder {
    public static final String VERSION = "text2sql-v1";
    private final String template;
    private final SqlPolicy policy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxPromptChars;

    public Text2SqlPromptBuilder(ResourceLoader resourceLoader, SqlPolicy policy,
            @Value("${datapilot.ai.max-prompt-chars:12000}") int maxPromptChars) throws IOException {
        Resource resource = resourceLoader.getResource("classpath:prompts/text2sql-v1.txt");
        this.template = resource.getContentAsString(StandardCharsets.UTF_8);
        this.policy = policy;
        this.maxPromptChars = Math.max(4000, Math.min(maxPromptChars, 24000));
    }

    public String systemPrompt() {
        try {
            Map<String, Set<String>> schema = new TreeMap<>();
            policy.allowedColumns().forEach((table, columns) -> schema.put(table, new TreeSet<>(columns)));
            String metrics = mapper.writeValueAsString(MetricCatalog.all());
            String prompt = template.replace("{{schema}}", mapper.writeValueAsString(schema))
                    .replace("{{metrics}}", metrics);
            if (prompt.length() > maxPromptChars) throw new AiServiceException("AI_PROMPT_TOO_LARGE");
            return prompt;
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiServiceException("AI_PROMPT_UNAVAILABLE");
        }
    }
}
