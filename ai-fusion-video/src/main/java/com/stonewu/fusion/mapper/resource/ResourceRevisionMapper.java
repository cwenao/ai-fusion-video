package com.stonewu.fusion.mapper.resource;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.stonewu.fusion.entity.resource.ResourceRevisionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ResourceRevisionMapper extends BaseMapper<ResourceRevisionEntity> {
    int capture(@Param("row") ResourceRevisionEntity row);

    record GenerationChange(String agentType, String parentRunId, Long userId, Long teamId, String frameType) {
    }

    record WritebackVersion(int version, Integer parentVersion) {
    }

    List<GenerationChange> generationChanges(@Param("type") String type, @Param("id") long id,
                                               @Param("after") int after, @Param("through") int through);

    WritebackVersion generationWritebackRequest(@Param("userId") long userId, @Param("requestId") String requestId);
}
