package com.flashchat.user.toolkit;


import com.flashchat.user.constant.UserTypeConstant;

/**
 * SaToken loginId 编解码工具
 * SaToken 的 loginId 支持任意类型（Long、String 等）。
 * FlashChat 有两类用户（匿名成员、注册用户），它们的数据库主键可能重叠
 * （member.id=1 和 user.id=1），如果直接用裸 Long 作为 loginId 会冲突。
 * 解决方案：loginId 用 "类型前缀_数据库ID" 格式的 String：
 * <ul>
 *   <li>匿名成员：{@code "member_123"}</li>
 *   <li>注册用户：{@code "user_456"}</li>
 * </ul>
 * 使用场景：
 * <ul>
 *   <li>登录时：{@code StpUtil.login(LoginIdUtil.memberLoginId(memberId))}</li>
 *   <li>HTTP 拦截器：从 {@code StpUtil.getLoginIdAsString()} 解析出 id 和类型</li>
 *   <li>WS 握手：从 {@code StpUtil.getLoginIdByToken(token)} 解析出 id 和类型</li>
 * </ul>
 * 未来迁移路径：
 * 如果需要更强的隔离，可迁移到 SaToken 多账号体系（StpMemberUtil / StpUserUtil），
 * 届时只需修改 login 调用和 token 校验逻辑，不影响业务层。
 */
public final class LoginIdUtil {

    private static final String MEMBER_PREFIX = "member_";
    private static final String USER_PREFIX = "user_";
    private static final char SEPARATOR = '_';

    private LoginIdUtil() {
    }

    // ==================== 编码方法 ====================

    /**
     * 构建匿名成员的 loginId
     * @param memberId t_member.id
     * @return "member_123"
     */
    public static String memberLoginId(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId 不能为 null");
        }
        return MEMBER_PREFIX + memberId;
    }

    /**
     * 构建注册用户的 loginId
     * @param userId t_user.id
     * @return "user_456"
     */
    public static String userLoginId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为 null");
        }
        return USER_PREFIX + userId;
    }

    /**
     * 根据用户类型动态构建 loginId
     * 适用于 doLogin() 等需要根据 userType 选择前缀的场景
     * @param id       数据库主键 ID
     * @param userType 用户类型，取值见 {@link UserTypeConstant}
     * @return 带前缀的 loginId
     * @throws IllegalArgumentException 未知的用户类型
     */
    public static String toLoginId(Long id, int userType) {
        return switch (userType) {
            case UserTypeConstant.MEMBER -> memberLoginId(id);
            case UserTypeConstant.USER -> userLoginId(id);
            default -> throw new IllegalArgumentException("未知的用户类型: " + userType);
        };
    }

    // ==================== 解码方法（String 入参） ====================

    /**
     * 从 loginId 字符串中提取数据库主键 ID
     * <p>
     * "member_123" → 123L
     * "user_456"   → 456L
     *
     * @param loginId SaToken 的 loginId 字符串
     * @return 数据库主键 ID
     * @throws IllegalArgumentException loginId 为空或格式不合法
     */
    public static Long extractId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId 不能为空");
        }
        int idx = loginId.indexOf(SEPARATOR);
        if (idx < 0 || idx == loginId.length() - 1) {
            throw new IllegalArgumentException("loginId 格式不合法: " + loginId);
        }
        try {
            return Long.parseLong(loginId.substring(idx + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("loginId 中的 ID 不是有效数字: " + loginId, e);
        }
    }

    /**
     * 从 loginId 字符串中提取用户类型
     * <p>
     * "member_123" → {@link UserTypeConstant#MEMBER}(0)
     * "user_456"   → {@link UserTypeConstant#USER}(1)
     *
     * @param loginId SaToken 的 loginId 字符串
     * @return 用户类型常量
     * @throws IllegalArgumentException loginId 前缀无法识别
     */
    public static int extractUserType(String loginId) {
        if (isMember(loginId)) {
            return UserTypeConstant.MEMBER;
        }
        if (isUser(loginId)) {
            return UserTypeConstant.USER;
        }
        throw new IllegalArgumentException("无法识别的 loginId 类型: " + loginId);
    }

    // ==================== 解码方法（Object 入参） ====================
    // 兼容 StpUtil.getLoginIdByToken() 等返回 Object 的 SaToken API

    /**
     * 从 SaToken 返回的 loginId（Object 类型）中提取数据库主键
     * <p>
     * 兼容 {@code StpUtil.getLoginIdByToken()} 等返回 Object 的 API
     * <p>
     * 使用示例：
     * <pre>{@code
     * Long memberId = LoginIdUtil.extractId(StpUtil.getLoginIdByToken(token));
     * }</pre>
     *
     * @param loginId SaToken 返回的 loginId 对象
     * @return 数据库主键 ID
     * @throws IllegalArgumentException loginId 为 null 或格式不合法
     */
    public static Long extractId(Object loginId) {
        if (loginId == null) {
            throw new IllegalArgumentException("loginId 不能为 null");
        }
        return extractId(loginId.toString());
    }

    /**
     * 从 SaToken 返回的 loginId（Object 类型）中提取用户类型
     *
     * @param loginId SaToken 返回的 loginId 对象
     * @return 用户类型常量
     * @throws IllegalArgumentException loginId 为 null 或前缀无法识别
     */
    public static int extractUserType(Object loginId) {
        if (loginId == null) {
            throw new IllegalArgumentException("loginId 不能为 null");
        }
        return extractUserType(loginId.toString());
    }

    // ==================== 判断方法（String 入参） ====================

    /**
     * 判断是否为匿名成员的 loginId
     *
     * @param loginId SaToken 的 loginId（可为 null）
     * @return true = 匿名成员
     */
    public static boolean isMember(String loginId) {
        return loginId != null && loginId.startsWith(MEMBER_PREFIX);
    }

    /**
     * 判断是否为注册用户的 loginId
     *
     * @param loginId SaToken 的 loginId（可为 null）
     * @return true = 注册用户
     */
    public static boolean isUser(String loginId) {
        return loginId != null && loginId.startsWith(USER_PREFIX);
    }

    // ==================== 判断方法（Object 入参） ====================
    // 兼容 StpUtil.getLoginIdByToken() 等返回 Object 的 SaToken API

    /**
     * 判断是否为匿名成员的 loginId
     * <p>
     * 使用示例：
     * <pre>{@code
     * Object loginId = StpUtil.getLoginIdByToken(token);
     * if (LoginIdUtil.isMember(loginId)) { ... }
     * }</pre>
     *
     * @param loginId SaToken 返回的 loginId 对象（可为 null）
     * @return true = 匿名成员
     */
    public static boolean isMember(Object loginId) {
        return loginId != null && isMember(loginId.toString());
    }

    /**
     * 判断是否为注册用户的 loginId
     *
     * @param loginId SaToken 返回的 loginId 对象（可为 null）
     * @return true = 注册用户
     */
    public static boolean isUser(Object loginId) {
        return loginId != null && isUser(loginId.toString());
    }
}
