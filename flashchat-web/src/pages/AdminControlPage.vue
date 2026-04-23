<template>
  <div class="admin-page">
    <div class="admin-shell">
      <header class="admin-hero">
        <div class="admin-toolbar">
          <button class="admin-back" type="button" @click="goRoomList">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
            <span>返回房间列表</span>
          </button>

          <transition name="admin-notice-fade">
            <div v-if="notice" class="admin-notice" :class="`is-${notice.type}`">
              {{ notice.text }}
            </div>
          </transition>
        </div>

        <div class="admin-hero-grid">
          <section class="hero-card">
            <div class="fc-kicker">管理控制台</div>
            <h1>系统运营面板</h1>
            <p>
              面向账号治理、房间处置与审计追踪的一体化工作台。
              账号列表现已支持分页，不会再停留在前几条结果。
            </p>

            <div class="identity-row">
              <span>{{ auth.identity.value?.nickname || '管理员' }}</span>
              <span>{{ auth.identity.value?.accountId || '-' }}</span>
              <span>{{ auth.identity.value?.isAdmin ? '系统管理员' : '未授权身份' }}</span>
            </div>

            <div class="tab-grid">
              <button
                v-for="tab in tabs"
                :key="tab.key"
                class="tab-chip"
                :class="{ active: activeTab === tab.key }"
                type="button"
                @click="activeTab = tab.key"
              >
                <strong>{{ tab.label }}</strong>
                <small>{{ tab.hint }}</small>
              </button>
            </div>
          </section>

          <aside class="stats-grid">
            <article class="stat-card">
              <span>账号总量</span>
              <strong>{{ accountPage.total }}</strong>
            </article>
            <article class="stat-card">
              <span>当前页管理员</span>
              <strong>{{ adminVisibleCount }}</strong>
            </article>
            <article class="stat-card">
              <span>当前页封禁</span>
              <strong>{{ bannedVisibleCount }}</strong>
            </article>
            <article class="stat-card">
              <span>日志总量</span>
              <strong>{{ logPage.total }}</strong>
            </article>
          </aside>
        </div>
      </header>

      <section v-if="booting" class="state-card">
        <div class="spinner"></div>
        <p>正在加载管理工作台...</p>
      </section>

      <section v-else-if="accessDenied" class="state-card">
        <div class="fc-kicker">权限受限</div>
        <h2>当前账号不是管理员。</h2>
        <p>请先在 `t_account.system_role` 中提权当前账号，然后重新登录。</p>
        <div class="action-row center">
          <button class="primary-btn" type="button" @click="goRoomList">返回</button>
          <button class="secondary-btn" type="button" @click="reloadWorkspace">重试</button>
        </div>
      </section>

      <template v-else>
        <section v-if="activeTab === 'overview'" class="panel-grid overview-grid">
          <article class="panel-card panel-highlight">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">总览</div>
                <h2>今日概览</h2>
              </div>
              <button class="ghost-btn" type="button" @click="reloadWorkspace">刷新</button>
            </div>

            <div class="overview-row">
              <div class="overview-tile">
                <span>账号页码</span>
                <strong>{{ accountPage.page }}</strong>
                <small>当前已加载 {{ accountPage.records.length }} 条</small>
              </div>
              <div class="overview-tile">
                <span>已加载房间</span>
                <strong>{{ roomDetail?.roomId || '-' }}</strong>
                <small>{{ roomDetail?.statusDesc || '尚未选择房间' }}</small>
              </div>
              <div class="overview-tile">
                <span>最近日志</span>
                <strong>{{ recentLogs.length }}</strong>
                <small>当前页预览</small>
              </div>
            </div>

            <div class="action-row">
              <button class="primary-btn" type="button" @click="goTab('accounts')">账号治理</button>
              <button class="secondary-btn" type="button" @click="goTab('rooms')">房间处置</button>
              <button class="secondary-btn" type="button" @click="goTab('logs')">查看日志</button>
              <button class="secondary-btn" type="button" @click="goFeedbackDesk">反馈处理台</button>
            </div>
          </article>

          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">账号速览</div>
                <h2>当前页预览</h2>
              </div>
              <button class="inline-btn" type="button" @click="goTab('accounts')">打开面板</button>
            </div>

            <div v-if="accountLoading && accountPage.records.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载账号...</p>
            </div>

            <div v-else-if="featuredAccounts.length === 0" class="list-state">
              <p>当前筛选下没有账号。</p>
            </div>

            <div v-else class="stack-list">
              <button
                v-for="item in featuredAccounts"
                :key="item.accountId"
                class="stack-item"
                type="button"
                @click="openAccountWorkspace(item.accountId)"
              >
                <div class="avatar" :style="avatarStyle(item.avatarColor)">
                  {{ initials(item.nickname || item.accountId) }}
                </div>
                <div class="stack-copy">
                  <strong>{{ item.nickname || item.accountId }}</strong>
                  <small>{{ item.accountId }}</small>
                </div>
                <div class="chip-row">
                  <span :class="statusToneClass(item.status)">{{ item.statusDesc || '-' }}</span>
                  <span :class="roleToneClass(item.systemRole)">{{ item.systemRoleDesc || '-' }}</span>
                </div>
              </button>
            </div>
          </article>

          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">最近日志</div>
                <h2>审计预览</h2>
              </div>
              <button class="inline-btn" type="button" @click="goTab('logs')">打开面板</button>
            </div>

            <div v-if="logLoading && logPage.records.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载日志...</p>
            </div>

            <div v-else-if="recentLogs.length === 0" class="list-state">
              <p>暂无日志。</p>
            </div>

            <div v-else class="log-preview-list">
              <article v-for="item in recentLogs" :key="item.id" class="log-preview">
                <div class="log-preview-head">
                  <strong>{{ item.operationTypeDesc || item.operationType }}</strong>
                  <span>{{ formatDateTime(item.createTime) }}</span>
                </div>
                <p>{{ item.targetDisplay || item.targetId || '-' }}</p>
              </article>
            </div>
          </article>
        </section>

        <section v-if="activeTab === 'accounts'" class="panel-grid two-column">
          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">账号</div>
                <h2>查询并分页浏览用户</h2>
              </div>
              <button class="ghost-btn" type="button" @click="loadAccounts(true)">刷新</button>
            </div>

            <form class="query-grid" @submit.prevent="loadAccounts(true)">
              <label class="field field-span-2">
                <span>关键词</span>
                <input
                  v-model.trim="accountQuery.keyword"
                  type="text"
                  placeholder="支持 accountId、昵称、邮箱模糊查询"
                />
              </label>

              <label class="field">
                <span>状态</span>
                <select v-model="accountQuery.status">
                  <option value="">全部</option>
                  <option value="1">正常</option>
                  <option value="0">封禁</option>
                </select>
              </label>

              <label class="field">
                <span>角色</span>
                <select v-model="accountQuery.systemRole">
                  <option value="">全部</option>
                  <option value="1">管理员</option>
                  <option value="0">普通用户</option>
                </select>
              </label>

              <label class="field">
                <span>每页条数</span>
                <select v-model="accountQuery.size" @change="changeAccountPageSize">
                  <option :value="12">12</option>
                  <option :value="20">20</option>
                  <option :value="50">50</option>
                </select>
              </label>

              <div class="query-actions">
                <button class="primary-btn" type="submit" :disabled="accountLoading">查询</button>
                <button class="secondary-btn" type="button" @click="resetAccountFilters">重置</button>
              </div>
            </form>

            <div v-if="accountLoading && accountPage.records.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载账号...</p>
            </div>

            <div v-else-if="accountPage.records.length === 0" class="list-state">
              <p>当前筛选下没有匹配账号。</p>
            </div>

            <div v-else class="account-list">
              <button
                v-for="item in accountPage.records"
                :key="item.accountId"
                class="account-row"
                :class="{ active: selectedAccount?.accountId === item.accountId }"
                type="button"
                @click="selectAccount(item.accountId)"
              >
                <div class="avatar" :style="avatarStyle(item.avatarColor)">
                  {{ initials(item.nickname || item.accountId) }}
                </div>
                <div class="account-copy">
                  <div class="account-title">
                    <strong>{{ item.nickname || item.accountId }}</strong>
                    <span>{{ item.accountId }}</span>
                  </div>
                  <div class="account-meta">
                    <span>{{ item.email || '未绑定邮箱' }}</span>
                    <span>积分 {{ safeNumber(item.credits) }}</span>
                  </div>
                </div>
                <div class="chip-row">
                  <span :class="statusToneClass(item.status)">{{ item.statusDesc || '-' }}</span>
                  <span :class="roleToneClass(item.systemRole)">{{ item.systemRoleDesc || '-' }}</span>
                </div>
              </button>
            </div>

            <div class="pager">
              <button class="secondary-btn" type="button" :disabled="accountLoading || !canGoPrevAccountPage" @click="changeAccountPage(-1)">
                上一页
              </button>
              <span>第 {{ accountPage.page }} / {{ accountTotalPages }} 页，共 {{ accountPage.total }} 条</span>
              <button class="secondary-btn" type="button" :disabled="accountLoading || !canGoNextAccountPage" @click="changeAccountPage(1)">
                下一页
              </button>
            </div>
          </article>

          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">账号详情</div>
                <h2>信息与操作</h2>
              </div>
            </div>

            <div v-if="accountDetailLoading && !selectedAccount" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载账号详情...</p>
            </div>

            <div v-else-if="!selectedAccount" class="list-state">
              <p>请先从左侧选择一个账号，再查看详情或执行处置。</p>
            </div>

            <template v-else>
              <div class="detail-hero">
                <div class="avatar avatar-large" :style="avatarStyle(selectedAccount.avatarColor)">
                  {{ initials(selectedAccount.nickname || selectedAccount.accountId) }}
                </div>
                <div class="detail-copy">
                  <h3>{{ selectedAccount.nickname || selectedAccount.accountId }}</h3>
                  <p>{{ selectedAccount.accountId }} / {{ selectedAccount.email || '未绑定邮箱' }}</p>
                  <div class="chip-row">
                    <span :class="statusToneClass(selectedAccount.status)">{{ selectedAccount.statusDesc || '-' }}</span>
                    <span :class="roleToneClass(selectedAccount.systemRole)">{{ selectedAccount.systemRoleDesc || '-' }}</span>
                    <span class="chip-neutral">积分 {{ safeNumber(selectedAccount.credits) }}</span>
                  </div>
                </div>
              </div>

              <div class="tile-grid">
                <div class="info-tile">
                  <span>注册状态</span>
                  <strong>{{ selectedAccount.isRegistered ? '已注册' : '游客' }}</strong>
                </div>
                <div class="info-tile">
                  <span>最后活跃</span>
                  <strong>{{ formatDateTime(selectedAccount.lastActiveTime) }}</strong>
                </div>
                <div class="info-tile">
                  <span>创建时间</span>
                  <strong>{{ formatDateTime(selectedAccount.createTime) }}</strong>
                </div>
              </div>

              <section class="sub-card">
                <div class="sub-head">
                  <div class="fc-section-label">账号操作</div>
                  <span>所有治理动作都必须填写原因。</span>
                </div>
                <textarea
                  v-model.trim="accountReason"
                  class="textarea"
                  rows="4"
                  placeholder="请输入本次操作原因"
                ></textarea>

                <div class="action-grid">
                  <button class="danger-btn" type="button" :disabled="accountActionLoading" @click="runAccountAction('ban')">封禁</button>
                  <button class="secondary-btn" type="button" :disabled="accountActionLoading" @click="runAccountAction('unban')">解封</button>
                  <button class="secondary-btn" type="button" :disabled="accountActionLoading" @click="runAccountAction('kickout')">踢下线</button>
                  <button class="secondary-btn" type="button" :disabled="accountActionLoading" @click="runAccountAction('grantAdmin')">授予管理员</button>
                  <button class="secondary-btn" type="button" :disabled="accountActionLoading" @click="runAccountAction('revokeAdmin')">撤销管理员</button>
                </div>
              </section>

              <section class="sub-card">
                <div class="sub-head">
                  <div class="fc-section-label">积分调整</div>
                  <span>通过管理员积分流水处理，不直接修改裸余额。</span>
                </div>

                <div class="query-grid compact">
                  <label class="field">
                    <span>调整方向</span>
                    <select v-model="creditForm.direction">
                      <option value="1">增加</option>
                      <option value="-1">减少</option>
                    </select>
                  </label>

                  <label class="field">
                    <span>数量</span>
                    <input v-model.trim="creditForm.amount" type="number" min="1" placeholder="请输入正整数" />
                  </label>

                  <label class="field field-span-2">
                    <span>原因</span>
                    <textarea
                      v-model.trim="creditForm.reason"
                      class="textarea"
                      rows="3"
                      placeholder="请输入积分调整原因"
                    ></textarea>
                  </label>
                </div>

                <button class="primary-btn" type="button" :disabled="creditLoading" @click="runCreditAdjust">
                  提交积分调整
                </button>
              </section>
            </template>
          </article>
        </section>

        <section v-if="activeTab === 'rooms'" class="panel-grid room-grid">
          <article class="panel-card room-list-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">房间列表</div>
                <h2>筛选并选择房间</h2>
              </div>
              <button class="ghost-btn" type="button" @click="loadRoomList(true)">刷新</button>
            </div>

            <form class="query-grid" @submit.prevent="loadRoomList(true)">
              <label class="field field-span-2">
                <span>关键词</span>
                <input
                  v-model.trim="roomQuery.keyword"
                  type="text"
                  placeholder="支持房间 ID 或标题模糊查询"
                />
              </label>

              <label class="field">
                <span>状态</span>
                <select v-model="roomQuery.status">
                  <option value="">全部</option>
                  <option value="0">等待中</option>
                  <option value="1">活跃</option>
                  <option value="2">即将到期</option>
                  <option value="3">宽限期</option>
                  <option value="4">已关闭</option>
                </select>
              </label>

              <label class="field">
                <span>可见性</span>
                <select v-model="roomQuery.isPublic">
                  <option value="">全部</option>
                  <option value="1">公开</option>
                  <option value="0">私密</option>
                </select>
              </label>

              <label class="field">
                <span>每页条数</span>
                <select v-model="roomQuery.size" @change="changeRoomPageSize">
                  <option :value="12">12</option>
                  <option :value="20">20</option>
                  <option :value="50">50</option>
                </select>
              </label>

              <div class="query-actions">
                <button class="primary-btn" type="submit" :disabled="roomListLoading">查询</button>
                <button class="secondary-btn" type="button" @click="resetRoomFilters">重置</button>
              </div>
            </form>

            <div v-if="roomListLoading && roomPage.records.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载房间列表...</p>
            </div>

            <div v-else-if="roomPage.records.length === 0" class="list-state">
              <p>当前筛选下没有匹配房间。</p>
            </div>

            <div v-else class="room-list">
              <button
                v-for="item in roomPage.records"
                :key="item.roomId"
                class="room-row"
                :class="{ active: roomLookup === item.roomId }"
                type="button"
                @click="loadRoomWorkspace(item.roomId)"
              >
                <div class="avatar" :style="avatarStyle('#c8956c')">
                  {{ initials(item.title || item.roomId) }}
                </div>
                <div class="room-copy">
                  <div class="room-title">
                    <strong>{{ item.title || item.roomId }}</strong>
                    <span>{{ item.roomId }}</span>
                  </div>
                  <div class="room-meta">
                    <span>成员 {{ safeNumber(item.memberCount) }} / {{ safeNumber(item.maxMembers) }}</span>
                    <span>在线 {{ safeNumber(item.onlineCount) }}</span>
                  </div>
                </div>
                <div class="chip-row">
                  <span :class="roomStatusToneClass(item.status)">{{ item.statusDesc || '-' }}</span>
                  <span class="chip-neutral">{{ item.visibilityDesc || (Number(item.isPublic) === 1 ? '公开' : '私密') }}</span>
                </div>
              </button>
            </div>

            <div class="pager">
              <button class="secondary-btn" type="button" :disabled="roomListLoading || !canGoPrevRoomPage" @click="changeRoomPage(-1)">
                上一页
              </button>
              <span>第 {{ roomPage.page }} / {{ roomTotalPages }} 页，共 {{ roomPage.total }} 条</span>
              <button class="secondary-btn" type="button" :disabled="roomListLoading || !canGoNextRoomPage" @click="changeRoomPage(1)">
                下一页
              </button>
            </div>
          </article>

          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">房间详情</div>
                <h2>信息、消息与处置</h2>
              </div>
              <button class="danger-btn" type="button" :disabled="roomActionLoading || !roomDetail" @click="runRoomAction('close')">强制关房</button>
            </div>

            <div v-if="roomLoading && !roomDetail" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载房间详情...</p>
            </div>

            <div v-else-if="!roomDetail" class="list-state">
              <p>从左侧列表选择房间后，即可查看详情、最近消息和成员列表。</p>
            </div>

            <template v-else>
              <div class="detail-hero">
                <div class="avatar avatar-large" :style="avatarStyle('#c8956c')">
                  {{ initials(roomDetail.title || roomDetail.roomId) }}
                </div>
                <div class="detail-copy">
                  <h3>{{ roomDetail.title || roomDetail.roomId }}</h3>
                  <p>{{ roomDetail.roomId }} / {{ roomDetail.statusDesc || '-' }}</p>
                  <div class="chip-row">
                    <span class="chip-neutral">在线 {{ safeNumber(roomDetail.onlineCount) }}</span>
                    <span class="chip-neutral">成员 {{ safeNumber(roomDetail.memberCount) }}</span>
                    <span class="chip-neutral">上限 {{ safeNumber(roomDetail.maxMembers) }}</span>
                  </div>
                </div>
              </div>

              <div class="tile-grid">
                <div class="info-tile">
                  <span>状态</span>
                  <strong>{{ roomDetail.statusDesc || '-' }}</strong>
                </div>
                <div class="info-tile">
                  <span>到期时间</span>
                  <strong>{{ formatDateTime(roomDetail.expireTime) }}</strong>
                </div>
                <div class="info-tile">
                  <span>创建时间</span>
                  <strong>{{ formatDateTime(roomDetail.createTime) }}</strong>
                </div>
              </div>

              <section class="sub-card">
                <div class="sub-head">
                  <div class="fc-section-label">处置原因</div>
                  <span>关房、踢人、禁言都需要填写。</span>
                </div>
                <textarea
                  v-model.trim="roomReason"
                  class="textarea"
                  rows="3"
                  placeholder="请输入本次房间处置原因"
                ></textarea>
              </section>

              <section class="sub-card">
                <div class="sub-head">
                  <div class="fc-section-label">最近消息</div>
                  <span>当前房间的快速预览。</span>
                </div>

                <div v-if="recentMessages.length === 0" class="list-state compact">
                  <p>暂无预览消息。</p>
                </div>

                <div v-else class="message-list">
                  <article v-for="item in recentMessages" :key="item.indexId || `${item.senderId}-${item.timestamp}`" class="message-card">
                    <strong>{{ item.username || item.senderId || '未知用户' }}</strong>
                    <p>{{ item.content || '-' }}</p>
                    <span>{{ formatTimestamp(item.timestamp) }}</span>
                  </article>
                </div>
              </section>
            </template>
          </article>

          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">成员列表</div>
                <h2>踢人与禁言</h2>
              </div>
            </div>

            <div v-if="roomLoading && roomMembers.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载成员列表...</p>
            </div>

            <div v-else-if="roomMembers.length === 0" class="list-state">
              <p>选择房间后可查看成员列表。</p>
            </div>

            <div v-else class="member-list">
              <article v-for="member in roomMembers" :key="member.accountId" class="member-card">
                <div class="member-main">
                  <img
                    v-if="isImageAvatar(member.avatar)"
                    class="member-avatar member-avatar-image"
                    :src="member.avatar"
                    alt=""
                  />
                  <div v-else class="member-avatar" :style="avatarStyle(member.avatar)">
                    {{ initials(member.nickname || String(member.accountId)) }}
                  </div>

                  <div class="member-copy">
                    <strong>{{ member.nickname || `成员 ${member.accountId}` }}</strong>
                    <small>
                      ID {{ member.accountId }} /
                      {{ member.isHost ? '房主' : '成员' }} /
                      {{ member.isOnline ? '在线' : '离线' }}
                    </small>
                  </div>
                </div>

                <div class="member-side">
                  <div class="chip-row">
                    <span :class="member.isMuted ? 'chip-danger' : 'chip-neutral'">
                      {{ member.isMuted ? '已禁言' : '可发言' }}
                    </span>
                  </div>

                  <div class="action-row">
                    <button class="secondary-btn" type="button" :disabled="roomActionLoading" @click="runRoomAction('kick', member.accountId)">踢出</button>
                    <button
                      v-if="!member.isMuted"
                      class="secondary-btn"
                      type="button"
                      :disabled="roomActionLoading"
                      @click="runRoomAction('mute', member.accountId)"
                    >
                      禁言
                    </button>
                    <button
                      v-else
                      class="secondary-btn"
                      type="button"
                      :disabled="roomActionLoading"
                      @click="runRoomAction('unmute', member.accountId)"
                    >
                      解除禁言
                    </button>
                  </div>
                </div>
              </article>
            </div>
          </article>
        </section>

        <section v-if="activeTab === 'logs'" class="panel-grid single-column">
          <article class="panel-card">
            <div class="panel-head">
              <div>
                <div class="fc-kicker">操作日志</div>
                <h2>审计追踪</h2>
              </div>
              <button class="ghost-btn" type="button" @click="loadLogs(true)">刷新</button>
            </div>

            <form class="query-grid logs" @submit.prevent="loadLogs(true)">
              <label class="field">
                <span>操作人</span>
                <input v-model.trim="logQuery.operatorAccountId" type="text" placeholder="例如 FC-ADMIN01" />
              </label>

              <label class="field">
                <span>操作类型</span>
                <input v-model.trim="logQuery.operationType" type="text" placeholder="例如 ACCOUNT_BAN" />
              </label>

              <label class="field">
                <span>目标类型</span>
                <input v-model.trim="logQuery.targetType" type="text" placeholder="ACCOUNT / ROOM" />
              </label>

              <label class="field">
                <span>目标标识</span>
                <input v-model.trim="logQuery.targetId" type="text" placeholder="账号 ID 或房间 ID" />
              </label>

              <div class="query-actions">
                <button class="primary-btn" type="submit" :disabled="logLoading">查询</button>
                <button class="secondary-btn" type="button" @click="resetLogFilters">重置</button>
              </div>
            </form>

            <div v-if="logLoading && logPage.records.length === 0" class="list-state">
              <div class="spinner small"></div>
              <p>正在加载日志...</p>
            </div>

            <div v-else-if="logPage.records.length === 0" class="list-state">
              <p>当前筛选下没有匹配日志。</p>
            </div>

            <div v-else class="log-list">
              <article v-for="item in logPage.records" :key="item.id" class="log-card">
                <div class="log-top">
                  <div>
                    <strong>{{ item.operationTypeDesc || item.operationType }}</strong>
                    <p>{{ item.targetTypeDesc || item.targetType }} / {{ item.targetDisplay || item.targetId || '-' }}</p>
                  </div>
                  <span>{{ formatDateTime(item.createTime) }}</span>
                </div>

                <div class="log-meta">
                  <span>操作人 {{ item.operatorAccountId || item.operatorId || '-' }}</span>
                  <span>目标 {{ item.targetId || '-' }}</span>
                </div>

                <div class="log-reason">{{ item.reason || '未记录原因' }}</div>

                <pre v-if="item.detailJson" class="log-detail">{{ formatDetailJson(item.detailJson) }}</pre>
              </article>
            </div>

            <div class="pager">
              <button class="secondary-btn" type="button" :disabled="logLoading || !canGoPrevLogPage" @click="changeLogPage(-1)">
                上一页
              </button>
              <span>第 {{ logPage.page }} / {{ logTotalPages }} 页</span>
              <button class="secondary-btn" type="button" :disabled="logLoading || !canGoNextLogPage" @click="changeLogPage(1)">
                下一页
              </button>
            </div>
          </article>
        </section>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import {
  adjustAdminCredits,
  banAdminAccount,
  closeAdminRoom,
  getAdminAccountDetail,
  getAdminRoomDetail,
  getAdminRoomMembers,
  grantAdminRole,
  kickAdminRoomMember,
  kickoutAdminAccount,
  muteAdminRoomMember,
  revokeAdminRole,
  searchAdminAccounts,
  searchAdminRooms,
  searchAdminLogs,
  unbanAdminAccount,
  unmuteAdminRoomMember
} from '@/api/admin'

