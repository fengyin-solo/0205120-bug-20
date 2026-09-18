package com.redtourism.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.redtourism.common.Constants;
import com.redtourism.common.Result;
import com.redtourism.dto.TargetCommentStats;
import com.redtourism.entity.Comment;
import com.redtourism.entity.User;
import com.redtourism.service.InteractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/comment")
public class CommentController {

    @Autowired
    private InteractionService interactionService;

    @GetMapping("/add")
    public Result<String> add(@RequestParam String targetType,
                               @RequestParam Long targetId,
                               @RequestParam String content,
                               @RequestParam(required = false) String images,
                               @RequestParam(required = false, defaultValue = "5") Integer rating,
                               HttpSession session) {
        User user = (User) session.getAttribute(Constants.SESSION_USER);
        if (user == null) return Result.error(401, "请先登录");
        if (content == null || content.trim().isEmpty()) return Result.error("评价内容不能为空");
        Comment comment = new Comment();
        comment.setUserId(user.getId());
        comment.setTargetType(targetType);
        comment.setTargetId(targetId);
        comment.setContent(content);
        comment.setImages(images);
        comment.setRating(rating);
        interactionService.addComment(comment);
        return Result.success("评论成功", null);
    }

    @GetMapping("/list")
    public Result<IPage<Comment>> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size,
                                        @RequestParam(required = false) String targetType,
                                        @RequestParam(required = false) Long targetId) {
        return Result.success(interactionService.listComments(page, size, targetType, targetId));
    }

    /** 评价条数与平均分，统一以 comment 表实时聚合为准 */
    @GetMapping("/stats")
    public Result<TargetCommentStats> stats(@RequestParam String targetType,
                                             @RequestParam Long targetId) {
        return Result.success(interactionService.getTargetStats(targetType, targetId));
    }

    /**
     * 删除评价：仅评价本人或管理员可操作。
     * 使用 POST，避免浏览器/代理缓存写请求导致“删完又出现”。
     */
    @PostMapping("/delete")
    public Result<String> delete(@RequestParam Long id, HttpSession session) {
        return doDelete(id, session);
    }

    /** 兼容旧链接（GET 已不推荐，仅保留转发），同样做归属校验 */
    @GetMapping("/delete")
    public Result<String> deleteGet(@RequestParam Long id, HttpSession session) {
        return doDelete(id, session);
    }

    private Result<String> doDelete(Long id, HttpSession session) {
        User user = (User) session.getAttribute(Constants.SESSION_USER);
        if (user == null) return Result.error(401, "请先登录");
        try {
            interactionService.deleteComment(id, user);
        } catch (SecurityException e) {
            return Result.error(403, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(401, e.getMessage());
        }
        return Result.success("删除成功", null);
    }
}
