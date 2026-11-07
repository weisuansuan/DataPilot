package com.datapilot.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiAnalysisService analysisService;

    public AiController(AiAnalysisService analysisService) { this.analysisService = analysisService; }

    @PostMapping("/generate")
    public AiAnalysisService.AiResponse generate(@Valid @RequestBody QuestionRequest request) {
        return analysisService.generate(request.question());
    }

    @PostMapping("/query")
    public AiAnalysisService.AiResponse query(@Valid @RequestBody QuestionRequest request) {
        return analysisService.query(request.question());
    }

    public record QuestionRequest(@NotBlank @Size(max = 500) String question) { }
}