const router = useRouter()
const auth = useAuth()

const tabs = [
  { key: 'overview', label: '总览', hint: '概况与快捷入口' },
  { key: 'accounts', label: '账号', hint: '查询、分页与治理' },
  { key: 'rooms', label: '房间', hint: '查询、禁言、踢人、关房' },
  { key: 'logs', label: '日志', hint: '审计记录与筛选' }
]

const activeTab = ref('overview')
const booting = ref(true)
const accessDenied = ref(false)
const notice = ref(null)

const accountQuery = ref({
  keyword: '',
  status: '',
  systemRole: '',
  page: 1,
  size: 20
})
const accountPage = ref(normalizePage(null, 1, 20))
const accountLoading = ref(false)
const accountDetailLoading = ref(false)
const accountActionLoading = ref(false)
const creditLoading = ref(false)
const selectedAccount = ref(null)
const accountReason = ref('')
const creditForm = ref({
  direction: '1',
  amount: '',
  reason: ''
})

const roomQuery = ref({
  keyword: '',
  status: '',
  isPublic: '',
  page: 1,
  size: 12
})
const roomPage = ref(normalizePage(null, 1, 12))
const roomListLoading = ref(false)
const roomLookup = ref('')
const roomReason = ref('')
const roomLoading = ref(false)
const roomActionLoading = ref(false)
const roomDetail = ref(null)
const roomMembers = ref([])

