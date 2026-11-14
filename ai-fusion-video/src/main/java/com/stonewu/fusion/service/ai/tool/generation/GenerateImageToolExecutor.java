package com.stonewu.fusion.service.ai.tool.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.agent.node.CanvasAgentNodeGeneration;
import com.stonewu.fusion.service.canvas.model.CanvasValueType;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.ProjectMediaCompletion;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.ProjectImageOptions;
import com.stonewu.fusion.service.generation.job.GenerationSubmissionService;
import com.stonewu.fusion.service.generation.review.GenerationReviewAdmission;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.fusion.service.resource.AssetFields;
import com.stonewu.fusion.service.resource.ResourceToolRequests;
import io.agentscope.core.tool.ToolSuspendException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提交可恢复生成任务；返回真实任务引用，不阻塞工具线程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerateImageToolExecutor implements ToolExecutor {

    /**
     * 模型类型常量：图片生成
     */
    private static final int MODEL_TYPE_IMAGE = 2;


    private final AiModelService aiModelService;
    private final GenerationSubmissionService submissions;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final CanvasJson json;
    private final CanvasAgentNodeGeneration canvasModels;
    private final GenerationReviewAdmission reviews;
    private final ProjectMediaCompletion projectMedia;
    private final ProjectImageOptions projectImageOptions;

    @Override
    public String getToolName() {
        return "generate_image";
    }

    @Override
    public String getDisplayName() {
        return "AI 生成图片";
    }

    @Override
    public String getToolDescription() {
        return """
                生成AI图片。项目首尾帧和子资产流水线会等待产物并自动回填。

                适用场景：
                1. 为角色生成立绘：根据角色的外貌、性格描述生成设定图
                2. 为分镜生成画面：根据分镜的场景、内容描述生成画面图
                3. 生成场景图、道具图等创意素材

                重要提示：
                - 其他生图任务如需保存到资产中，请使用 update_asset_image 工具
                - 提示词要详细具体，包含画面的主体、风格、视角等信息
                - 可以使用中文提示词，系统会自动处理
                - 如果你打算传 imageUrls，或不确定当前默认模型是否支持参考图，请先调用 get_generation_model_capabilities
                
                项目资产保存的 properties.imagePrompt 由系统直接用作生图正文。
                项目首尾帧、项目资产和画布专业节点使用用户选择的模型。项目首尾帧和项目资产的输出参数由系统应用，实际限制请查询能力工具。
                """;
    }

    @Override
    public String getParametersSchema() {
        String imageUrlDescription = "参考图片 URL 列表，调用前查询本次选定模型支持的数量与类型";

        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                        .set("prompt", JSONUtil.createObj()
                                .set("type", "string")
                                .set("description", "图片生成提示词（英文效果更佳）"))
                        .set("reviewTargetId", JSONUtil.createObj().set("type", "integer")
                                .set("description", "项目生成时填写本次生成对应的子资产 ID 或分镜镜头 ID"))
                        .set("expectedVersion", JSONUtil.createObj().set("type", "integer")
                                .set("description", "项目回填必填，填写实际读取的镜头或子资产版本"))
                        .set("frameType", JSONUtil.createObj().set("type", "string")
                                .set("description", "分镜帧回填必填，first 或 last，须与本次请求一致"))
                        .set("assetId", JSONUtil.createObj().set("type", "integer")
                                .set("description", "子资产回填必填，填写主资产 ID"))
                        .set("expectedParentVersion", JSONUtil.createObj().set("type", "integer")
                                .set("description", "子资产回填必填，填写实际读取的 assetVersion"))
                        .set("negativePrompt", JSONUtil.createObj()
                                .set("type", "string")
                                .set("description", "反向提示词，描述不希望出现的内容"))
                        .set("width", JSONUtil.createObj()
                                .set("type", "number")
                                .set("description", "图片宽度（默认使用模型配置中的默认宽度）"))
                        .set("height", JSONUtil.createObj()
                                .set("type", "number")
                                .set("description", "图片高度（默认使用模型配置中的默认高度）"))
                        .set("style", JSONUtil.createObj()
                                .set("type", "string")
                                .set("description", "风格（如 realistic, anime, watercolor 等）"))
                        .set("imageUrls", JSONUtil.createObj()
                                .set("type", "array")
                                .set("items", JSONUtil.createObj().set("type", "string"))
                                .set("description", imageUrlDescription)))
                .set("required", JSONUtil.parseArray("[\"prompt\"]"))
                .toString();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            String prompt = params.getStr("prompt");
            if (StrUtil.isBlank(prompt)) {
                return errorResult("缺少 prompt");
            }
            if (StrUtil.isNotBlank(params.getStr("style"))) {
                prompt += "\n风格要求：" + params.getStr("style");
            }
            if (StrUtil.isNotBlank(params.getStr("negativePrompt"))) {
                prompt += "\n请避免：" + params.getStr("negativePrompt");
            }

            var mutation = context.requireMutationContext();
            var input = json.read(toolInput, ObjectNode.class);
            var target = projectMedia.requireTarget(mutation, input, GenerationKind.IMAGE);
            if (target != null && target.header().resourceType() == CanvasValueType.ASSET_ITEM) {
                String saved = target.data().path("properties").path(AssetFields.IMAGE_PROMPT).textValue();
                if (StrUtil.isBlank(saved)) {
                    return errorResult("请先为当前子资产生成或填写生图提示词并保存");
                }
                prompt = saved;
            }

            var selection = projectImageOptions.selected(context);
            int width = selection == null ? params.getInt("width", 0) : 0;
            int height = selection == null ? params.getInt("height", 0) : 0;

            // 参考图片（图生图）
            String refImageUrls = null;
            if (params.containsKey("imageUrls")) {
                List<String> imageUrls = params.getJSONArray("imageUrls").toList(String.class);
                if (!imageUrls.isEmpty()) {
                    refImageUrls = JSONUtil.toJsonStr(imageUrls);
                }
            }

            AiModel model;
            if (selection != null) {
                model = selection.model();
            } else {
                model = canvasModels.scoped(context) ? canvasModels.require(context,
                        GenerationKind.IMAGE) : resolvePreferredModel();
            }

            // 构建生图任务
            ImageTask task = ImageTask.builder()
                    .prompt(prompt)
                    .width(width > 0 ? width : null)
                    .height(height > 0 ? height : null)
                    .ratio(selection == null ? null : selection.aspectRatio())
                    .aspectRatio(selection == null ? null : selection.aspectRatio())
                    .resolution(selection == null ? null : selection.resolution())
                    .refImageUrls(refImageUrls)
                    .modelId(model.getId())
                    .count(1)
                    .userId(context.getUserId())
                    .build();

            generationModelCapabilityService.validateImageTask(model, task);

            log.info("[generate_image] 提交生图任务: prompt={}, size={}x{}, modelId={}, modelCode={}, 参考图: {}",
                    prompt, width, height, model.getId(), model.getCode(), refImageUrls != null ? "有" : "无");

            var review = reviews.agent(mutation, GenerationKind.IMAGE, input.get("reviewTargetId"));
            var accepted = submissions.image(task, ResourceToolRequests.requestId(json, mutation), mutation, null,
                    review);
            if (target != null && context.getCanvasScope() == null) {
                throw new ToolSuspendException("图片已受理，生成完成后自动回填。");
            }
            return JSONUtil.createObj()
                    .set("status", "pending")
                    .set("submissionId", accepted.id())
                    .set("taskId", accepted.publicTaskId())
                    .set("recordId", accepted.taskId())
                    .set("state", accepted.state())
                    .set("message",
                            "生成已可靠受理。通过 get_generation_task 读取实际结果；尚未完成时不要将其报告为生成成功。")
                    .toString();

        } catch (ToolSuspendException pending) {
            throw pending;
        } catch (Exception e) {
            log.error("[generate_image] 生成图片失败", e);
            return errorResult("生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取默认图片生成模型的 ID
     */
    private AiModel resolvePreferredModel() {
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_IMAGE);
        if (defaultModel != null) {
            return defaultModel;
        }
        throw new IllegalStateException("未配置可用的图片生成模型");
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
