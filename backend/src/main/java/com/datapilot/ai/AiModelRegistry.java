package com.datapilot.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiModelRegistry {
    private static final String QINIU_ID = "qiniu";
    private static final String LOCAL_PREFIX = "freellmapi:";
    private static final String MODELSCOPE_ID = "modelscope:kimi-k3.1-pro";
    private static final long CATALOG_TTL_MILLIS = 30_000;

    private final OpenAiCompatibleChatClient qiniuClient;
    private final OpenAiCompatibleChatClient localClient;
    private final OpenAiCompatibleChatClient modelScopeClient;
    private final String defaultProvider;
    private final String qiniuModel;
    private final String localModel;
    private final String qiniuApiKey;
    private final String localApiKey;
    private final String modelScopeApiKey;
    private final String modelScopeModel;
    private volatile List<String> localModels = List.of();
    private volatile long localCatalogLoadedAt;

    @Autowired
    public AiModelRegistry(OpenAiCompatibleChatClient qiniuClient,
            @Value("${datapilot.ai.provider:qiniu}") String defaultProvider,
            @Value("${datapilot.ai.model:qiniu/conatus-free}") String qiniuModel,
            @Value("${datapilot.ai.api-key:}") String qiniuApiKey,
            @Value("${datapilot.ai.timeout-seconds:45}") int timeoutSeconds,
            @Value("${datapilot.ai.max-output-tokens:1200}") int maxOutputTokens,
            @Value("${datapilot.ai.max-retry-delay-seconds:5}") int maxRetryDelaySeconds,
            @Value("${datapilot.ai.local.base-url:http://127.0.0.1:31415/v1}") String localBaseUrl,
            @Value("${datapilot.ai.local.model:auto}") String localModel,
            @Value("${datapilot.ai.local.api-key:}") String localApiKey,
            @Value("${datapilot.ai.local.stream:false}") boolean localStream,
            @Value("${datapilot.ai.modelscope.base-url:https://api-inference.modelscope.cn/v1}") String modelScopeBaseUrl,
            @Value("${datapilot.ai.modelscope.model:moonshotai/Kimi-K3.1-Pro}") String modelScopeModel,
            @Value("${datapilot.ai.modelscope.api-key:}") String modelScopeApiKey,
            @Value("${datapilot.ai.modelscope.stream:true}") boolean modelScopeStream) {
        this.qiniuClient = qiniuClient;
        this.defaultProvider = defaultProvider;
        this.qiniuModel = qiniuModel;
        this.qiniuApiKey = qiniuApiKey;
        this.localModel = localModel;
        this.localApiKey = localApiKey;
        this.modelScopeApiKey = modelScopeApiKey;
        this.modelScopeModel = modelScopeModel;
        this.localClient = new OpenAiCompatibleChatClient(localBaseUrl, localModel, localApiKey,
                localStream, timeoutSeconds, maxOutputTokens, 0, maxRetryDelaySeconds);
        this.modelScopeClient = new OpenAiCompatibleChatClient(modelScopeBaseUrl, modelScopeModel, modelScopeApiKey,
                modelScopeStream, timeoutSeconds, maxOutputTokens, 1, maxRetryDelaySeconds);
        this.fixedSelection = null;
    }

    private AiModelRegistry(String provider, String model, ChatModelClient client) {
        this.qiniuClient = null;
        this.localClient = null;
        this.modelScopeClient = null;
        this.defaultProvider = provider;
        this.qiniuModel = model;
        this.localModel = model;
        this.qiniuApiKey = "configured";
        this.localApiKey = "";
        this.modelScopeApiKey = "";
        this.modelScopeModel = model;
        this.fixedSelection = new Selection(provider, provider, model, client);
    }

    private final Selection fixedSelection;

    public static AiModelRegistry fixed(String provider, ChatModelClient client) {
        return new AiModelRegistry(provider, client.model(), client);
    }

    public synchronized List<ModelOption> models() {
        if (fixedSelection != null) {
            return List.of(new ModelOption(fixedSelection.id(), fixedSelection.provider(), fixedSelection.model(), true, true, true));
        }
        List<ModelOption> result = new ArrayList<>();
        boolean qiniuConfigured = !qiniuApiKey.isBlank();
        result.add(new ModelOption(QINIU_ID, "Qiniu AI", qiniuModel, qiniuConfigured, qiniuConfigured,
                defaultProvider.equalsIgnoreCase("qiniu") && qiniuConfigured));

        List<String> discovered = discoverLocalModels();
        if (!localApiKey.isBlank() && discovered.isEmpty()) {
            result.add(new ModelOption(LOCAL_PREFIX + localModel, "FreeLLMAPI 本地", localModel, true, false, false));
        } else {
            for (String model : discovered) {
                result.add(new ModelOption(LOCAL_PREFIX + model, "FreeLLMAPI 本地", model, true, true,
                        defaultProvider.equalsIgnoreCase("freellmapi") && model.equals(localModel)));
            }
        }
        boolean modelScopeConfigured = !modelScopeApiKey.isBlank();
        result.add(new ModelOption(MODELSCOPE_ID, "ModelScope", modelScopeModel,
                modelScopeConfigured, modelScopeConfigured, false));
        String preferred = defaultModelId(result);
        return result.stream().map(option -> new ModelOption(option.id(), option.provider(), option.model(),
                option.configured(), option.available(), option.id().equals(preferred))).toList();
    }

    public synchronized String defaultModelId() {
        if (fixedSelection != null) return fixedSelection.id();
        return defaultModelId(modelsWithoutDefault());
    }

    public synchronized Selection resolve(String id) {
        if (fixedSelection != null) return id == null || id.isBlank() || fixedSelection.id().equals(id) ? fixedSelection : null;
        if (id == null || id.isBlank()) id = defaultModelId();
        if (QINIU_ID.equals(id)) {
            return qiniuApiKey.isBlank() ? null : new Selection(QINIU_ID, "Qiniu AI", qiniuModel, qiniuClient);
        }
        if (MODELSCOPE_ID.equals(id)) {
            return modelScopeApiKey.isBlank() ? null : new Selection(id, "ModelScope", modelScopeModel, modelScopeClient);
        }
        if (id.startsWith(LOCAL_PREFIX) && !localApiKey.isBlank()) {
            String model = id.substring(LOCAL_PREFIX.length());
            if (discoverLocalModels().contains(model)) return new Selection(id, "FreeLLMAPI 本地", model, localClient);
        }
        return null;
    }

    private List<ModelOption> modelsWithoutDefault() {
        if (fixedSelection != null) return List.of();
        List<ModelOption> result = new ArrayList<>();
        boolean qiniuConfigured = !qiniuApiKey.isBlank();
        result.add(new ModelOption(QINIU_ID, "Qiniu AI", qiniuModel, qiniuConfigured, qiniuConfigured, false));
        for (String model : discoverLocalModels()) {
            result.add(new ModelOption(LOCAL_PREFIX + model, "FreeLLMAPI 本地", model, true, true, false));
        }
        boolean modelScopeConfigured = !modelScopeApiKey.isBlank();
        result.add(new ModelOption(MODELSCOPE_ID, "ModelScope", modelScopeModel,
                modelScopeConfigured, modelScopeConfigured, false));
        return result;
    }

    private String defaultModelId(List<ModelOption> options) {
        if (defaultProvider.equalsIgnoreCase("freellmapi")) {
            return options.stream().filter(option -> option.available() && option.id().equals(LOCAL_PREFIX + localModel))
                    .map(ModelOption::id).findFirst()
                    .orElseGet(() -> options.stream().filter(option -> option.available() && option.id().startsWith(LOCAL_PREFIX))
                            .map(ModelOption::id).findFirst().orElseGet(() -> availableOrFirst(options)));
        }
        if (defaultProvider.equalsIgnoreCase("modelscope")) {
            return options.stream().filter(option -> option.available() && MODELSCOPE_ID.equals(option.id()))
                    .map(ModelOption::id).findFirst().orElseGet(() -> availableOrFirst(options));
        }
        return options.stream().filter(option -> option.available() && QINIU_ID.equals(option.id()))
                .map(ModelOption::id).findFirst().orElseGet(() -> availableOrFirst(options));
    }

    private static String availableOrFirst(List<ModelOption> options) {
        return options.stream().filter(ModelOption::available).map(ModelOption::id).findFirst()
                .orElseGet(() -> options.isEmpty() ? QINIU_ID : options.getFirst().id());
    }

    private List<String> discoverLocalModels() {
        if (localApiKey.isBlank()) return List.of();
        long now = System.currentTimeMillis();
        if (now - localCatalogLoadedAt < CATALOG_TTL_MILLIS) return localModels;
        List<String> discovered = localClient.availableModels();
        localModels = discovered;
        localCatalogLoadedAt = now;
        return localModels;
    }

    public record Selection(String id, String provider, String model, ChatModelClient client) { }
    public record ModelOption(String id, String provider, String model, boolean configured,
            boolean available, boolean defaultModel) { }
}
