package com.datapilot.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiAnalysisService analysisService;
    private final AiModelRegistry modelRegistry;

    public AiController(AiAnalysisService analysisService, AiModelRegistry modelRegistry) {
        this.analysisService = analysisService;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping("/models")
    public AiModelsResponse models() {
        return new AiModelsResponse(modelRegistry.models(), modelRegistry.defaultModelId());
    }

    @PostMapping("/generate")
    public AiAnalysisService.AiResponse generate(@Valid @RequestBody QuestionRequest request) {
        return analysisService.generate(request.question(), request.modelId());
    }

    @PostMapping("/query")
    public AiAnalysisService.AiResponse query(@Valid @RequestBody QuestionRequest request) {
        return analysisService.query(request.question(), request.modelId());
    }

    public record QuestionRequest(@NotBlank @Size(max = 500) String question, @Size(max = 160) String modelId) { }
    public record AiModelsResponse(java.util.List<AiModelRegistry.ModelOption> models, String defaultModelId) { }
}
