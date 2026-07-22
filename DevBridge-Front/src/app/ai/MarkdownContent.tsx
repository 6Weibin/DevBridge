/**
 * AI Markdown 渲染组件，解析常见 Markdown 语法并避免直接注入 HTML。
 *
 * by AI.Coding
 */
import React, { useLayoutEffect, useRef, useState } from "react";

const EAGER_TAIL_SEGMENTS = 16;
const SEGMENT_ROOT_MARGIN = "800px 0px";

type MarkdownBlock =
  | { type: "heading"; level: number; text: string }
  | { type: "horizontal-rule" }
  | { type: "paragraph"; text: string }
  | { type: "unordered-list"; items: string[] }
  | { type: "ordered-list"; items: string[] }
  | { type: "table"; headers: string[]; rows: string[][] }
  | { type: "code"; text: string; language: string }
  | { type: "quote"; text: string };

interface MarkdownContentProps {
  content: string;
  segments?: string[];
  streaming?: boolean;
}

/**
 * 渲染 AI 返回的分段 Markdown；稳定分段由 React.memo 复用，流式阶段只重解析尾段。
 */
export const MarkdownContent = React.memo(function MarkdownContent({ content, segments, streaming = false }: MarkdownContentProps) {
  const values = segments && segments.length > 0 ? segments : content ? [content] : [];
  if (values.length === 0) {
    return <span className="text-muted-foreground">正在生成...</span>;
  }
  return (
    <div className="space-y-2 break-words" data-markdown-segment-count={values.length}>
      {values.map((value, index) => (
        <LazyMarkdownSegment
          key={index}
          content={value}
          eager={index >= values.length - EAGER_TAIL_SEGMENTS}
          streaming={streaming && index === values.length - 1}
        />
      ))}
    </div>
  );
});

/**
 * 仅挂载视口附近或流式尾部的 Markdown，屏外内容保留等高占位，避免一次解析 1M Token。
 */