const logQuery = ref({
  operatorAccountId: '',
  operationType: '',
  targetType: '',
  targetId: '',
  page: 1,
  size: 10
})
const logPage = ref(normalizePage(null, 1, 10))
const logLoading = ref(false)

const featuredAccounts = computed(() => accountPage.value.records.slice(0, 4))
const recentLogs = computed(() => logPage.value.records.slice(0, 5))
const recentMessages = computed(() => {
  return Array.isArray(roomDetail.value?.recentMessages) ? roomDetail.value.recentMessages : []
})
const adminVisibleCount = computed(() => accountPage.value.records.filter(item => Number(item.systemRole) === 1).length)
const bannedVisibleCount = computed(() => accountPage.value.records.filter(item => Number(item.status) === 0).length)
const canGoPrevAccountPage = computed(() => Number(accountPage.value.page || 1) > 1)
const canGoNextAccountPage = computed(() => {
  return Number(accountPage.value.page || 1) * Number(accountPage.value.size || 20) < Number(accountPage.value.total || 0)
})
const accountTotalPages = computed(() => {
  const total = Number(accountPage.value.total || 0)
  const size = Math.max(Number(accountPage.value.size || 20), 1)
  return Math.max(1, Math.ceil(total / size))
})
const canGoPrevRoomPage = computed(() => Number(roomPage.value.page || 1) > 1)
const canGoNextRoomPage = computed(() => {
  return Number(roomPage.value.page || 1) * Number(roomPage.value.size || 12) < Number(roomPage.value.total || 0)
})
const roomTotalPages = computed(() => {
  const total = Number(roomPage.value.total || 0)
  const size = Math.max(Number(roomPage.value.size || 12), 1)
  return Math.max(1, Math.ceil(total / size))
})
const canGoPrevLogPage = computed(() => Number(logPage.value.page || 1) > 1)
const canGoNextLogPage = computed(() => {
  return Number(logPage.value.page || 1) * Number(logPage.value.size || 10) < Number(logPage.value.total || 0)
})
const logTotalPages = computed(() => {
  const total = Number(logPage.value.total || 0)
  const size = Math.max(Number(logPage.value.size || 10), 1)
  return Math.max(1, Math.ceil(total / size))
})

