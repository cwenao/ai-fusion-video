package com.stonewu.fusion.controller.generation;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.service.canvas.CanvasActorResolver;
import com.stonewu.fusion.service.generation.GenerationKind;
import com.stonewu.fusion.service.generation.job.GenerationJobException;
import com.stonewu.fusion.service.generation.review.GenerationContentReview.Target;
import com.stonewu.fusion.service.generation.review.GenerationContentReview.View;
import com.stonewu.fusion.service.generation.review.GenerationContentReviewJobs;
import com.stonewu.fusion.service.generation.review.GenerationReviewModels;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/generation/reviews")
@RequiredArgsConstructor
public class GenerationContentReviewController {
    private final GenerationReviewModels models;
    private final GenerationContentReviewJobs jobs;
    private final CanvasActorResolver actors;

    @GetMapping("/models")
    public CommonResult<List<GenerationReviewModels.Option>> models(@RequestParam("kind") GenerationKind kind) {
        return CommonResult.success(models.options(kind));
    }

    @GetMapping("/media/{mediaId}")
    public CommonResult<View> result(@PathVariable String mediaId,
                                     @RequestParam(name = "target", required = false) Target target,
                                     @RequestParam(name = "targetId", required = false) Long targetId) {
        if ((target == null) != (targetId == null) || (targetId != null && targetId < 1)) {
            throw GenerationJobException.invalid("审查对象类型和编号必须同时有效");
        }
        return CommonResult.success(jobs.byMedia(mediaId, target, targetId, actors.current()));
    }
}
