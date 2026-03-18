package com.flashchat.chatservice.toolkit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dto.req.CursorPageBaseReq;
import com.flashchat.chatservice.dto.resp.CursorPageBaseResp;

import java.util.List;
import java.util.function.Consumer;

public class CursorUtils {
    /**
     * 游标分页查询（倒序，向前翻页）
     * 生成的 SQL：
     *   SELECT * FROM t_message
     *   WHERE room_id = ? AND status = 0 [AND id < cursor]
     *   ORDER BY id DESC
     *   LIMIT pageSize + 1
     *
     * @param service      MyBatis-Plus 的 IService 实现
     * @param request      分页参数（cursor + pageSize）
     * @param initWrapper  调用方传入的附加查询条件
     * @param cursorColumn 游标字段（如 MessageDO::getId）
     * @param <T>          实体类型
     * @return 分页结果
     */
    public static <T> CursorPageBaseResp<T> getCursorPage(
            IService<T> service,
            CursorPageBaseReq request,
            Consumer<LambdaQueryWrapper<T>> initWrapper,
            SFunction<T, ?> cursorColumn) {

        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();

        // 1. 注入外部条件
        initWrapper.accept(wrapper);

        // 2. 游标条件：WHERE id < cursor
        if (request.getCursor() != null && !request.getCursor().isBlank()) {
            wrapper.lt(cursorColumn, parseCursorValue(request.getCursor()));
        }

        // 3. 倒序
        wrapper.orderByDesc(cursorColumn);

        // 4. 多查 1 条
        int querySize = request.getPageSize() + 1;
        wrapper.last("LIMIT " + querySize);

        // 5. 执行查询
        List<T> records = service.list(wrapper);

        // 6. 判断是否最后一页
        boolean isLast = records.size() <= request.getPageSize();
        if (!isLast) {
            records = records.subList(0, request.getPageSize());
        }

        // 7. 提取下一页游标（最后一条记录的游标字段值）
        String nextCursor = null;
        if (!records.isEmpty() && !isLast) {
            T lastRecord = records.get(records.size() - 1);
            nextCursor = extractCursorValue(lastRecord, cursorColumn);
        }

        return CursorPageBaseResp.<T>builder()
                .list(records)
                .cursor(nextCursor)
                .isLast(isLast)
                .build();
    }


    /**
     * 反向游标查询（正序，拉取游标之后的新数据）
     * 用于：断线重连后补齐断线期间的消息
     *
     * 生成的 SQL：
     *   SELECT * FROM t_message
     *   WHERE room_id = ? AND status = 0 AND id > #{afterCursor}
     *   ORDER BY id ASC
     *   LIMIT #{maxSize + 1}
     *
     * @param service      MyBatis-Plus 的 IService 实现
     * @param afterCursor  游标值（查询此值之后的数据）
     * @param maxSize      最多返回多少条
     * @param initWrapper  附加查询条件
     * @param cursorColumn 游标字段
     * @param <T>          实体类型
     * @return 分页结果（list 已经是正序，前端直接渲染）
     */
    public static <T> CursorPageBaseResp<T> getAfterCursor(
            IService<T> service,
            String afterCursor,
            int maxSize,
            Consumer<LambdaQueryWrapper<T>> initWrapper,
            SFunction<T, ?> cursorColumn) {

        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();

        // 1. 注入外部条件
        initWrapper.accept(wrapper);

        // 2. 游标条件：WHERE id > cursor（与 getCursorPage 的 lt 相反）
        if (afterCursor != null && !afterCursor.isBlank()) {
            wrapper.gt(cursorColumn, parseCursorValue(afterCursor));
        }

        // 3. 正序（与 getCursorPage 的 DESC 相反）
        wrapper.orderByAsc(cursorColumn);

        // 4. 多查 1 条判断是否还有更多
        wrapper.last("LIMIT " + (maxSize + 1));

        // 5. 执行查询
        List<T> records = service.list(wrapper);

        // 6. 判断是否最后一页
        boolean isLast = records.size() <= maxSize;
        if (!isLast) {
            records = records.subList(0, maxSize);
        }

        // 7. 提取下一页游标（最后一条记录，即最新的那条）
        //    下次请求：WHERE id > nextCursor → 继续往后拉
        String nextCursor = null;
        if (!records.isEmpty()) {
            T lastRecord = records.get(records.size() - 1);
            nextCursor = extractCursorValue(lastRecord, cursorColumn);
        }

        return CursorPageBaseResp.<T>builder()
                .list(records)
                .cursor(nextCursor)
                .isLast(isLast)
                .build();
    }


    /**
     * 解析游标值（String → 合适的类型）
     * 当前只支持 Long 类型（消息 id），未来可扩展
     */
    private static Object parseCursorValue(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return cursor;
        }
    }

    /**
     * 提取记录中游标字段的值
     */
    private static <T> String extractCursorValue(T record, SFunction<T, ?> cursorColumn) {
        try {
            Object value = cursorColumn.apply(record);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