onMounted(async () => {
  await bootWorkspace()
})

onUnmounted(() => {
  window.clearTimeout(showNotice.timer)
})

async function bootWorkspace() {
  booting.value = true
  accessDenied.value = false

  if (!auth.hasToken()) {
    router.replace({ name: 'Welcome' })
    return
  }

  const valid = await auth.checkOnly()
  if (!valid) {
    router.replace({ name: 'Welcome' })
    return
  }

  if (!auth.identity.value?.isAdmin) {
    accessDenied.value = true
    booting.value = false
    return
  }

  try {
    await Promise.all([
      loadAccounts(true),
      loadRoomList(true),
      loadLogs(true)
    ])
  } finally {
    booting.value = false
  }
}

function goRoomList() {
  router.push({ name: 'Chat', query: { view: 'rooms' } })
}

function goTab(key) {
  activeTab.value = key
  if (key === 'rooms' && roomPage.value.records.length === 0 && !roomListLoading.value) {
    loadRoomList(true)
  }
}

function goFeedbackDesk() {
  router.push({ name: 'AdminFeedbacks' })
}

async function reloadWorkspace() {
  await bootWorkspace()
}

async function loadAccounts(reset = false) {
  if (accountLoading.value && !reset) return
  if (reset) accountQuery.value.page = 1

  accountLoading.value = true
  try {
    const page = normalizePage(
      await searchAdminAccounts(buildAccountQuery()),
      accountQuery.value.page,
      accountQuery.value.size
    )
    accountPage.value = page

    if (!page.records.length) {
      selectedAccount.value = null
      return
    }

    const currentId = selectedAccount.value?.accountId
    const nextId = page.records.some(item => item.accountId === currentId)
      ? currentId
      : page.records[0].accountId

    await selectAccount(nextId)
  } catch (error) {
    showNotice(error?.message || '加载账号失败', 'error')
  } finally {
    accountLoading.value = false
  }
}

