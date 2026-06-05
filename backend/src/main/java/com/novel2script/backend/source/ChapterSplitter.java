package com.novel2script.backend.source;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首版章节切分器：优先识别常见章节标题，识别不到时把全文当作第一章。
 */
@Component
public class ChapterSplitter {

    private static final Pattern CHAPTER_TITLE_PATTERN = Pattern.compile(
            "(?m)^\\s*((第\\s*[0-9一二三四五六七八九十百千万零〇两]+\\s*[章节卷集回].*)|(Chapter\\s+\\d+.*)|(CHAPTER\\s+\\d+.*)|(\\d+[.、]\\s*.+))\\s*$"
    );

    public List<ChapterSegment> split(String rawContent) {
        String cleanContent = clean(rawContent);
        if (cleanContent.isBlank()) {
            throw new IllegalArgumentException("小说正文不能为空");
        }

        Matcher matcher = CHAPTER_TITLE_PATTERN.matcher(cleanContent);
        List<TitleMatch> titles = new ArrayList<>();
        while (matcher.find()) {
            titles.add(new TitleMatch(matcher.start(), matcher.end(), matcher.group(1).trim()));
        }

        if (titles.isEmpty()) {
            return List.of(new ChapterSegment(1, "第1章", cleanContent, cleanContent));
        }

        List<ChapterSegment> chapters = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            TitleMatch current = titles.get(i);
            int contentEnd = (i + 1 < titles.size()) ? titles.get(i + 1).start() : cleanContent.length();
            String chapterRaw = cleanContent.substring(current.start(), contentEnd).trim();
            String chapterClean = clean(chapterRaw);
            chapters.add(new ChapterSegment(i + 1, current.title(), chapterRaw, chapterClean));
        }
        return chapters;
    }

    /**
     * 基础文本清洗：统一换行、去掉行尾空白、压缩过多空行。
     */
    public String clean(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        List<String> cleanedLines = new ArrayList<>();
        int blankCount = 0;
        for (String line : lines) {
            String cleanedLine = line.stripTrailing();
            if (cleanedLine.isBlank()) {
                blankCount++;
                if (blankCount <= 1) {
                    cleanedLines.add("");
                }
            } else {
                blankCount = 0;
                cleanedLines.add(cleanedLine);
            }
        }
        return String.join("\n", cleanedLines).trim();
    }

    private record TitleMatch(int start, int end, String title) {
    }

    public record ChapterSegment(int chapterNo, String title, String rawText, String cleanText) {
    }
}
