package com.stonewu.fusion.controller.system;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.controller.system.vo.PromptTemplateRequest;
import com.stonewu.fusion.service.system.PromptTemplateService;
import com.stonewu.fusion.service.system.PromptTemplateService.Template;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "提示词模板")
@RestController
@RequestMapping("/api/system/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService templates;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public CommonResult<List<Template>> list() {
        return CommonResult.success(templates.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CommonResult<Template> create(@Valid @RequestBody PromptTemplateRequest request) {
        return CommonResult.success(templates.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CommonResult<Template> update(@PathVariable String id, @Valid @RequestBody PromptTemplateRequest request) {
        return CommonResult.success(templates.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CommonResult<Boolean> delete(@PathVariable String id) {
        templates.delete(id);
        return CommonResult.success(true);
    }
}
