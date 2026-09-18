package com.redtourism.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.redtourism.dto.CommentDeleteResult;
import com.redtourism.dto.TargetCommentStats;
import com.redtourism.entity.Comment;
import com.redtourism.entity.Favorite;
import com.redtourism.entity.LikeRecord;
import com.redtourism.entity.User;
import java.util.List;

public interface InteractionService {
    boolean addComment(Comment comment);
    IPage<Comment> listComments(int page, int size, String targetType, Long targetId);
    IPage<Comment> listAllComments(int page, int size, String keyword);
    Comment getCommentById(Long id);
    boolean replyComment(Long commentId, String replyContent, Long adminId);

    /**
     * 删除单条评价，仅评价本人或管理员可操作。
     * @throws IllegalStateException 未登录
     * @throws SecurityException 无权删除
     */
    void deleteComment(Long id, User operator);

    /**
     * 批量删除评价（管理员），逐条执行、逐条反馈。
     * 单条失败不影响其它条目，已删除的按幂等返回成功。
     */
    List<CommentDeleteResult> deleteComments(List<Long> ids, User operator);

    /** 以 comment 表为唯一口径，获取某对象的评价条数与平均分 */
    TargetCommentStats getTargetStats(String targetType, Long targetId);

    boolean addFavorite(Long userId, String targetType, Long targetId);
    boolean removeFavorite(Long userId, String targetType, Long targetId);
    boolean isFavorited(Long userId, String targetType, Long targetId);
    List<Favorite> listUserFavorites(Long userId, String targetType);

    boolean addLike(Long userId, String targetType, Long targetId);
    boolean removeLike(Long userId, String targetType, Long targetId);
    boolean isLiked(Long userId, String targetType, Long targetId);
    long countLikes(String targetType, Long targetId);
}
