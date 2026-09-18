package com.redtourism.dto;

import lombok.Data;

/**
 * 批量删除评价时，逐条反馈的处理结果。
 * 已不存在的评价视为删除成功（幂等），失败时不影响其它条目并允许前端重试。
 */
@Data
public class CommentDeleteResult {
    private Long id;
    private boolean success;
    private String msg;

    public CommentDeleteResult() {}

    public CommentDeleteResult(Long id, boolean success, String msg) {
        this.id = id;
        this.success = success;
        this.msg = msg;
    }

    public static CommentDeleteResult ok(Long id) {
        return new CommentDeleteResult(id, true, "删除成功");
    }

    public static CommentDeleteResult fail(Long id, String msg) {
        return new CommentDeleteResult(id, false, msg);
    }
}
