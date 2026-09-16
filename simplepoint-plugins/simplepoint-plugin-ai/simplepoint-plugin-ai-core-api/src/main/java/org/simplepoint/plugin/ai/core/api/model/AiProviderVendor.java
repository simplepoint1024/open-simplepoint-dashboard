package org.simplepoint.plugin.ai.core.api.model;

/**
 * Well-known AI vendors and self-hosted runtimes.
 *
 * <p>The selected vendor controls catalog authentication, response normalization, and the
 * internal inference protocol. Custom providers use the OpenAI-compatible protocol.</p>
 */
public enum AiProviderVendor {
  OPENAI,
  ANTHROPIC,
  GOOGLE_GEMINI,
  AZURE_OPENAI,
  MISTRAL,
  GROQ,
  DEEPSEEK,
  XAI,
  OPENROUTER,
  TOGETHER_AI,
  FIREWORKS_AI,
  ALIBABA_QWEN,
  MOONSHOT,
  MINIMAX,
  STEPFUN,
  ZHIPU_AI,
  BAIDU_QIANFAN,
  TENCENT_HUNYUAN,
  VOLCENGINE_DOUBAO,
  SILICONFLOW,
  NVIDIA_NIM,
  HUGGING_FACE,
  COHERE,
  OLLAMA,
  LM_STUDIO,
  VLLM,
  CUSTOM
}
