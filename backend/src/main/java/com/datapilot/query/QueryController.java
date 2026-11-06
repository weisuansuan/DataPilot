package com.datapilot.query;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private final QueryService queryService;

    public QueryController(QueryService queryService) { this.queryService = queryService; }

    @PostMapping("/query/validate")
    public ValidationResponse validate(@Valid @RequestBody SqlRequest request) {
        SqlPolicy.Validation result = queryService.validate(request.sql());
        return new ValidationResponse(result.allowed(), result.errorCode(), result.message());
    }

    @PostMapping("/query/execute")
    public QueryService.QueryResponse execute(@Valid @RequestBody SqlRequest request) {
        return queryService.execute(request.sql());
    }

    @GetMapping("/metrics")
    public List<MetricDefinition> metrics() { return MetricCatalog.all(); }

    public record SqlRequest(@NotBlank @Size(max = 16384) String sql) { }
    public record ValidationResponse(boolean allowed, String errorCode, String message) { }
    public record MetricDefinition(String id, String name, String description, String formula,
            String filters, List<String> dimensions, List<String> fields) { }
}
