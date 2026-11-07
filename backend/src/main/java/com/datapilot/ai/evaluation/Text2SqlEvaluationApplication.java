package com.datapilot.ai.evaluation;

import com.datapilot.DataPilotApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class Text2SqlEvaluationApplication {
    private Text2SqlEvaluationApplication() { }
    public static void main(String[] args) {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(DataPilotApplication.class)
                .web(WebApplicationType.NONE).run(args)) { }
    }
}