async function changeAccountPage(direction) {
  const nextPage = Number(accountQuery.value.page || 1) + direction
  if (nextPage < 1) return
  accountQuery.value.page = nextPage
  await loadAccounts(false)
}

async function changeAccountPageSize() {
  accountQuery.value.page = 1
  await loadAccounts(false)
}

async function loadRoomList(reset = false) {
  if (roomListLoading.value && !reset) return
  if (reset) roomQuery.value.page = 1

  roomListLoading.value = true
  try {
    const page = normalizePage(
      await searchAdminRooms(buildRoomQuery()),
      roomQuery.value.page,
      roomQuery.value.size
    )
    roomPage.value = page

    if (!page.records.length) {
      roomLookup.value = ''
      roomDetail.value = null
      roomMembers.value = []
      return
    }

    const nextRoomId = page.records.some(item => item.roomId === roomLookup.value)
      ? roomLookup.value
      : page.records[0].roomId

    await loadRoomWorkspace(nextRoomId)
  } catch (error) {
    showNotice(error?.message || '加载房间列表失败', 'error')
  } finally {
    roomListLoading.value = false
  }
}

async function changeRoomPage(direction) {
  const nextPage = Number(roomQuery.value.page || 1) + direction
  if (nextPage < 1) return
  roomQuery.value.page = nextPage
  await loadRoomList(false)
}

async function changeRoomPageSize() {
  roomQuery.value.page = 1
  await loadRoomList(false)
}

async function selectAccount(accountId) {
  if (!accountId) return
  accountDetailLoading.value = true
  try {
    selectedAccount.value = await getAdminAccountDetail(accountId)
  } catch (error) {
    showNotice(error?.message || '加载账号详情失败', 'error')
  } finally {
    accountDetailLoading.value = false
  }
}