const LazyMarkdownSegment = React.memo(function LazyMarkdownSegment({
  content,
  eager,
  streaming,
}: {
  content: string;
  eager: boolean;
  streaming: boolean;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [visible, setVisible] = useState(eager);
  const [height, setHeight] = useState(() => estimateSegmentHeight(content));

  React.useEffect(() => {
    const element = containerRef.current;
    if (!element || eager || typeof IntersectionObserver === "undefined") {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      entries => setVisible(entries.some(entry => entry.isIntersecting)),
      { rootMargin: SEGMENT_ROOT_MARGIN },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [eager]);

  useLayoutEffect(() => {
    const element = containerRef.current;
    // 流式尾段始终可见，无需每帧强制测量布局；完成后再记录最终占位高度。
    if (streaming || !visible || !element) return;
    const nextHeight = Math.ceil(element.getBoundingClientRect().height);
    if (nextHeight > 0) setHeight(current => current === nextHeight ? current : nextHeight);
  }, [content, streaming, visible]);

  return (
    <div ref={containerRef} style={visible ? undefined : { height }} data-markdown-segment-shell="true">
      {visible ? <MarkdownSegment content={content} streaming={streaming}/> : null}
    </div>
  );
});

/**
 * 根据分段行数提供接近真实高度的占位，降低首次滚动到历史内容时的跳动。
 */
function estimateSegmentHeight(content: string) {
  const lineCount = Math.max(1, content.split("\n").length);
  return Math.max(72, Math.min(12_000, lineCount * 20));
}

/**
 * 解析并渲染单个已进入视口的稳定 Markdown 分段。
 */
const MarkdownSegment = React.memo(function MarkdownSegment({ content, streaming }: { content: string; streaming: boolean }) {
  const deferredContent = React.useDeferredValue(content);
  // 短尾段立即解析；长尾段降为低优先级，避免长任务持续占满 Renderer 主线程。
  const renderContent = streaming && content.length > 2_000 ? deferredContent : content;
  const blocks = React.useMemo(() => parseBlocks(renderContent), [renderContent]);
  return (
    <section
      className="space-y-2"
      data-streaming={streaming ? "true" : undefined}
      data-markdown-segment="true"
    >
      {blocks.map((block, index) => renderBlock(block, index))}
    </section>
  );
});

/**
 * 按行解析 Markdown 块级结构，覆盖 AI 回复中最常见的标题、列表、引用和代码块。
 */
function parseBlocks(content: string) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const blocks: MarkdownBlock[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }
    if (line.trimStart().startsWith("```")) {
      const parsed = parseCodeBlock(lines, index);
      blocks.push(parsed.block);
      index = parsed.nextIndex;
      continue;
    }
    if (startsTable(lines, index)) {
      const parsed = parseTable(lines, index);
      blocks.push(parsed.block);
      index = parsed.nextIndex;
      continue;
    }
    const special = parseSpecialBlock(lines, index);
    if (special) {
      blocks.push(special.block);
      index = special.nextIndex;
      continue;
    }
    const paragraph = parseParagraph(lines, index);
    blocks.push(paragraph.block);
    index = paragraph.nextIndex;
  }
  return blocks;
}

/**
 * 判断当前位置是否为 Markdown 表格；必须同时具备表头和分隔行，避免普通管道文本误判。
 */
function startsTable(lines: string[], index: number) {
  if (index + 1 >= lines.length) {
    return false;
  }
  return isTableRow(lines[index]) && isTableSeparator(lines[index + 1]);
}

/**
 * 解析 Markdown 表格，支持 AI 常见的 `| A | B |` 格式。
 */
function parseTable(lines: string[], startIndex: number) {
  const headers = splitTableRow(lines[startIndex]);
  const rows: string[][] = [];
  let index = startIndex + 2;
  while (index < lines.length && isTableRow(lines[index])) {
    const values = splitTableRow(lines[index]);
    rows.push(headers.map((_, cellIndex) => values[cellIndex] || ""));
    index += 1;
  }
  return {
    block: { type: "table", headers, rows } as MarkdownBlock,
    nextIndex: index,
  };
}

/**
 * 判断表格正文行，至少需要包含一个管道符。
 */
function isTableRow(line: string) {
  return line.trim().includes("|");
}

/**
 * 判断 Markdown 表格分隔行，例如 `| --- | :---: |`。
 */
function isTableSeparator(line: string) {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every(cell => /^:?-{3,}:?$/.test(cell.trim()));
}

/**
 * 拆分表格行并去掉首尾空单元格，保留单元格内文本供行内 Markdown 解析。
 */
function splitTableRow(line: string) {
  const trimmed = line.trim();
  const normalized = trimmed.startsWith("|") ? trimmed.slice(1) : trimmed;
  const withoutEnd = normalized.endsWith("|") ? normalized.slice(0, -1) : normalized;
  return withoutEnd.split("|").map(cell => cell.trim());
}

/**
 * 解析代码块，保留原始换行以便展示命令、日志和堆栈。
 */
function parseCodeBlock(lines: string[], startIndex: number) {
  const firstLine = lines[startIndex].trim();
  const language = firstLine.slice(3).trim();
  const codeLines: string[] = [];
  let index = startIndex + 1;
  while (index < lines.length && !lines[index].trimStart().startsWith("```")) {
    codeLines.push(lines[index]);
    index += 1;
  }
  return {
    block: { type: "code", text: codeLines.join("\n"), language } as MarkdownBlock,
    nextIndex: index < lines.length ? index + 1 : index,
  };
}

/**
 * 解析标题、列表和引用等有明确起始符的块。
 */
function parseSpecialBlock(lines: string[], startIndex: number) {
  const line = lines[startIndex];
  if (isHorizontalRule(line)) {
    return { block: { type: "horizontal-rule" } as MarkdownBlock, nextIndex: startIndex + 1 };
  }
  const heading = /^(#{1,4})\s+(.+)$/.exec(line.trim());
  if (heading) {
    return { block: { type: "heading", level: heading[1].length, text: heading[2] } as MarkdownBlock, nextIndex: startIndex + 1 };
  }
  if (/^\s*[-*]\s+/.test(line)) {
    return parseList(lines, startIndex, "unordered-list", /^\s*[-*]\s+/);
  }
  if (/^\s*\d+\.\s+/.test(line)) {
    return parseList(lines, startIndex, "ordered-list", /^\s*\d+\.\s+/);
  }
  if (/^\s*>\s?/.test(line)) {
    return parseQuote(lines, startIndex);
  }
  return null;
}

/** 识别 Markdown 水平分隔线，兼容短横线、星号、下划线及标记间空格。 */
function isHorizontalRule(line: string) {
  const value = line.trim();
  return /^(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$/.test(value);
}

/**
 * 解析连续列表项，避免每行列表被拆成多个块。
 */
function parseList(lines: string[], startIndex: number, type: "unordered-list" | "ordered-list", marker: RegExp) {
  const items: string[] = [];
  let index = startIndex;
  while (index < lines.length && marker.test(lines[index])) {
    items.push(lines[index].replace(marker, "").trim());
    index += 1;
  }
  return { block: { type, items } as MarkdownBlock, nextIndex: index };
}

/**
 * 解析引用块，常用于 AI 输出注意事项或结论。
 */
function parseQuote(lines: string[], startIndex: number) {
  const values: string[] = [];
  let index = startIndex;
  while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
    values.push(lines[index].replace(/^\s*>\s?/, ""));
    index += 1;
  }
  return { block: { type: "quote", text: values.join("\n") } as MarkdownBlock, nextIndex: index };
}

/**
 * 解析普通段落，直到遇到空行或新的 Markdown 特殊块。
 */
function parseParagraph(lines: string[], startIndex: number) {
  const values: string[] = [];
  let index = startIndex;
  while (index < lines.length && lines[index].trim() && !startsSpecialBlock(lines[index])) {
    values.push(lines[index]);
    index += 1;
  }
  return { block: { type: "paragraph", text: values.join("\n") } as MarkdownBlock, nextIndex: index };
}

/**
 * 判断当前行是否会开启新的块级 Markdown 结构。
 */
function startsSpecialBlock(line: string) {
  const value = line.trimStart();
  return isHorizontalRule(value) || value.startsWith("```") || /^#{1,4}\s+/.test(value) || /^[-*]\s+/.test(value) || /^\d+\.\s+/.test(value) || /^>\s?/.test(value);
}

/**
 * 渲染单个 Markdown 块。
 */
function renderBlock(block: MarkdownBlock, index: number) {
  if (block.type === "heading") {
    const Tag = `h${Math.min(block.level + 3, 6)}` as keyof JSX.IntrinsicElements;
    return <Tag key={index} className="font-semibold text-foreground">{renderInline(block.text)}</Tag>;
  }
  if (block.type === "horizontal-rule") {
    return <hr key={index} className="my-3 border-0 border-t border-border/80"/>;
  }
  if (block.type === "unordered-list") {
    return <ul key={index} className="list-disc space-y-1 pl-4">{block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}</ul>;
  }
  if (block.type === "ordered-list") {
    return <ol key={index} className="list-decimal space-y-1 pl-4">{block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}</ol>;
  }
  if (block.type === "table") {
    return renderTable(block, index);
  }
  if (block.type === "code") {
    return <pre key={index} className="overflow-x-auto rounded-md bg-muted p-2 font-mono text-[11px]"><code>{block.text}</code></pre>;
  }
  if (block.type === "quote") {
    return <blockquote key={index} className="border-l-2 border-primary/50 pl-2 text-muted-foreground">{renderInline(block.text)}</blockquote>;
  }
  return <p key={index} className="whitespace-pre-wrap">{renderInline(block.text)}</p>;
}

/**
 * 渲染 Markdown 表格，横向滚动避免大量列撑破 AI 侧边栏。
 */
function renderTable(block: Extract<MarkdownBlock, { type: "table" }>, index: number) {
  return (
    <div key={index} className="overflow-x-auto rounded-lg border border-border/70">
      <table className="min-w-full border-collapse text-left text-[11px]">
        <thead className="bg-muted text-muted-foreground">
          <tr>
            {block.headers.map((header, headerIndex) => (
              <th key={headerIndex} className="border-b border-border/70 px-2.5 py-2 font-semibold whitespace-nowrap">{renderInline(header)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {block.rows.map((row, rowIndex) => (
            <tr key={rowIndex} className="border-b border-border/40 last:border-b-0">
              {row.map((cell, cellIndex) => (
                <td key={cellIndex} className="max-w-72 px-2.5 py-2 align-top break-words">{renderInline(cell)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 渲染行内 Markdown，支持链接、粗体和行内代码。
 */
function renderInline(text: string) {
  const nodes: React.ReactNode[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g;
  let lastIndex = 0;
  for (const match of text.matchAll(pattern)) {
    const matchIndex = match.index ?? 0;
    if (matchIndex > lastIndex) {
      nodes.push(text.slice(lastIndex, matchIndex));
    }
    nodes.push(renderInlineToken(match[0], nodes.length));
    lastIndex = matchIndex + match[0].length;
  }
  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}

/**
 * 渲染单个行内 token；链接地址会做协议限制，避免 javascript URL 注入。
 */
function renderInlineToken(token: string, key: number) {
  if (token.startsWith("`")) {
    return <code key={key} className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">{token.slice(1, -1)}</code>;
  }
  if (token.startsWith("**")) {
    return <strong key={key}>{token.slice(2, -2)}</strong>;
  }
  const link = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
  if (link && isSafeHref(link[2])) {
    return <a key={key} href={link[2]} target="_blank" rel="noreferrer" className="text-primary underline underline-offset-2">{link[1]}</a>;
  }
  return token;
}

/**
 * 只允许安全链接协议，防止模型输出危险链接被直接点击执行。
 */
function isSafeHref(href: string) {
  return href.startsWith("https://") || href.startsWith("http://") || href.startsWith("mailto:");
}
