package com.redtourism.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redtourism.dto.TargetCommentStats;
import com.redtourism.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 统计某对象的评价条数与平均分。以 comment 表为唯一口径，
     * 列表页 / 详情页 / 后台展示与冗余字段回写都走这里。
     */
    @Select("SELECT COUNT(*) AS commentCount, " +
            "COALESCE(AVG(rating), 0) AS avgRating, " +
            "#{targetType} AS targetType, #{targetId} AS targetId " +
            "FROM comment WHERE target_type = #{targetType} AND target_id = #{targetId}")
    TargetCommentStats selectTargetStats(@Param("targetType") String targetType,
                                         @Param("targetId") Long targetId);
}
