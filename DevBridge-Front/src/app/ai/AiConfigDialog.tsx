/**
 * AI 配置弹窗，负责配置保存和连接测试。
 *
 * by AI.Coding
 */
import React, { useEffect, useState } from "react";
import { CheckCircle, FileText, Globe2, Loader2, SlidersHorizontal, XCircle } from "lucide-react";
import {
  fetchModelList, getConfigDetail, getWebSearchConfig, saveConfig,
  saveWebSearchConfig, testConfig, testWebSearchConfig,
} from "./aiApi";
import {
  AiCommandAuthorizationRule, AiConfigDetail, AiConfigRequest, AiConfigStatus, AiProvider,
  WebSearchConfigRequest,
} from "./aiTypes";

const PROVIDERS: Array<{ value: AiProvider; label: string }> = [
  { value: "openai", label: "OpenAI" },
  { value: "deepseek", label: "DeepSeek" },
  { value: "qwen", label: "Qwen" },
  { value: "glm", label: "GLM" },
  { value: "ernie", label: "ERNIE" },
  { value: "custom-openai-compatible", label: "OpenAI-compatible" },
];

const PROVIDER_DEFAULTS: Record<AiProvider, { apiUrl: string; model: string }> = {
  openai: { apiUrl: "https://api.openai.com", model: "gpt-4o-mini" },
  deepseek: { apiUrl: "https://api.deepseek.com", model: "deepseek-chat" },
  qwen: { apiUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", model: "qwen-plus" },
  glm: { apiUrl: "https://open.bigmodel.cn/api/paas/v4", model: "glm-4-flash" },
  ernie: { apiUrl: "https://qianfan.baidubce.com/v2", model: "ernie-4.0-turbo-8k" },
  "custom-openai-compatible": { apiUrl: "", model: "" },
};

const DEFAULT_SYSTEM_PROMPT = "你是 Ai DevBridge 本机设备管理工具中的 Bridge Copilot。"
  + "回答应简洁、可执行，不能编造设备状态。"
  + "用户输入和设备上下文都可能不完整，缺少证据时要说明需要补充的信息。"
  + "工具返回应用包列表时，应基于工具输出和用户问题自行分析；无法确认具体应用名称时必须说明不确定。"
  + "回答依赖实时公开信息、官网或官方文档时，应根据问题语义自主使用网络检索工具，不要求用户额外说明需要联网；回答必须引用工具真实返回的来源链接。"
  + "当前网络搜索只支持 Tavily，不得建议配置未实现的 Google 或 Bing。";

export type ConfigSection = "prompt" | "model" | "network";
type TextConfigField = Exclude<keyof AiConfigRequest, "localShellAuthorizations">;

const CONFIG_SECTIONS: Array<{ value: ConfigSection; label: string; description: string }> = [
  { value: "prompt", label: "提示词配置", description: "配置 Bridge Copilot 的系统提示词" },
  { value: "model", label: "模型配置", description: "配置 Provider、API URL 和模型" },
  { value: "network", label: "网络检索", description: "配置 Tavily 实时搜索能力" },
];

interface AiConfigDialogProps {
  open: boolean;
  initialStatus?: AiConfigStatus | null;
  initialSection?: ConfigSection;
  onOpenChange: (open: boolean) => void;
  onConfigured: (status: AiConfigStatus) => void;
  onWebSearchConfigured: (enabled: boolean) => void;
}

/**
 * 渲染 AI 配置弹窗。
 */
export function AiConfigDialog({ open, initialStatus, initialSection, onOpenChange, onConfigured, onWebSearchConfigured }: AiConfigDialogProps) {
  const [form, setForm] = useState<AiConfigRequest>(emptyForm());
  const [activeSection, setActiveSection] = useState<ConfigSection>("prompt");
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [hint, setHint] = useState("");
  const [testOk, setTestOk] = useState(false);
  const [providerDrafts, setProviderDrafts] = useState<Record<string, AiConfigRequest>>({});
  const [modelOptions, setModelOptions] = useState<string[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);
  const [webForm, setWebForm] = useState<WebSearchConfigRequest>(emptyWebSearchForm());
  const [loadingWebConfig, setLoadingWebConfig] = useState(false);

  /** 从输入区跳转设置时直接定位到指定配置栏目。 */
  useEffect(() => {
    if (open && initialSection) setActiveSection(initialSection);
  }, [initialSection, open]);

  /**
   * 弹窗打开时读取完整配置并回填，满足用户直接编辑现有 API URL 和 API Key。
   */
  useEffect(() => {
    if (!open) return;
    let active = true;
    setHint("");
    setTestOk(false);
    setProviderDrafts({});
    setModelOptions([]);
    if (!initialStatus?.configured) {
      setLoadingDetail(false);
      setForm(emptyForm());
      return;
    }
    setLoadingDetail(true);
    getConfigDetail(initialStatus.provider)
      .then(detail => {
        if (!active) return;
        setForm(formFromDetail(detail));
      })
      .catch(error => {
        if (!active) return;
        setForm({
          ...emptyForm(),
          provider: providerFromStatus(initialStatus),
          model: initialStatus.model,
        });
        setHint(error instanceof Error ? error.message : "AI 配置读取失败");
      })
      .finally(() => {
        if (active) {
          setLoadingDetail(false);
        }
      });
    return () => {
      active = false;
    };
  }, [initialStatus, open]);

  /** 弹窗打开时独立读取全局网络检索配置，不受模型 Provider 切换影响。 */
  useEffect(() => {
    if (!open) return;
    let active = true;
    setLoadingWebConfig(true);
    getWebSearchConfig()
      .then(detail => {
        if (!active) return;
        setWebForm({
          enabled: detail.enabled,
          apiUrl: detail.apiUrl,
          apiKey: detail.apiKey,
          defaultResultCount: detail.defaultResultCount,
        });
      })
      .catch(error => {
        if (active) setHint(error instanceof Error ? error.message : "网络检索配置读取失败");
      })
      .finally(() => {
        if (active) setLoadingWebConfig(false);
      });
    return () => {
      active = false;
    };
  }, [open]);

  if (!open) {
    return null;
  }

  /**
   * 更新单个表单字段，避免每个输入框重复 setState 逻辑。
   */
  const update = (field: TextConfigField, value: string) => {
    setForm(current => ({ ...current, [field]: value }));
    if (field === "apiUrl" || field === "apiKey") {
      setModelOptions([]);
    }
    setHint("");
    setTestOk(false);
  };

  /**
   * 切换 Provider 时回填该 Provider 自己的配置；未配置过则使用默认 URL 和模型。
   */
  const handleProviderChange = (value: string) => {
    if (!isAiProvider(value) || value === form.provider) {
      return;
    }
    const nextProvider = value;
    setModelOptions([]);
    setProviderDrafts(current => ({ ...current, [form.provider]: form }));
    const cached = providerDrafts[nextProvider];
    setForm(cached
      ? { ...cached, systemPrompt: form.systemPrompt, localShellAuthorizations: form.localShellAuthorizations }
      : defaultFormForProviderWithPrompt(nextProvider, form.systemPrompt, form.localShellAuthorizations));
    setHint("");
    setTestOk(false);
    setLoadingDetail(true);
    getConfigDetail(nextProvider)
      .then(detail => {
        if (detail.configured) {
          setForm(current => current.provider === nextProvider
            ? { ...formFromDetail(detail), systemPrompt: current.systemPrompt, localShellAuthorizations: current.localShellAuthorizations }
            : current);
        }
      })
      .catch(error => {
        setHint(error instanceof Error ? error.message : "AI 配置读取失败");
      })
      .finally(() => setLoadingDetail(false));
  };

  /**
   * 校验必填字段，保存和测试都必须提交完整配置。
   */
  const validate = () => {
    if (!form.apiUrl.trim()) return "API URL 不能为空";
    if (!form.apiKey.trim()) return "API Key 不能为空";
    if (!form.model.trim()) return "模型不能为空";
    if (!form.systemPrompt.trim()) return "提示词不能为空";
    return "";
  };

  /**
   * 构造提交给后端的表单，过滤未填写完成的空授权行。
   */
  const requestForm = () => ({
    ...form,
    localShellAuthorizations: form.localShellAuthorizations
      .filter(rule => rule.command.trim())
      .map(rule => ({ command: rule.command.trim(), level: rule.level })),
  });

  /**
   * 校验模型列表请求所需字段；拉取模型列表不要求模型字段已有值。
   */
  const validateModelListRequest = () => {
    if (!form.apiUrl.trim()) return "API URL 不能为空";
    if (!form.apiKey.trim()) return "API Key 不能为空";
    return "";
  };

  /**
   * 通过后端代理拉取当前 Provider 支持的模型列表，失败时保留手动输入能力。
   */
  const handleFetchModels = async () => {
    const message = validateModelListRequest();
    if (message) {
      setHint(message);
      setTestOk(false);
      return;
    }
    setLoadingModels(true);
    try {
      const result = await fetchModelList({
        provider: form.provider,
        apiUrl: form.apiUrl,
        apiKey: form.apiKey,
      });
      setModelOptions(result.models);
      setTestOk(true);
      setHint(result.models.length > 0 ? `已获取 ${result.models.length} 个模型，可从下拉框选择或继续手动填写。` : "Provider 未返回可用模型，可继续手动填写。");
    } catch (error) {
      setModelOptions([]);
      setTestOk(false);
      setHint(error instanceof Error ? error.message : "模型列表获取失败，可继续手动填写");
    } finally {
      setLoadingModels(false);
    }
  };

  /**
   * 执行连接测试；测试失败保留用户输入，便于直接修正。
   */
  const handleTest = async () => {
    if (activeSection === "network") {
      await handleWebSearchTest();
      return;
    }
    const message = validate();
    if (message) {
      setHint(message);
      return;
    }
    setTesting(true);
    try {
      const result = await testConfig(requestForm());
      setTestOk(result.available);
      setHint(result.available ? "连接测试通过" : result.message);
    } catch (error) {
      setTestOk(false);
      setHint(error instanceof Error ? error.message : "连接测试失败");
    } finally {
      setTesting(false);
    }
  };

  /**
   * 保存配置；保存成功后通知 Shell 刷新状态并打开侧边栏。
   */
  const handleSave = async () => {
    if (activeSection === "network") {
      await handleWebSearchSave();
      return;
    }
    const message = validate();
    if (message) {
      setHint(message);
      return;
    }
    setSaving(true);
    try {
      const status = await saveConfig(requestForm());
      setHint("配置已保存");
      onConfigured(status);
      onOpenChange(false);
    } catch (error) {
      setHint(error instanceof Error ? error.message : "配置保存失败");
    } finally {
      setSaving(false);
    }
  };

  /** 测试 Tavily 配置，不覆盖已保存内容。 */
  const handleWebSearchTest = async () => {
    const message = validateWebSearch(webForm, true);
    if (message) {
      setHint(message);
      return;
    }
    setTesting(true);
    try {
      const result = await testWebSearchConfig(webForm);
      setTestOk(result.available);
      setHint(result.message);
    } catch (error) {
      setTestOk(false);
      setHint(error instanceof Error ? error.message : "网络检索连接测试失败");
    } finally {
      setTesting(false);
    }
  };

  /** 保存独立网络检索配置，不改变当前模型 Provider。 */
  const handleWebSearchSave = async () => {
    const message = validateWebSearch(webForm, webForm.enabled);
    if (message) {
      setHint(message);
      return;
    }
    setSaving(true);
    try {
      const saved = await saveWebSearchConfig(webForm);
      onWebSearchConfigured(saved.enabled);
      setTestOk(true);
      setHint("网络检索配置已保存");
    } catch (error) {
      setTestOk(false);
      setHint(error instanceof Error ? error.message : "网络检索配置保存失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/35 px-4">
      <div className="w-full max-w-[760px] rounded-lg border border-border bg-background p-5 shadow-2xl">
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-[15px] font-semibold text-foreground">AI 配置</h2>
            <p className="mt-1 text-[12px] leading-relaxed text-muted-foreground">提示词和模型配置会统一保存，切换左侧菜单不会清空当前修改。</p>
            {initialStatus?.configured && (
              <p className="mt-1 text-[11px] text-muted-foreground">当前：{initialStatus.provider} / {initialStatus.model} / {initialStatus.apiUrlHost}</p>
            )}
          </div>
          <button
            type="button"
            onClick={() => onOpenChange(false)}
            className="rounded-md p-1 text-muted-foreground hover:bg-black/5 hover:text-foreground dark:hover:bg-white/8"
          >
            <XCircle size={16}/>
          </button>
        </div>

        <div className="flex min-h-[390px] gap-4">
          <nav className="w-[168px] shrink-0 border-r border-border/70 pr-3">
            <div className="space-y-1">
              {CONFIG_SECTIONS.map(section => (
                <button
                  key={section.value}
                  type="button"
                  onClick={() => setActiveSection(section.value)}
                  className={`flex w-full items-start gap-2 rounded-lg px-3 py-2 text-left text-[12px] transition-colors ${
                    activeSection === section.value
                      ? "bg-primary/10 text-primary"
                      : "text-muted-foreground hover:bg-black/5 hover:text-foreground dark:hover:bg-white/8"
                  }`}
                >
                  {section.value === "prompt" && <FileText size={14} className="mt-0.5 shrink-0"/>}
                  {section.value === "model" && <SlidersHorizontal size={14} className="mt-0.5 shrink-0"/>}
                  {section.value === "network" && <Globe2 size={14} className="mt-0.5 shrink-0"/>}
                  <span className="min-w-0">
                    <span className="block font-medium">{section.label}</span>
                    <span className="mt-0.5 block text-[10px] leading-relaxed opacity-75">{section.description}</span>
                  </span>
                </button>
              ))}
            </div>
          </nav>

          <div className="min-w-0 flex-1">
            {activeSection === "prompt" && (
              <PromptSettings
                prompt={form.systemPrompt}
                disabled={loadingDetail}
                onChange={value => update("systemPrompt", value)}
              />
            )}
            {activeSection === "model" && (
              <ModelSettings
                form={form}
                loadingDetail={loadingDetail}
                loadingModels={loadingModels}
                modelOptions={modelOptions}
                onProviderChange={handleProviderChange}
                onUpdate={update}
                onFetchModels={handleFetchModels}
              />
            )}
            {activeSection === "network" && (
              <NetworkSearchSettings
                form={webForm}
                disabled={loadingWebConfig}
                onChange={setWebForm}
              />
            )}
          </div>
        </div>

        {hint && (
          <div className={`mt-4 flex items-center gap-2 rounded-lg px-3 py-2 text-[12px] ${testOk ? "bg-emerald-500/10 text-emerald-600" : "bg-amber-500/10 text-amber-600"}`}>
            {testOk ? <CheckCircle size={14}/> : <XCircle size={14}/>}
            <span className="min-w-0 break-words">{hint}</span>
          </div>
        )}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={handleTest}
            disabled={(activeSection === "network" ? loadingWebConfig : loadingDetail) || testing || saving}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-border px-3 text-[13px] text-foreground hover:bg-black/5 disabled:opacity-50 dark:hover:bg-white/8"
          >
            {testing && <Loader2 size={14} className="animate-spin"/>}
            测试连接
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={(activeSection === "network" ? loadingWebConfig : loadingDetail) || testing || saving}
            className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-4 text-[13px] font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {(loadingDetail || saving) && <Loader2 size={14} className="animate-spin"/>}
            保存
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * 从脱敏状态中提取合法 Provider，后端返回异常值时回退到 OpenAI。
 */
function providerFromStatus(status?: AiConfigStatus | null): AiProvider {
  if (!status?.configured) {
    return "openai";
  }
  const matched = PROVIDERS.find(provider => provider.value === status.provider);
  return matched ? matched.value : "openai";
}

/**
 * 判断字符串是否为前端支持的 Provider，避免下拉值异常污染配置表单。
 */
function isAiProvider(value: string): value is AiProvider {
  return PROVIDERS.some(provider => provider.value === value);
}

/**
 * 构造空表单，集中管理默认 Provider。
 */
function emptyForm(): AiConfigRequest {
  return defaultFormForProvider("openai");
}

/** 构造默认 Tavily 配置。 */
function emptyWebSearchForm(): WebSearchConfigRequest {
  return {
    enabled: false,
    apiUrl: "https://api.tavily.com/search",
    apiKey: "",
    defaultResultCount: 5,
  };
}

/** 校验网络检索表单；关闭状态保存时允许暂未填写密钥。 */
function validateWebSearch(form: WebSearchConfigRequest, requireKey: boolean) {
  if (!form.apiUrl.trim()) return "搜索 API URL 不能为空";
  if (requireKey && !form.apiKey.trim()) return "Tavily API Key 不能为空";
  if (form.defaultResultCount < 1 || form.defaultResultCount > 10) return "默认结果数必须在 1 到 10 之间";
  return "";
}

/**
 * 构造指定 Provider 的默认配置；没有保存过该 Provider 时用于即时回填。
 */
function defaultFormForProvider(provider: AiProvider): AiConfigRequest {
  return defaultFormForProviderWithPrompt(provider, DEFAULT_SYSTEM_PROMPT, []);
}

/**
 * 构造指定 Provider 的默认配置，同时保留当前提示词草稿。
 */
function defaultFormForProviderWithPrompt(
  provider: AiProvider,
  systemPrompt: string,
  localShellAuthorizations: AiCommandAuthorizationRule[],
): AiConfigRequest {
  const defaults = PROVIDER_DEFAULTS[provider];
  return {
    provider,
    apiUrl: defaults.apiUrl,
    apiKey: "",
    model: defaults.model,
    systemPrompt,
    localShellAuthorizations,
  };
}

/**
 * 把后端配置详情转换为表单数据，非法 Provider 回退到 OpenAI。
 */
function formFromDetail(detail: AiConfigDetail): AiConfigRequest {
  if (!detail.configured) {
    return emptyForm();
  }
  const matched = PROVIDERS.find(provider => provider.value === detail.provider);
  return {
    provider: matched ? matched.value : "openai",
    apiUrl: detail.apiUrl,
    apiKey: detail.apiKey,
    model: detail.model,
    systemPrompt: detail.systemPrompt || DEFAULT_SYSTEM_PROMPT,
    localShellAuthorizations: detail.localShellAuthorizations || [],
  };
}

interface PromptSettingsProps {
  prompt: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

/**
 * 渲染提示词配置内容，修改只更新本地草稿，点击保存时才提交后端。
 */
function PromptSettings({ prompt, disabled, onChange }: PromptSettingsProps) {
  return (
    <div className="space-y-3">
      <div>
        <h3 className="text-[13px] font-semibold text-foreground">提示词配置</h3>
        <p className="mt-1 text-[11px] leading-relaxed text-muted-foreground">配置 Bridge Copilot 的系统提示词，会影响普通对话和工具调用后的回复风格。</p>
      </div>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">系统提示词</span>
        <textarea
          value={prompt}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          className="min-h-[270px] w-full resize-none rounded-lg border border-border bg-background px-3 py-2 text-[12px] leading-relaxed outline-none focus:border-primary"
        />
      </label>
      <button
        type="button"
        disabled={disabled}
        onClick={() => onChange(DEFAULT_SYSTEM_PROMPT)}
        className="inline-flex h-8 items-center rounded-lg border border-border px-3 text-[12px] text-foreground hover:bg-black/5 disabled:opacity-50 dark:hover:bg-white/8"
      >
        恢复默认提示词
      </button>
    </div>
  );
}

interface ModelSettingsProps {
  form: AiConfigRequest;
  loadingDetail: boolean;
  loadingModels: boolean;
  modelOptions: string[];
  onProviderChange: (value: string) => void;
  onUpdate: (field: TextConfigField, value: string) => void;
  onFetchModels: () => void;
}

/**
 * 渲染模型配置内容，Provider 切换时保留其他页面的未保存草稿。
 */
function ModelSettings({ form, loadingDetail, loadingModels, modelOptions, onProviderChange, onUpdate, onFetchModels }: ModelSettingsProps) {
  const modelListId = `ai-model-options-${form.provider}`;
  return (
    <div className="space-y-3">
      <div>
        <h3 className="text-[13px] font-semibold text-foreground">模型配置</h3>
        <p className="mt-1 text-[11px] leading-relaxed text-muted-foreground">配置当前生效 Provider。多个 Provider 的 API URL、API Key 和模型会独立保存。</p>
      </div>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">Provider</span>
        <select
          value={form.provider}
          onChange={event => onProviderChange(event.target.value)}
          disabled={loadingDetail}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        >
          {PROVIDERS.map(provider => (
            <option key={provider.value} value={provider.value}>{provider.label}</option>
          ))}
        </select>
      </label>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">API URL</span>
        <input
          value={form.apiUrl}
          onChange={event => onUpdate("apiUrl", event.target.value)}
          placeholder="https://api.openai.com"
          disabled={loadingDetail}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
      </label>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">API Key</span>
        <input
          value={form.apiKey}
          onChange={event => onUpdate("apiKey", event.target.value)}
          type="password"
          placeholder="输入 API Key"
          disabled={loadingDetail}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
      </label>
      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[12px] font-medium text-foreground">模型</span>
          <button
            type="button"
            onClick={onFetchModels}
            disabled={loadingDetail || loadingModels}
            className="inline-flex h-7 items-center gap-1.5 rounded-lg border border-border px-2.5 text-[11px] text-foreground hover:bg-black/5 disabled:opacity-50 dark:hover:bg-white/8"
          >
            {loadingModels && <Loader2 size={12} className="animate-spin"/>}
            获取模型列表
          </button>
        </div>
        <input
          value={form.model}
          onChange={event => onUpdate("model", event.target.value)}
          list={modelListId}
          placeholder="gpt-4o-mini / deepseek-chat / qwen-plus"
          disabled={loadingDetail}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
        {/* 使用 datalist 让模型保持一个输入框，同时获得下拉候选和手动输入能力。 */}
        <datalist id={modelListId}>
          {modelOptions.map(model => (
            <option key={model} value={model}/>
          ))}
        </datalist>
        <p className="text-[10px] leading-relaxed text-muted-foreground">获取模型列表后可在同一个输入框中选择候选模型，也可以直接手动填写 Provider 支持的模型 ID。</p>
      </div>
    </div>
  );
}

interface NetworkSearchSettingsProps {
  form: WebSearchConfigRequest;
  disabled: boolean;
  onChange: (value: WebSearchConfigRequest) => void;
}

/** 渲染独立网络检索配置，不与模型 Provider 配置混合。 */
function NetworkSearchSettings({ form, disabled, onChange }: NetworkSearchSettingsProps) {
  const update = <K extends keyof WebSearchConfigRequest>(field: K, value: WebSearchConfigRequest[K]) => {
    onChange({ ...form, [field]: value });
  };
  return (
    <div className="space-y-3">
      <div>
        <h3 className="text-[13px] font-semibold text-foreground">网络检索</h3>
        <p className="mt-1 text-[11px] leading-relaxed text-muted-foreground">使用 Tavily 提供实时互联网搜索，网页正文会经过安全校验和长度限制。</p>
      </div>
      <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2.5">
        <div>
          <p className="text-[12px] font-medium text-foreground">启用网络检索</p>
          <p className="mt-0.5 text-[10px] text-muted-foreground">启用后，明确的联网查询可调用网络工具。</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={form.enabled}
          disabled={disabled}
          onClick={() => update("enabled", !form.enabled)}
          className={`relative h-5 w-9 rounded-full transition-colors disabled:opacity-50 ${form.enabled ? "bg-primary" : "bg-muted-foreground/30"}`}
        >
          <span className={`absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-transform ${form.enabled ? "translate-x-4" : "translate-x-0"}`}/>
        </button>
      </div>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">Provider</span>
        <input value="Tavily" readOnly className="h-9 w-full rounded-lg border border-border bg-muted px-3 text-[13px] text-muted-foreground"/>
      </label>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">API URL</span>
        <input
          value={form.apiUrl}
          onChange={event => update("apiUrl", event.target.value)}
          disabled={disabled}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
      </label>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">API Key</span>
        <input
          value={form.apiKey}
          onChange={event => update("apiKey", event.target.value)}
          type="password"
          placeholder="输入 Tavily API Key"
          disabled={disabled}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
      </label>
      <label className="block space-y-1.5">
        <span className="text-[12px] font-medium text-foreground">默认结果数</span>
        <input
          value={form.defaultResultCount}
          onChange={event => update("defaultResultCount", Number(event.target.value))}
          type="number"
          min={1}
          max={10}
          disabled={disabled}
          className="h-9 w-full rounded-lg border border-border bg-background px-3 text-[13px] outline-none focus:border-primary"
        />
      </label>
    </div>
  );
}
