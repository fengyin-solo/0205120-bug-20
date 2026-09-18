package com.redtourism.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.redtourism.common.Constants;
import com.redtourism.dto.CommentDeleteResult;
import com.redtourism.dto.TargetCommentStats;
import com.redtourism.entity.*;
import com.redtourism.mapper.*;
import com.redtourism.service.InteractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class InteractionServiceImpl implements InteractionService {

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private FavoriteMapper favoriteMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ScenicSpotMapper spotMapper;
    @Autowired
    private HotelMapper hotelMapper;
    @Autowired
    private FoodMapper foodMapper;

    @Override
    public boolean addComment(Comment comment) {
        if (comment.getRating() == null) {
            comment.setRating(5);
        }
        if (comment.getRating() < 1 || comment.getRating() > 5) {
            throw new RuntimeException("评分必须在 1~5 之间");
        }
        if (!isSupportedTarget(comment.getTargetType())) {
            throw new RuntimeException("不支持的评价对象类型");
        }
        boolean ok = commentMapper.insert(comment) > 0;
        if (ok) {
            refreshTargetStats(comment.getTargetType(), comment.getTargetId());
        }
        return ok;
    }

    @Override
    public IPage<Comment> listComments(int page, int size, String targetType, Long targetId) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(Comment::getTargetType, targetType);
        }
        if (targetId != null) {
            wrapper.eq(Comment::getTargetId, targetId);
        }
        wrapper.orderByDesc(Comment::getCreateTime);
        IPage<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        fillUserInfo(result.getRecords());
        return result;
    }

    @Override
    public IPage<Comment> listAllComments(int page, int size, String keyword) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Comment::getContent, keyword);
        }
        wrapper.orderByDesc(Comment::getCreateTime);
        IPage<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        fillUserInfo(result.getRecords());
        return result;
    }

    private void fillUserInfo(List<Comment> records) {
        records.forEach(c -> {
            User user = userMapper.selectById(c.getUserId());
            if (user != null) {
                c.setUsername(user.getNickname() != null ? user.getNickname() : user.getUsername());
                c.setUserAvatar(user.getAvatar());
            }
        });
    }

    @Override
    public Comment getCommentById(Long id) {
        return commentMapper.selectById(id);
    }

    @Override
    public boolean replyComment(Long commentId, String replyContent, Long adminId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new RuntimeException("留言不存在");
        }
        comment.setReplyContent(replyContent);
        comment.setReplyTime(new Date());
        return commentMapper.updateById(comment) > 0;
    }

    @Override
    public void deleteComment(Long id, User operator) {
        if (operator == null) {
            throw new IllegalStateException("请先登录");
        }
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            // 已不存在，按幂等处理，避免前端重试时报错
            return;
        }
        if (!canDelete(operator, comment)) {
            throw new SecurityException("只能删除自己的评价");
        }
        if (commentMapper.deleteById(id) <= 0) {
            throw new RuntimeException("删除失败，请重试");
        }
        refreshTargetStats(comment.getTargetType(), comment.getTargetId());
    }

    @Override
    public List<CommentDeleteResult> deleteComments(List<Long> ids, User operator) {
        if (operator == null || !Constants.ROLE_ADMIN.equals(operator.getRole())) {
            throw new SecurityException("无权批量删除评价");
        }
        List<CommentDeleteResult> results = new ArrayList<>();
        // 只对真正被删掉的对象重算统计，且按对象去重，避免重复 UPDATE
        Set<String> refreshed = new HashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null) {
                    results.add(CommentDeleteResult.fail(null, "评价ID为空"));
                    continue;
                }
                try {
                    Comment comment = commentMapper.selectById(id);
                    if (comment == null) {
                        // 幂等：上一轮可能已删掉，重试时标记成功并保留在列表外
                        results.add(CommentDeleteResult.ok(id));
                        continue;
                    }
                    if (commentMapper.deleteById(id) > 0) {
                        results.add(CommentDeleteResult.ok(id));
                        refreshed.add(comment.getTargetType() + "#" + comment.getTargetId());
                    } else {
                        results.add(CommentDeleteResult.fail(id, "删除失败，请重试"));
                    }
                } catch (Exception e) {
                    // 单条异常不回滚其它条目，原评价保留，前端可对失败项重试
                    results.add(CommentDeleteResult.fail(id, "删除失败：" + e.getMessage()));
                }
            }
        }
        for (String key : refreshed) {
            String[] parts = key.split("#", 2);
            refreshTargetStats(parts[0], Long.valueOf(parts[1]));
        }
        return results;
    }

    private boolean canDelete(User operator, Comment comment) {
        if (Constants.ROLE_ADMIN.equals(operator.getRole())) {
            return true;
        }
        return operator.getId() != null && operator.getId().equals(comment.getUserId());
    }

    private boolean isSupportedTarget(String targetType) {
        return Constants.TARGET_SPOT.equals(targetType)
                || Constants.TARGET_HOTEL.equals(targetType)
                || Constants.TARGET_FOOD.equals(targetType);
    }

    @Override
    public TargetCommentStats getTargetStats(String targetType, Long targetId) {
        TargetCommentStats stats = commentMapper.selectTargetStats(targetType, targetId);
        if (stats == null) {
            stats = new TargetCommentStats(targetType, targetId, 0L, 0.0);
        }
        stats.setTargetType(targetType);
        stats.setTargetId(targetId);
        // 平均分统一保留一位小数，AVG 返回类型由数据库决定，四舍五入放到 Java 侧处理
        stats.setAvgRating(Math.round(stats.getAvgRating() * 10.0) / 10.0);
        return stats;
    }

    /**
     * 以 comment 表实时聚合结果回写各对象表的冗余评分/条数字段，
     * 保证列表卡片、详情页、后台三处口径完全一致。
     */
    private void refreshTargetStats(String targetType, Long targetId) {
        if (!isSupportedTarget(targetType) || targetId == null) {
            return;
        }
        TargetCommentStats stats = getTargetStats(targetType, targetId);
        long count = stats.getCommentCount();
        double avg = stats.getAvgRating();
        try {
            switch (targetType) {
                case Constants.TARGET_SPOT:
                    ScenicSpot spot = spotMapper.selectById(targetId);
                    if (spot != null) {
                        spot.setAvgRating(avg);
                        spot.setCommentCount(count);
                        spotMapper.updateById(spot);
                    }
                    break;
                case Constants.TARGET_HOTEL:
                    Hotel hotel = hotelMapper.selectById(targetId);
                    if (hotel != null) {
                        hotel.setRating(avg);
                        hotel.setCommentCount(count);
                        hotelMapper.updateById(hotel);
                    }
                    break;
                case Constants.TARGET_FOOD:
                    Food food = foodMapper.selectById(targetId);
                    if (food != null) {
                        food.setAvgRating(avg);
                        food.setCommentCount(count);
                        foodMapper.updateById(food);
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
            // 统计回写失败不影响删除本身；下次增删评价时会再次重算
        }
    }

    @Override
    public boolean addFavorite(Long userId, String targetType, Long targetId) {
        if (isFavorited(userId, targetType, targetId)) {
            throw new RuntimeException("已收藏");
        }
        Favorite fav = new Favorite();
        fav.setUserId(userId);
        fav.setTargetType(targetType);
        fav.setTargetId(targetId);
        return favoriteMapper.insert(fav) > 0;
    }

    @Override
    public boolean removeFavorite(Long userId, String targetType, Long targetId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, targetType)
                .eq(Favorite::getTargetId, targetId);
        return favoriteMapper.delete(wrapper) > 0;
    }

    @Override
    public boolean isFavorited(Long userId, String targetType, Long targetId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, targetType)
                .eq(Favorite::getTargetId, targetId);
        return favoriteMapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<Favorite> listUserFavorites(Long userId, String targetType) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId);
        if (StringUtils.hasText(targetType)) {
            wrapper.eq(Favorite::getTargetType, targetType);
        }
        wrapper.orderByDesc(Favorite::getCreateTime);
        return favoriteMapper.selectList(wrapper);
    }

    @Override
    public boolean addLike(Long userId, String targetType, Long targetId) {
        if (isLiked(userId, targetType, targetId)) {
            throw new RuntimeException("已点赞");
        }
        LikeRecord like = new LikeRecord();
        like.setUserId(userId);
        like.setTargetType(targetType);
        like.setTargetId(targetId);
        return likeRecordMapper.insert(like) > 0;
    }

    @Override
    public boolean removeLike(Long userId, String targetType, Long targetId) {
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        return likeRecordMapper.delete(wrapper) > 0;
    }

    @Override
    public boolean isLiked(Long userId, String targetType, Long targetId) {
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        return likeRecordMapper.selectCount(wrapper) > 0;
    }

    @Override
    public long countLikes(String targetType, Long targetId) {
        LambdaQueryWrapper<LikeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LikeRecord::getTargetType, targetType)
                .eq(LikeRecord::getTargetId, targetId);
        return likeRecordMapper.selectCount(wrapper);
    }
}
