package com.redtourism.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.redtourism.common.BizException;
import com.redtourism.common.Constants;
import com.redtourism.entity.*;
import com.redtourism.mapper.*;
import com.redtourism.service.InteractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private ScenicSpotMapper scenicSpotMapper;
    @Autowired
    private HotelMapper hotelMapper;
    @Autowired
    private FoodMapper foodMapper;

    private final TransactionTemplate transactionTemplate;

    public InteractionServiceImpl(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean addComment(Comment comment) {
        boolean inserted = commentMapper.insert(comment) > 0;
        if (inserted) {
            recalcCommentStats(comment.getTargetType(), comment.getTargetId());
        }
        return inserted;
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
        // create_time 可能同秒重复，追加 id 作为唯一次序，避免分页漂移/重复
        wrapper.orderByDesc(Comment::getCreateTime).orderByDesc(Comment::getId);
        IPage<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        result.getRecords().forEach(c -> {
            User user = userMapper.selectById(c.getUserId());
            if (user != null) {
                c.setUsername(user.getNickname() != null ? user.getNickname() : user.getUsername());
                c.setUserAvatar(user.getAvatar());
            }
        });
        return result;
    }

    @Override
    public IPage<Comment> listAllComments(int page, int size, String keyword) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Comment::getContent, keyword);
        }
        wrapper.orderByDesc(Comment::getCreateTime).orderByDesc(Comment::getId);
        IPage<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        result.getRecords().forEach(c -> {
            User user = userMapper.selectById(c.getUserId());
            if (user != null) {
                c.setUsername(user.getNickname() != null ? user.getNickname() : user.getUsername());
                c.setUserAvatar(user.getAvatar());
            }
        });
        return result;
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
            throw new BizException(401, "请先登录");
        }
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BizException(404, "评论不存在或已被删除");
        }
        boolean isOwner = comment.getUserId() != null && comment.getUserId().equals(operator.getId());
        boolean isAdmin = Constants.ROLE_ADMIN.equals(operator.getRole());
        if (!isOwner && !isAdmin) {
            throw new BizException(403, "无权删除他人的评论");
        }
        // 删除与统计重算在同一事务内，失败整体回滚，评论保留
        transactionTemplate.execute(status -> {
            doDelete(comment);
            return null;
        });
    }

    @Override
    public Map<String, Object> batchDeleteComments(List<Long> ids, User operator) {
        List<Map<String, Object>> items = new ArrayList<>();
        int successCount = 0;
        // 逐条独立事务：单条失败回滚该条（评论保留），不影响其他条目
        for (Long id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                transactionTemplate.execute(status -> {
                    deleteComment(id, operator);
                    return null;
                });
                item.put("success", true);
                item.put("message", "删除成功");
                successCount++;
            } catch (BizException e) {
                item.put("success", false);
                item.put("message", e.getMessage());
            } catch (Exception e) {
                item.put("success", false);
                item.put("message", "删除失败：" + e.getMessage());
            }
            items.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", ids.size());
        result.put("successCount", successCount);
        result.put("failCount", ids.size() - successCount);
        result.put("items", items);
        return result;
    }

    /**
     * 执行物理删除并同步重算目标对象统计；必须在事务内调用，
     * 任一步失败整体回滚，保证评论与统计数据一致。
     */
    private void doDelete(Comment comment) {
        if (commentMapper.deleteById(comment.getId()) <= 0) {
            throw new BizException("删除失败，请稍后重试");
        }
        recalcCommentStats(comment.getTargetType(), comment.getTargetId());
    }

    @Override
    public void recalcCommentStats(String targetType, Long targetId) {
        if (!StringUtils.hasText(targetType) || targetId == null) {
            return;
        }
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getTargetType, targetType).eq(Comment::getTargetId, targetId);
        List<Comment> comments = commentMapper.selectList(wrapper);
        long count = comments.size();
        double avg = comments.stream()
                .mapToInt(c -> c.getRating() == null ? 5 : c.getRating())
                .average()
                .orElse(0.0);
        // 保留 1 位小数，避免各端展示精度不一致
        avg = Math.round(avg * 10) / 10.0;
        switch (targetType) {
            case Constants.TARGET_SPOT: {
                ScenicSpot spot = new ScenicSpot();
                spot.setId(targetId);
                spot.setAvgRating(avg);
                spot.setCommentCount(count);
                scenicSpotMapper.updateById(spot);
                break;
            }
            case Constants.TARGET_HOTEL: {
                Hotel hotel = new Hotel();
                hotel.setId(targetId);
                hotel.setRating(avg);
                hotel.setCommentCount(count);
                hotelMapper.updateById(hotel);
                break;
            }
            case Constants.TARGET_FOOD: {
                Food food = new Food();
                food.setId(targetId);
                food.setRating(avg);
                food.setCommentCount(count);
                foodMapper.updateById(food);
                break;
            }
            default:
                // 线路/文化等无评分统计列，无需重算
                break;
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
