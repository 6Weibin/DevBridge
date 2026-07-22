/**
 * AI 长回复稳定性检查，直接复用生产分段实现验证 1M 字符完整性和分段上限。
 *
 * by AI.Coding
 */
import { appendContentSegment, materializeContent } from "../src/app/ai/aiStreamSegments.ts";

const chunk = "## 设备检查\n\n- 网络正常\n- 日志证据完整\n\n".repeat(40);
// 以保守 4 字符/Token 模拟 1M Token 历史正文，验证前端不会因全量重解析而失控。
const targetCharacters = 4_000_000;
let segments = [];
let expected = "";

while (expected.length < targetCharacters) {
  const remaining = targetCharacters - expected.length;
  const next = chunk.slice(0, Math.min(chunk.length, remaining));
  expected += next;
  segments = appendContentSegment(segments, next);
}

const actual = materializeContent("", segments);
const maximumSegment = Math.max(...segments.map(value => value.length));
if (actual !== expected) throw new Error("AI 长回复分段后正文不完整");
if (maximumSegment > 12_000) throw new Error(`AI 长回复尾段超过上限: ${maximumSegment}`);
if (segments.length < 50) throw new Error("AI 长回复未形成稳定分段");

process.stdout.write(JSON.stringify({
  characters: actual.length,
  estimatedTokens: Math.ceil(actual.length / 4),
  segments: segments.length,
  maximumSegment,
  heapUsedBytes: process.memoryUsage().heapUsed,
}) + "\n");