async function openAccountWorkspace(accountId) {
  activeTab.value = 'accounts'
  await selectAccount(accountId)
}

async function runAccountAction(action) {
  if (!selectedAccount.value?.accountId) {
    showNotice('请先选择账号', 'error')
    return
  }

  const reason = accountReason.value.trim()
  if (!reason) {
    showNotice('请先填写原因', 'error')
    return
  }

  const actionMap = {
    ban: { request: banAdminAccount, success: '账号已封禁' },
    unban: { request: unbanAdminAccount, success: '账号已解封' },
    kickout: { request: kickoutAdminAccount, success: '账号已踢下线' },
    grantAdmin: { request: grantAdminRole, success: '已授予管理员权限' },
    revokeAdmin: { request: revokeAdminRole, success: '已撤销管理员权限' }
  }

  const currentAction = actionMap[action]
  if (!currentAction) return

  accountActionLoading.value = true
  try {
    await currentAction.request(selectedAccount.value.accountId, { reason })
    accountReason.value = ''
    showNotice(currentAction.success, 'success')
    await loadAccounts(false)
  } catch (error) {
    showNotice(error?.message || '账号操作失败', 'error')
  } finally {
    accountActionLoading.value = false
  }
}

async function runCreditAdjust() {
  if (!selectedAccount.value?.accountId) {
    showNotice('请先选择账号', 'error')
    return
  }

  const amount = Number(creditForm.value.amount)
  const reason = creditForm.value.reason.trim()
  if (!Number.isInteger(amount) || amount <= 0) {
    showNotice('数量必须是正整数', 'error')
    return
  }
  if (!reason) {
    showNotice('请先填写原因', 'error')
    return
  }

  creditLoading.value = true
  try {
    await adjustAdminCredits(selectedAccount.value.accountId, {
      amount,
      direction: Number(creditForm.value.direction),
      reason
    })
    creditForm.value = {
      direction: '1',
      amount: '',
      reason: ''
    }
    showNotice('积分调整已提交', 'success')
    await loadAccounts(false)
  } catch (error) {
    showNotice(error?.message || '积分调整失败', 'error')
  } finally {
    creditLoading.value = false
  }
}

async function loadRoomWorkspace(targetRoomId = roomLookup.value.trim()) {
  const roomId = String(targetRoomId || '').trim()
  if (!roomId) {
    showNotice('请先选择房间', 'error')
    return
  }

  roomLookup.value = roomId
  roomLoading.value = true
  try {
    const [detail, members] = await Promise.all([
      getAdminRoomDetail(roomId),
      getAdminRoomMembers(roomId)
    ])
    roomDetail.value = detail
    roomMembers.value = Array.isArray(members) ? members : []
    showNotice('房间信息已加载', 'success')
  } catch (error) {
    showNotice(error?.message || '加载房间失败', 'error')
  } finally {
    roomLoading.value = false
  }
}

async function runRoomAction(action, memberAccountId = null) {
  if (!roomDetail.value?.roomId) {
    showNotice('请先查询房间', 'error')
    return
  }

  const reason = roomReason.value.trim()
  if (!reason) {
    showNotice('请先填写原因', 'error')
    return
  }

  const roomId = roomDetail.value.roomId
  roomActionLoading.value = true
  try {
    if (action === 'close') {
      await closeAdminRoom(roomId, { reason })
      showNotice('房间已关闭', 'success')
    } else if (action === 'kick') {
      await kickAdminRoomMember(roomId, memberAccountId, { reason })
      showNotice('成员已踢出', 'success')
    } else if (action === 'mute') {
      await muteAdminRoomMember(roomId, memberAccountId, { reason })
      showNotice('成员已禁言', 'success')
    } else if (action === 'unmute') {
      await unmuteAdminRoomMember(roomId, memberAccountId, { reason })
      showNotice('成员已解除禁言', 'success')
    }

    roomReason.value = ''
    await loadRoomList(false)
  } catch (error) {
    showNotice(error?.message || '房间操作失败', 'error')
  } finally {
    roomActionLoading.value = false
  }
}

async function loadLogs(reset = false) {
  if (logLoading.value && !reset) return
  if (reset) logQuery.value.page = 1

  logLoading.value = true
  try {
    logPage.value = normalizePage(
      await searchAdminLogs(buildLogQuery()),
      logQuery.value.page,
      logQuery.value.size
    )
  } catch (error) {
    showNotice(error?.message || '加载日志失败', 'error')
  } finally {
    logLoading.value = false
  }
}

async function changeLogPage(direction) {
  const nextPage = Number(logQuery.value.page || 1) + direction
  if (nextPage < 1) return
  logQuery.value.page = nextPage
  await loadLogs(false)
}

function resetAccountFilters() {
  accountQuery.value = {
    keyword: '',
    status: '',
    systemRole: '',
    page: 1,
    size: 20
  }
  loadAccounts(true)
}

function resetRoomFilters() {
  roomQuery.value = {
    keyword: '',
    status: '',
    isPublic: '',
    page: 1,
    size: 12
  }
  loadRoomList(true)
}

function resetLogFilters() {
  logQuery.value = {
    operatorAccountId: '',
    operationType: '',
    targetType: '',
    targetId: '',
    page: 1,
    size: 10
  }
  loadLogs(true)
}

function buildAccountQuery() {
  const params = {
    page: Number(accountQuery.value.page || 1),
    size: Number(accountQuery.value.size || 20)
  }

  if (accountQuery.value.keyword) params.keyword = accountQuery.value.keyword
  if (accountQuery.value.status !== '') params.status = Number(accountQuery.value.status)
  if (accountQuery.value.systemRole !== '') params.systemRole = Number(accountQuery.value.systemRole)
  return params
}

function buildRoomQuery() {
  const params = {
    page: Number(roomQuery.value.page || 1),
    size: Number(roomQuery.value.size || 12)
  }

  if (roomQuery.value.keyword) params.keyword = roomQuery.value.keyword
  if (roomQuery.value.status !== '') params.status = Number(roomQuery.value.status)
  if (roomQuery.value.isPublic !== '') params.isPublic = Number(roomQuery.value.isPublic)
  return params
}

function buildLogQuery() {
  const params = {
    page: Number(logQuery.value.page || 1),
    size: Number(logQuery.value.size || 10)
  }

  if (logQuery.value.operatorAccountId) params.operatorAccountId = logQuery.value.operatorAccountId
  if (logQuery.value.operationType) params.operationType = logQuery.value.operationType
  if (logQuery.value.targetType) params.targetType = logQuery.value.targetType
  if (logQuery.value.targetId) params.targetId = logQuery.value.targetId
  return params
}

