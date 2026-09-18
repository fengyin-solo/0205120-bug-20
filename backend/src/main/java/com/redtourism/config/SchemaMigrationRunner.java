package com.redtourism.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 针对已存在数据库的幂等结构补丁：
 * schema.sql 只在首次建库时执行，老库需要在启动时补齐评价统计列，
 * 并把历史脏数据（冗余的评分/条数字段）按 comment 表重算到一致口径。
 */
@Slf4j
@Component
@Order(10)
public class SchemaMigrationRunner implements ApplicationRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("hotel", "comment_count", "BIGINT DEFAULT 0");
        addColumnIfMissing("food", "avg_rating", "DOUBLE DEFAULT 0");
        addColumnIfMissing("food", "comment_count", "BIGINT DEFAULT 0");
        syncAllTargetStats();
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            List<Map<String, Object>> exists = jdbcTemplate.queryForList(
                    "SELECT 1 FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, column);
            if (exists.isEmpty()) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("迁移：已为表 {} 补充列 {}", table, column);
            }
        } catch (Exception e) {
            log.warn("迁移：检查表 {}.{} 结构失败：{}", table, column, e.getMessage());
        }
    }

    /**
     * 启动时以 comment 表为唯一口径，把景点/酒店/美食三张表的
     * 平均分与评价条数整体重算一遍，保证升级后列表、详情、后台立即一致。
     */
    private void syncAllTargetStats() {
        updateStats("scenic_spot", "SPOT", "avg_rating", "comment_count");
        updateStats("hotel", "HOTEL", "rating", "comment_count");
        updateStats("food", "FOOD", "avg_rating", "comment_count");
    }

    private void updateStats(String table, String targetType, String ratingColumn, String countColumn) {
        String sql = "UPDATE " + table + " t LEFT JOIN (" +
                "SELECT target_id, COUNT(*) AS cnt, COALESCE(ROUND(AVG(rating), 1), 0) AS avg_r " +
                "FROM comment WHERE target_type = ? GROUP BY target_id" +
                ") c ON c.target_id = t.id " +
                "SET t." + countColumn + " = COALESCE(c.cnt, 0), t." + ratingColumn + " = COALESCE(c.avg_r, 0)";
        try {
            int rows = jdbcTemplate.update(sql, targetType);
            log.info("迁移：已按 comment 表重算 {} 的评分/条数（影响 {} 行）", table, rows);
        } catch (Exception e) {
            log.warn("迁移：重算 {} 评分/条数失败：{}", table, e.getMessage());
        }
    }
}
