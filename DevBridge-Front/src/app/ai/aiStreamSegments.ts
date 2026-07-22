/**
 * AI 长回复分段缓冲，限制可变尾段大小，避免流式阶段反复复制完整正文。
 *
 * by AI.Coding
 */

const TARGET_SEGMENT_CHARACTERS = 6_000;
const MAX_MUTABLE_TAIL_CHARACTERS = 12_000;

/**
 * 把新文本追加到稳定分段；只有最后一个分段会变化，已完成分段保持引用稳定。
 */
export function appendContentSegment(current: string[] | undefined, chunk: string) {
  if (!chunk) return current || [];
  const segments = current && current.length > 0 ? current.slice() : [""];
  segments[segments.length - 1] += chunk;
  splitMutableTail(segments);
  return segments;
}

/** 在 Markdown 空行边界切分尾段，超长单块按换行兜底切分。 */
function splitMutableTail(segments: string[]) {
  while (segments[segments.length - 1].length > TARGET_SEGMENT_CHARACTERS) {
    const tail = segments[segments.length - 1];
    const boundary = stableBoundary(tail);
    if (boundary < 1) return;
    segments[segments.length - 1] = tail.slice(0, boundary);
    segments.push(tail.slice(boundary));
  }
}

/** 优先使用完整块边界，只有异常超长 Markdown 块才使用普通换行。 */
function stableBoundary(value: string) {
  const blockBoundary = value.lastIndexOf("\n\n", TARGET_SEGMENT_CHARACTERS);
  if (blockBoundary >= TARGET_SEGMENT_CHARACTERS / 2) return blockBoundary + 2;
  if (value.length <= MAX_MUTABLE_TAIL_CHARACTERS) return -1;
  const lineBoundary = value.lastIndexOf("\n", TARGET_SEGMENT_CHARACTERS);
  return lineBoundary > 0 ? lineBoundary + 1 : TARGET_SEGMENT_CHARACTERS;
}

/** 仅在持久化或业务判断时一次性合并完整正文。 */
export function materializeContent(content: string, segments: string[] | undefined) {
  return segments && segments.length > 0 ? segments.join("") : content;
}
