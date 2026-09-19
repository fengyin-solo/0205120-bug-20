package com.redtourism.config;

import com.redtourism.common.Constants;
import com.redtourism.service.InteractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时 schema 迁移与统计数据校正：
 * 1) 为历史数据库补齐 hotel.comment_count、food.rating、food.comment_count 列
 *    （schema.sql 仅在新装环境生效，已有数据卷不会重复执行）；
 * 2) 以 comment 表为准，全量重算景点/酒店/美食的平均分与评论数，
 *    修复历史增删评论未同步统计造成的口径漂移，保证列表页、详情页与后台数据一致。
 */
@Slf4j
@Component
public class SchemaMigrationRunner implements ApplicationRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private InteractionService interactionService;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumn("hotel", "comment_count", "ALTER TABLE hotel ADD COLUMN comment_count BIGINT DEFAULT 0");
        ensureColumn("food", "rating", "ALTER TABLE food ADD COLUMN rating DOUBLE DEFAULT 0");
        ensureColumn("food", "comment_count", "ALTER TABLE food ADD COLUMN comment_count BIGINT DEFAULT 0");
        recalcAllCommentStats();
    }

    private void ensureColumn(String table, String column, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (count != null && count == 0) {
                jdbcTemplate.execute(alterSql);
                log.info("schema迁移：{} 表新增 {} 列", table, column);
            }
        } catch (Exception e) {
            log.error("schema迁移失败（{}.{})：{}", table, column, e.getMessage());
        }
    }

    private void recalcAllCommentStats() {
        try {
            recalcForType(Constants.TARGET_SPOT);
            recalcForType(Constants.TARGET_HOTEL);
            recalcForType(Constants.TARGET_FOOD);
        } catch (Exception e) {
            log.error("评论统计校正失败：{}", e.getMessage());
        }
    }

    private void recalcForType(String targetType) {
        List<Long> targetIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT target_id FROM comment WHERE target_type = ?", Long.class, targetType);
        for (Long targetId : targetIds) {
            interactionService.recalcCommentStats(targetType, targetId);
        }
        // 已无评论的目标清零统计，避免残留删除前的旧值
        String table;
        switch (targetType) {
            case Constants.TARGET_SPOT:
                table = "scenic_spot";
                break;
            case Constants.TARGET_HOTEL:
                table = "hotel";
                break;
            case Constants.TARGET_FOOD:
                table = "food";
                break;
            default:
                return;
        }
        String ratingColumn = Constants.TARGET_SPOT.equals(targetType) ? "avg_rating" : "rating";
        jdbcTemplate.update(
                "UPDATE " + table + " t SET t." + ratingColumn + " = 0, t.comment_count = 0 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM comment c WHERE c.target_type = ? AND c.target_id = t.id)",
                targetType);
    }
}
