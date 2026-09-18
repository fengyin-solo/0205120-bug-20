package com.redtourism.dto;

import lombok.Data;

/**
 * 某个被评价对象（景点/酒店/美食）在 comment 表中的实时评价统计。
 * 列表页、详情页、后台统一以此口径回写与展示。
 */
@Data
public class TargetCommentStats {
    private String targetType;
    private Long targetId;
    private Long commentCount = 0L;
    private Double avgRating = 0.0;

    public TargetCommentStats() {}

    public TargetCommentStats(String targetType, Long targetId, Long commentCount, Double avgRating) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.commentCount = commentCount == null ? 0L : commentCount;
        this.avgRating = avgRating == null ? 0.0 : avgRating;
    }
}
