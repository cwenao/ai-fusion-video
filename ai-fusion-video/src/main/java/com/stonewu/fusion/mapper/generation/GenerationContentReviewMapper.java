package com.stonewu.fusion.mapper.generation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.fusion.entity.generation.GenerationContentReviewEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GenerationContentReviewMapper extends BaseMapper<GenerationContentReviewEntity> {
    Long nextPending();

    int claim(@Param("id") long id);

    int expire();

    boolean active(@Param("id") long id);

    int complete(@Param("id") long id, @Param("state") String state,
                 @Param("result") String result, @Param("message") String message);
}
