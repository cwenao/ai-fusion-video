package com.stonewu.fusion.service.generation.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.entity.generation.GenerationContentReviewEntity;
import com.stonewu.fusion.mapper.execution.ExecutionCoordinationMapper;
import com.stonewu.fusion.mapper.generation.GenerationContentReviewMapper;
import com.stonewu.fusion.service.canvas.CanvasJson;
import com.stonewu.fusion.service.canvas.model.CanvasActor;
import com.stonewu.fusion.service.generation.GenerationExecutionContexts;
import com.stonewu.fusion.service.generation.job.GenerationJobRepository;
import com.stonewu.fusion.service.generation.job.GenerationSubmission;
import com.stonewu.fusion.service.media.MediaReference;
import com.stonewu.fusion.service.media.MediaResourceQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.stonewu.fusion.service.generation.review.GenerationContentReview.*;

@Component
@RequiredArgsConstructor
public class GenerationContentReviewJobs {
    private final GenerationContentReviewMapper jobs;
    private final ExecutionCoordinationMapper coordination;
    private final GenerationJobRepository submissions;
    private final GenerationExecutionContexts access;
    private final MediaResourceQueries media;
    private final CanvasJson json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(GenerationSubmission submission, long itemId, String mediaReference) {
        var review = submission.contentReview();
        if (review != null) {
            jobs.insert(GenerationContentReviewEntity.builder().submissionId(submission.id()).itemId(itemId)
                    .mediaId(MediaReference.id(mediaReference)).modelId(review.model().id()).build());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GenerationContentReviewEntity claim() {
        // 跨实例串行决定同一模型的领取；提交后释放锁，随后才准备媒体和调用模型。
        coordination.lockGenerationDispatch();
        jobs.expire();
        Long id = jobs.nextPending();
        if (id == null || jobs.claim(id) != 1) {
            return null;
        }
        return jobs.selectById(id);
    }

    public void requireActive(long id) {
        if (!jobs.active(id)) {
            throw new GenerationReviewFailure("审查任务已超时或结束");
        }
    }

    public void succeed(long id, Result result) {
        jobs.complete(id, "COMPLETED", json.write(result), null);
    }

    public void fail(long id, String message) {
        jobs.complete(id, "FAILED", null, message);
    }

    public View byMedia(String mediaId, Target target, Long targetId, CanvasActor actor) {
        MediaReference.of(mediaId);
        var row = jobs.selectOne(new LambdaQueryWrapper<GenerationContentReviewEntity>()
                .eq(GenerationContentReviewEntity::getMediaId, mediaId));
        if (row == null) {
            return null;
        }
        var submission = submissions.submission(row.getSubmissionId());
        access.requireRead(submission.kind(), submission.taskId(), actor);
        media.get(mediaId, actor);
        var criteria = submission.contentReview();
        if (target != null && (target != criteria.target() || !targetId.equals(criteria.targetId()))) {
            return null;
        }
        return new View(row.getState(), criteria.model().name(),
                row.getResultJson() == null ? null : json.read(row.getResultJson(), Result.class),
                row.getErrorMessage());
    }
}
