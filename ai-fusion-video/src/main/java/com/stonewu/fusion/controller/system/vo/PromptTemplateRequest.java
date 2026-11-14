package com.stonewu.fusion.controller.system.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PromptTemplateRequest(
        @NotBlank(message = "请输入模板名称")
        @Size(max = 80, message = "模板名称最多 80 字符")
        String name,
        @NotNull(message = "请选择适用资产")
        @Pattern(regexp = "character|scene|prop|general", message = "适用资产无效")
        String category,
        @NotBlank(message = "请输入 Markdown 模板正文")
        @Size(max = 5000, message = "模板正文最多 5000 字符")
        String markdown
) {
}