function normalizePage(data = null, page = 1, size = 20) {
  return {
    page: Number(data?.page || page),
    size: Number(data?.size || size),
    total: Number(data?.total || 0),
    records: Array.isArray(data?.records) ? data.records : []
  }
}

function showNotice(text, type = 'info') {
  notice.value = { text, type }
  window.clearTimeout(showNotice.timer)
  showNotice.timer = window.setTimeout(() => {
    notice.value = null
  }, 2400)
}
showNotice.timer = null

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

function formatTimestamp(value) {
  if (!value && value !== 0) return '-'
  const date = new Date(Number(value))
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

function formatDetailJson(value) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function safeNumber(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

function initials(value) {
  const text = String(value || '?').trim()
  return (text[0] || '?').toUpperCase()
}

function avatarStyle(value) {
  return {
    background: value && String(value).startsWith('#') ? value : '#c8956c'
  }
}

function isImageAvatar(value) {
  return Boolean(value) && !String(value).startsWith('#')
}

function statusToneClass(status) {
  return Number(status) === 0 ? 'chip-danger' : 'chip-success'
}

function roleToneClass(systemRole) {
  return Number(systemRole) === 1 ? 'chip-admin' : 'chip-neutral'
}

function roomStatusToneClass(status) {
  if (Number(status) === 1) return 'chip-success'
  if (Number(status) === 4) return 'chip-danger'
  return 'chip-neutral'
}
</script>

<style scoped>
.admin-page {
  min-height: 100vh;
  padding: 20px 18px 28px;
  background: var(--fc-app-gradient);
}

.admin-shell {
  max-width: 1320px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 28px;
  background:
    radial-gradient(circle at top left, rgba(182, 118, 57, 0.08), transparent 38%),
    linear-gradient(180deg, rgba(255, 253, 249, 0.94) 0%, rgba(250, 245, 238, 0.94) 100%);
  box-shadow: 0 20px 44px rgba(33, 26, 20, 0.08);
}

.admin-toolbar,
.admin-hero-grid,
.panel-grid,
.panel-head,
.query-actions,
.detail-hero,
.tile-grid,
.action-row,
.action-grid,
.member-main,
.member-side,
.log-top,
.log-meta,
.pager {
  display: flex;
}

.admin-toolbar,
.panel-head,
.log-top,
.pager {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-back,
.primary-btn,
.secondary-btn,
.danger-btn,
.ghost-btn,
.inline-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 10px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    color var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.admin-back,
.secondary-btn,
.ghost-btn {
  background: rgba(255, 255, 255, 0.82);
  color: var(--fc-text);
}

.admin-back svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.primary-btn {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 12px 22px rgba(151, 90, 38, 0.16);
}

.danger-btn {
  background: var(--fc-danger);
  border-color: transparent;
  color: #fff9f6;
  box-shadow: 0 12px 22px rgba(184, 96, 75, 0.14);
}

.inline-btn {
  min-height: 34px;
  padding: 8px 0;
  border: none;
  background: none;
  color: var(--fc-accent-strong);
}

.admin-back:hover,
.secondary-btn:hover,
.ghost-btn:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.primary-btn:hover,
.danger-btn:hover {
  transform: translateY(-1px);
}

.admin-notice {
  padding: 9px 12px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.9);
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text);
}

.admin-notice.is-success { color: var(--fc-success); }
.admin-notice.is-error { color: var(--fc-danger); }
.admin-notice.is-info { color: var(--fc-text); }

.admin-notice-fade-enter-active,
.admin-notice-fade-leave-active {
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.admin-notice-fade-enter-from,
.admin-notice-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.admin-hero-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 14px;
}

.hero-card,
.stat-card,
.panel-card,
.state-card,
.account-row,
.stack-item,
.message-card,
.member-card,
.log-card,
.log-preview {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.84);
}

.hero-card {
  padding: 24px;
}

.hero-card h1 {
  margin: 10px 0 6px;
  font-family: var(--fc-font-display);
  font-size: clamp(32px, 4vw, 44px);
  line-height: 1.04;
  font-weight: 600;
  color: var(--fc-text);
}

.hero-card p {
  margin: 0;
  max-width: 760px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.identity-row,
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.identity-row {
  margin-top: 16px;
}

.identity-row span,
.chip-success,
.chip-danger,
.chip-admin,
.chip-neutral {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}

.identity-row span,
.chip-neutral {
  background: rgba(255, 255, 255, 0.88);
  color: var(--fc-text-sec);
}

.chip-success {
  background: rgba(84, 120, 76, 0.12);
  border-color: rgba(84, 120, 76, 0.18);
  color: var(--fc-success);
}

.chip-danger {
  background: rgba(184, 96, 75, 0.1);
  border-color: rgba(184, 96, 75, 0.16);
  color: var(--fc-danger);
}

.chip-admin {
  background: rgba(182, 118, 57, 0.12);
  border-color: rgba(182, 118, 57, 0.18);
  color: var(--fc-accent-strong);
}

.tab-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.tab-chip {
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.74);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.tab-chip strong {
  display: block;
  font-family: var(--fc-font-display);
  font-size: 18px;
  line-height: 1.15;
  color: var(--fc-text);
}

.tab-chip small {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--fc-text-muted);
}

.tab-chip:hover,
.tab-chip.active {
  border-color: var(--fc-selected-border);
  background: var(--fc-selected-bg);
  box-shadow: var(--fc-selected-shadow);
  transform: translateY(-1px);
}

.stats-grid {
  display: grid;
  gap: 12px;
}

