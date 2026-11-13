package com.stonewu.fusion.entity.generation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("afv_generation_content_review")
public class GenerationContentReviewEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String submissionId;
    private Long itemId;
    private String mediaId;
    private Long modelId;
    private String state;
    private LocalDateTime deadlineAt;
    private String resultJson;
    private String errorMessage;
}
