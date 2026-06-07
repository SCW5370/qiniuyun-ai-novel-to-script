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

    private static final Pattern CHINESE_CHAPTER_NO_PATTERN = Pattern.compile(
            "^第\\s*([0-9一二三四五六七八九十百千万零〇两]+)\\s*[章节卷集回].*"
    );

    private static final Pattern ENGLISH_CHAPTER_NO_PATTERN = Pattern.compile(
            "^(?i:chapter)\\s+(\\d+).*"
    );

    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile(
            "^(\\d+)[.、]\\s*.+"
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
            Integer parsedChapterNo = parseChapterNo(current.title());
            chapters.add(new ChapterSegment(
                    parsedChapterNo == null ? i + 1 : parsedChapterNo,
                    current.title(),
                    chapterRaw,
                    chapterClean,
                    parsedChapterNo != null
            ));
        }
        return chapters;
    }

    public Integer parseChapterNo(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalizedTitle = title.trim();
        Matcher chineseMatcher = CHINESE_CHAPTER_NO_PATTERN.matcher(normalizedTitle);
        if (chineseMatcher.matches()) {
            return parseChapterNumberText(chineseMatcher.group(1));
        }
        Matcher englishMatcher = ENGLISH_CHAPTER_NO_PATTERN.matcher(normalizedTitle);
        if (englishMatcher.matches()) {
            return parsePositiveInt(englishMatcher.group(1));
        }
        Matcher leadingMatcher = LEADING_NUMBER_PATTERN.matcher(normalizedTitle);
        if (leadingMatcher.matches()) {
            return parsePositiveInt(leadingMatcher.group(1));
        }
        return null;
    }

    private Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseChapterNumberText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace("两", "二").replace("〇", "零").trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            return parsePositiveInt(normalized);
        }
        int result = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int digit = chineseDigit(normalized.charAt(i));
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(normalized.charAt(i));
            if (unit > 0) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
            }
        }
        result += section + number;
        return result > 0 ? result : null;
    }

    private int chineseDigit(char value) {
        return switch (value) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private int chineseUnit(char value) {
        return switch (value) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> 0;
        };
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

    public record ChapterSegment(int chapterNo, String title, String rawText, String cleanText, boolean explicitChapterNo) {

        public ChapterSegment(int chapterNo, String title, String rawText, String cleanText) {
            this(chapterNo, title, rawText, cleanText, false);
        }
    }
}
