package com.stonewu.fusion.service.generation.review;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.GenerationContentReviewEntity;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationReviewWorker {
    private final GenerationContentReviewJobs jobs;
    private final GenerationReviewExecution execution;
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (!slots.tryAcquire()) {
            return;
        }
        boolean started = false;
        try {
            var job = jobs.claim();
            if (job != null) {
                workers.submit(() -> run(job));
                started = true;
            }
        } finally {
            if (!started) {
                slots.release();
            }
        }
    }

    private void run(GenerationContentReviewEntity job) {
        try {
            jobs.succeed(job.getId(), execution.review(job));
        } catch (Exception failure) {
            log.warn("生成素材审查未完成 [reviewId={}, reason={}]", job.getId(),
                    failure.getClass().getSimpleName());
            jobs.fail(job.getId(), message(failure));
        } finally {
            slots.release();
        }
    }

    private String message(Exception failure) {
        if (failure instanceof GenerationReviewFailure expected) {
            return expected.getMessage();
        }
        if (failure instanceof BusinessException expected && (expected.getCode() == 403
                || expected.getCode() == 404 || expected.getCode() == 410)) {
            return "生成素材或审查模型已不可访问";
        }
        return "AI 审查未完成，请检查审查模型配置；生成结果仍可使用";
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