.stat-card {
  padding: 18px;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.stat-card span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.stat-card strong {
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 1;
  font-weight: 600;
  color: var(--fc-text);
}

.panel-grid {
  margin-top: 16px;
  display: grid;
  gap: 14px;
}

.overview-grid {
  grid-template-columns: 1.2fr 0.9fr;
}

.overview-grid > :first-child {
  grid-column: 1 / -1;
}

.two-column {
  grid-template-columns: minmax(340px, 0.95fr) minmax(420px, 1.05fr);
}

.room-grid {
  grid-template-columns: 360px minmax(0, 1fr);
}

.room-grid > :first-child {
  grid-row: 1 / span 2;
}

.single-column {
  grid-template-columns: 1fr;
}

.panel-card,
.state-card {
  padding: 22px;
}

.panel-highlight {
  background:
    linear-gradient(135deg, rgba(255, 250, 243, 0.96), rgba(255, 255, 255, 0.9)),
    rgba(255, 255, 255, 0.9);
}

.panel-head h2,
.state-card h2,
.detail-copy h3 {
  margin: 8px 0 0;
  font-family: var(--fc-font-display);
  font-size: 26px;
  line-height: 1.08;
  font-weight: 600;
  color: var(--fc-text);
}

.state-card {
  margin-top: 16px;
  text-align: center;
}

.state-card p,
.detail-copy p,
.message-card p,
.log-reason,
.log-preview p {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.center {
  justify-content: center;
}

.overview-row,
.tile-grid {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.overview-tile,
.info-tile {
  padding: 16px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 20px;
  background: rgba(255, 253, 249, 0.88);
}

.overview-tile span,
.info-tile span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.overview-tile strong,
.info-tile strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 28px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.overview-tile small {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

.list-state {
  padding: 28px 18px;
  text-align: center;
}

.list-state.compact {
  padding: 16px;
}

.list-state p {
  margin: 0;
  color: var(--fc-text-sec);
}

.spinner {
  width: 30px;
  height: 30px;
  margin: 0 auto 12px;
  border: 2px solid rgba(77, 52, 31, 0.12);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: admin-spin 0.7s linear infinite;
}

.spinner.small {
  width: 24px;
  height: 24px;
}

@keyframes admin-spin {
  to { transform: rotate(360deg); }
}

.stack-list,
.account-list,
.room-list,
.message-list,
.member-list,
.log-list,
.log-preview-list {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stack-item,
.account-row,
.room-row {
  width: 100%;
  padding: 14px 16px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.stack-item:hover,
.account-row:hover,
.account-row.active,
.room-row:hover,
.room-row.active {
  border-color: var(--fc-selected-border);
  background: var(--fc-selected-bg);
  box-shadow: var(--fc-selected-shadow);
}

.avatar,
.member-avatar {
  width: 44px;
  height: 44px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}

.avatar-large {
  width: 68px;
  height: 68px;
  border-radius: 22px;
  font-size: 22px;
}

.member-avatar {
  border-radius: 14px;
}

.member-avatar-image {
  object-fit: cover;
  background: #f0e5d8;
}

.stack-copy,
.account-copy,
.room-copy,
.detail-copy,
.member-copy {
  min-width: 0;
}

.stack-copy strong,
.account-title strong,
.room-title strong,
.member-copy strong {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.stack-copy small,
.account-title span,
.account-meta span,
.room-title span,
.room-meta span,
.member-copy small,
.log-preview span,
.log-meta span,
.sub-head span {
  font-size: 12px;
  color: var(--fc-text-muted);
}

.account-title,
.account-meta,
.room-title,
.room-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.account-meta,
.room-meta {
  margin-top: 6px;
}

.detail-hero {
  margin-top: 16px;
  align-items: center;
  gap: 16px;
}

.sub-card {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 22px;
  background: rgba(255, 253, 249, 0.9);
}

.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.textarea,
.field input,
.field select {
  width: 100%;
  border: 1px solid rgba(77, 52, 31, 0.1);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--fc-text);
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.textarea {
  margin-top: 12px;
  min-height: 104px;
  padding: 14px 16px;
  line-height: 1.7;
  resize: vertical;
}

.query-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.query-grid.logs {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.query-grid.compact {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field span {
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text-sec);
}

.field input,
.field select {
  min-height: 46px;
  padding: 0 14px;
}

.field-span-2 {
  grid-column: span 2;
}

.query-actions {
  align-items: flex-end;
  flex-wrap: wrap;
}

.action-row {
  flex-wrap: wrap;
  gap: 10px;
}

.action-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.message-card,
.member-card,
.log-card,
.log-preview {
  padding: 14px 16px;
}

.message-card strong,
.log-card strong,
.log-preview strong {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--fc-text);
}

.message-card span {
  display: block;
  margin-top: 10px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

.member-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.member-main,
.member-side {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.log-preview {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 20px;
  background: rgba(255, 253, 249, 0.84);
}

.log-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.log-meta {
  margin-top: 10px;
  flex-wrap: wrap;
  gap: 8px;
}

.log-detail {
  margin: 12px 0 0;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(33, 26, 20, 0.04);
  font-family: var(--fc-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--fc-text-sec);
  white-space: pre-wrap;
  word-break: break-word;
}

.pager {
  margin-top: 18px;
}

.admin-back:disabled,
.primary-btn:disabled,
.secondary-btn:disabled,
.danger-btn:disabled,
.ghost-btn:disabled {
  opacity: 0.58;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.admin-back:focus-visible,
.primary-btn:focus-visible,
.secondary-btn:focus-visible,
.danger-btn:focus-visible,
.ghost-btn:focus-visible,
.inline-btn:focus-visible,
.tab-chip:focus-visible,
.stack-item:focus-visible,
.account-row:focus-visible,
.textarea:focus-visible,
.field input:focus-visible,
.field select:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (prefers-reduced-motion: reduce) {
  .spinner,
  .admin-notice-fade-enter-active,
  .admin-notice-fade-leave-active,
  .admin-back,
  .primary-btn,
  .secondary-btn,
  .danger-btn,
  .ghost-btn,
  .tab-chip,
  .stack-item,
  .account-row {
    transition: none;
    animation: none !important;
  }
}

@media (max-width: 1080px) {
  .admin-hero-grid,
  .overview-grid,
  .two-column {
    grid-template-columns: 1fr;
  }

  .room-grid {
    grid-template-columns: 1fr;
  }

  .room-grid > :first-child {
    grid-row: auto;
  }

  .query-grid.logs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .admin-page {
    padding: 14px 12px 22px;
  }

  .admin-shell {
    padding: 16px;
    border-radius: 22px;
  }

  .admin-toolbar,
  .panel-head,
  .log-top,
  .pager,
  .sub-head,
  .member-main,
  .member-side {
    flex-direction: column;
    align-items: stretch;
  }

  .tab-grid,
  .overview-row,
  .tile-grid,
  .query-grid,
  .query-grid.logs,
  .query-grid.compact,
  .action-grid {
    grid-template-columns: 1fr;
  }

  .field-span-2 {
    grid-column: auto;
  }

  .stack-item,
  .account-row,
  .room-row {
    grid-template-columns: auto 1fr;
  }

  .chip-row {
    grid-column: 1 / -1;
  }
}
</style>
