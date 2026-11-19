package com.stonewu.fusion.service.generation;

import com.stonewu.fusion.common.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 按实际参考图槽位校准原提示词的图号，保留镜头叙述。 */
final class StoryboardVideoPrompt {
    private static final Pattern NUMBERED_IMAGE = Pattern.compile("(?:(?i:\\bPicture)\\h+|图片?\\h*)([0-9０-９]+)");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("(?i:<Subject\\h+\\d+>)|[\\r\\n，,。！？!?；;]");
    private static final Pattern IMAGE_AFTER_NAME = Pattern.compile("(?:由|见|参见)\\h*<?\\h*$");
    private static final Pattern NAME_AFTER_IMAGE = Pattern.compile(
            "^>?\\h*(?:提供的|所示的?|中的|里的|内的|的|对应的?|作为|用作|是|为|[:：])?\\h*");

    record Picture(String url, String assetName, String itemName) {
        boolean names(String phrase, boolean preceding) {
            return matches(phrase, assetName, preceding) || matches(phrase, itemName, preceding);
        }

        private static boolean matches(String phrase, String name, boolean preceding) {
            return name != null && !name.isBlank() && (preceding ? phrase.contains(name) : phrase.startsWith(name));
        }
    }

    private record Mention(int start, int end, int original, Integer namedSlot) {
    }

    private StoryboardVideoPrompt() {
    }

    static String align(String prompt, List<Picture> pictures) {
        if (prompt == null || !NUMBERED_IMAGE.matcher(prompt).find()) {
            return prompt;
        }
        var mentions = new ArrayList<Mention>();
        int offset = 0;
        String previous = "";
        var parts = CLAUSE_BOUNDARY.splitWithDelimiters(prompt, -1);
        for (int index = 0; index < parts.length; index += 2) {
            String clause = parts[index];
            collect(clause, previous, offset, pictures, mentions);
            if (index + 1 < parts.length) {
                String boundary = parts[index + 1];
                previous = boundary.equals("，") || boundary.equals(",") ? clause : "";
                offset += clause.length() + boundary.length();
            }
        }

        Map<Integer, Set<Integer>> namedSlots = new HashMap<>();
        for (var mention : mentions) {
            if (mention.namedSlot() != null) {
                namedSlots.computeIfAbsent(mention.original(), ignored -> new HashSet<>()).add(mention.namedSlot());
            }
        }
        var aligned = new StringBuilder(prompt.length());
        int cursor = 0;
        for (var mention : mentions) {
            int slot = slot(mention, namedSlots, pictures.size());
            aligned.append(prompt, cursor, mention.start());
            if (slot == mention.original()) {
                aligned.append(prompt, mention.start(), mention.end());
            } else {
                aligned.append(slot);
            }
            cursor = mention.end();
        }
        return aligned.append(prompt, cursor, prompt.length()).toString();
    }

    private static void collect(String clause, String previous, int offset, List<Picture> pictures,
                                List<Mention> mentions) {
        var tokens = NUMBERED_IMAGE.matcher(clause).results().toList();
        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            int beforeStart = index == 0 ? 0 : tokens.get(index - 1).end();
            int afterEnd = index + 1 == tokens.size() ? clause.length() : tokens.get(index + 1).start();
            String before = clause.substring(beforeStart, token.start());
            var backwards = IMAGE_AFTER_NAME.matcher(before);
            boolean preceding = backwards.find();
            String phrase;
            if (preceding) {
                String subject = before.substring(0, backwards.start()).strip();
                phrase = subject.isEmpty() && index == 0 ? previous : subject;
            } else {
                phrase = NAME_AFTER_IMAGE.matcher(clause.substring(token.end(), afterEnd)).replaceFirst("");
            }
            int number = parseNumber(token.group(1));
            mentions.add(new Mention(offset + token.start(1), offset + token.end(1), number,
                    identify(phrase, preceding, pictures, number)));
        }
    }

    private static Integer identify(String phrase, boolean preceding, List<Picture> pictures, int original) {
        Integer slot = null;
        for (int index = 0; index < pictures.size(); index++) {
            if (pictures.get(index).names(phrase, preceding)) {
                if (slot != null) {
                    throw invalid(original, "同时指向多个已绑定资产，请明确图号对应的素材");
                }
                slot = index + 1;
            }
        }
        return slot;
    }

    private static int slot(Mention mention, Map<Integer, Set<Integer>> namedSlots, int imageCount) {
        if (mention.namedSlot() != null) {
            return mention.namedSlot();
        }
        int original = mention.original();
        Set<Integer> known = namedSlots.getOrDefault(original, Set.of());
        if (known.size() > 1) {
            throw invalid(original, "有多个资产定义，请指出本处引用的素材");
        }
        if (!known.isEmpty()) {
            return known.iterator().next();
        }
        if (original < 1 || original > imageCount) {
            throw invalid(original, "在本次实际参考图中不存在");
        }
        return original;
    }

    private static int parseNumber(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException failure) {
            throw new BusinessException(400, "提示词中的参考图编号超出范围");
        }
    }

    private static BusinessException invalid(int number, String reason) {
        return new BusinessException(400, "提示词中的图片 " + number + " " + reason);
    }
}
