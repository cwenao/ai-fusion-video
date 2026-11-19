package com.stonewu.fusion.service.generation;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.service.generation.StoryboardVideoPrompt.Picture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardVideoPromptTests {
    private static final List<Picture> PICTURES = List.of(new Picture("role", "旅人", "斗篷造型"),
            new Picture("prop", "石案", "带刻字"), new Picture("scene", "古庙", "雨夜内景"));

    @Test
    void preservesPositionalReferencesAndTextAlreadyUsingTheActualOrder() {
        String prompt = "图片1：今晚下雨。<Subject 2> 石案，由 <Picture 2> 定义。图片3提供的古庙。";
        assertThat(StoryboardVideoPrompt.align(prompt, PICTURES)).isEqualTo(prompt);
    }

    @Test
    void realignsNamedReferencesAndTheirLaterUsesWithAStyleImageInFront() {
        var pictures = List.of(new Picture("style", "画面风格", "画风"), PICTURES.get(0), PICTURES.get(1), PICTURES.get(2));
        String prompt = "参考图片1的画面风格。<Subject 1> 旅人，由 <Picture 2> 定义。"
                + "<Subject 2> 石案，由 <Picture 4> 定义。<Subject 3> 图片3提供的古庙。\n古庙布景沿用图3。";
        assertThat(StoryboardVideoPrompt.align(prompt, pictures)).isEqualTo(
                "参考图片1的画面风格。<Subject 1> 旅人，由 <Picture 2> 定义。"
                        + "<Subject 2> 石案，由 <Picture 3> 定义。<Subject 3> 图片4提供的古庙。\n古庙布景沿用图4。");
    }

    @Test
    void rejectsAnUnqualifiedReferenceWithMultipleDefinitionsOrNoActualSlot() {
        assertThatThrownBy(() -> StoryboardVideoPrompt.align("图片1中的旅人。图片1中的石案。沿用图片1。", PICTURES))
                .isInstanceOf(BusinessException.class).hasMessageContaining("多个资产定义");
        assertThatThrownBy(() -> StoryboardVideoPrompt.align("保留图片2。", List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("实际参考图中不存在");
    }
}
