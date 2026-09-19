package com.redtourism.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.redtourism.entity.Comment;
import com.redtourism.entity.Favorite;
import com.redtourism.entity.LikeRecord;
import com.redtourism.entity.User;
import java.util.List;
import java.util.Map;

public interface InteractionService {
    boolean addComment(Comment comment);
    IPage<Comment> listComments(int page, int size, String targetType, Long targetId);
    IPage<Comment> listAllComments(int page, int size, String keyword);
    Comment getCommentById(Long id);
    boolean replyComment(Long commentId, String replyContent, Long adminId);

    /**
     * 删除评论（仅评论本人或管理员可操作），删除成功后同步重算目标对象的平均分与评论数。
     * 评论不存在或无权删除时抛出 BizException。
     */
    void deleteComment(Long id, User operator);

    /**
     * 批量删除评论：逐条独立事务处理，单条失败不影响其他条目，
     * 返回每条的处理结果（成功的条数、失败的条数及逐条明细），失败的评论保留可重试。
     */
    Map<String, Object> batchDeleteComments(List<Long> ids, User operator);

    /**
     * 按 comment 表实时统计并重算目标对象（景点/酒店/美食）的平均分与评论数，
     * 保证列表页、详情页与后台统计口径一致。
     */
    void recalcCommentStats(String targetType, Long targetId);

    boolean addFavorite(Long userId, String targetType, Long targetId);
    boolean removeFavorite(Long userId, String targetType, Long targetId);
    boolean isFavorited(Long userId, String targetType, Long targetId);
    List<Favorite> listUserFavorites(Long userId, String targetType);

    boolean addLike(Long userId, String targetType, Long targetId);
    boolean removeLike(Long userId, String targetType, Long targetId);
    boolean isLiked(Long userId, String targetType, Long targetId);
    long countLikes(String targetType, Long targetId);
}
