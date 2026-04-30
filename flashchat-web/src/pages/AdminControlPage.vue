<template>
  <div class="admin-page fc-workspace">
    <PageNoticeToast :notice="notice" />

    <div class="admin-shell fc-shell fc-shell-lg">
      <header class="admin-hero">
        <div class="admin-toolbar">
          <div class="admin-toolbar-row">
            <button class="admin-back" type="button" @click="goRoomList">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M15 6l-6 6 6 6" />
              </svg>
              <span>房间列表</span>
            </button>
            <button class="secondary-btn" type="button" @click="goFeedbackDesk">反馈处理台</button>
          </div>
          <span class="admin-scope" title="仅管理员可访问">
            <i></i>
            <span>管理员控制台</span>
          </span>
        </div>

        <div class="admin-hero-grid">
          <section class="hero-card">
            <div class="hero-spine"></div>
            <div class="fc-kicker">Admin · Ops Console</div>
            <h1>系统运营面板</h1>
            <p>
              面向账号治理、房间处置与审计追踪的一体化工作台。账号、房间和日志均支持分页与精确筛选，所有治理动作都有迹可循。
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
              <small>全量账号数</small>
            </article>
            <article class="stat-card">
              <span>当前页管理员</span>
              <strong>{{ adminVisibleCount }}</strong>
              <small>本页可见的系统管理员</small>
            </article>
            <article class="stat-card">
              <span>当前页封禁</span>
              <strong>{{ bannedVisibleCount }}</strong>
              <small>本页被封禁的账号</small>
            </article>
            <article class="stat-card">
              <span>日志总量</span>
              <strong>{{ logPage.total }}</strong>
              <small>审计日志累计</small>
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
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageNoticeToast from '@/components/PageNoticeToast.vue'
import { useAuth } from '@/composables/useAuth'
import { usePageNotice } from '@/composables/usePageNotice'
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
const { notice, showNotice } = usePageNotice()

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
/* Page & shell — shared primitives provide canvas/grain/shadow */
.admin-page { padding-bottom: 36px; }
.admin-shell { padding: 26px 28px 30px; }

/* Toolbar */
.admin-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--fc-rule-soft);
}

.admin-toolbar-row {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
}

.admin-scope {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--fc-rule);
  background: rgba(255, 248, 237, 0.7);
  color: var(--fc-accent-strong);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.admin-scope i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--fc-accent);
  box-shadow: 0 0 0 4px rgba(182, 118, 57, 0.14);
  animation: admin-pulse 1.8s var(--fc-ease-in-out) infinite;
}

@keyframes admin-pulse {
  0%, 100% { box-shadow: 0 0 0 4px rgba(182, 118, 57, 0.14); }
  50%      { box-shadow: 0 0 0 7px rgba(182, 118, 57, 0.06); }
}

/* Buttons */
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
  padding: 9px 18px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.01em;
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

.admin-back {
  padding-left: 14px;
}

.admin-back svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

.admin-back:hover svg { transform: translateX(-2px); }

.admin-back:hover,
.secondary-btn:hover,
.ghost-btn:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 4px 12px rgba(33, 26, 20, 0.05);
}

.primary-btn {
  background: linear-gradient(180deg, #c07d3e 0%, var(--fc-accent) 100%);
  border-color: transparent;
  color: #fffaf3;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.3) inset,
    0 10px 22px rgba(151, 90, 38, 0.22);
}

.primary-btn:hover:not(:disabled) {
  background: linear-gradient(180deg, var(--fc-accent) 0%, var(--fc-accent-strong) 100%);
  transform: translateY(-1px);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.35) inset,
    0 14px 28px rgba(151, 90, 38, 0.26);
}

.danger-btn {
  background: linear-gradient(180deg, #c56a54 0%, var(--fc-danger) 100%);
  border-color: transparent;
  color: #fff8f5;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.25) inset,
    0 10px 22px rgba(184, 96, 75, 0.22);
}

.danger-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.3) inset,
    0 14px 28px rgba(184, 96, 75, 0.26);
}

.ghost-btn {
  background: transparent;
  border-color: transparent;
  color: var(--fc-accent-strong);
  min-height: 34px;
  padding: 7px 14px;
}

.ghost-btn:hover:not(:disabled) {
  background: var(--fc-accent-veil);
  border-color: transparent;
  box-shadow: none;
}

.inline-btn {
  min-height: 30px;
  padding: 4px 2px;
  border: none;
  background: none;
  color: var(--fc-accent-strong);
  text-decoration: underline;
  text-decoration-color: transparent;
  text-underline-offset: 4px;
  transition: text-decoration-color var(--fc-duration-normal) var(--fc-ease-in-out);
}

.inline-btn:hover { text-decoration-color: var(--fc-accent-strong); }

.admin-back:disabled,
.primary-btn:disabled,
.secondary-btn:disabled,
.danger-btn:disabled,
.ghost-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

/* Hero */
.admin-hero-grid {
  margin-top: 22px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 22px;
  animation: admin-fadeUp 480ms var(--fc-ease-out) both;
}

.hero-card {
  position: relative;
  padding: 28px 28px 26px 38px;
  border: 1px solid var(--fc-border);
  border-radius: 24px;
  background:
    radial-gradient(ellipse 100% 80% at 100% 0%, rgba(182, 118, 57, 0.07), transparent 60%),
    linear-gradient(180deg, #fffdf7 0%, rgba(255, 253, 249, 0.94) 100%);
  box-shadow: 0 12px 32px rgba(33, 26, 20, 0.05);
  overflow: hidden;
}

.hero-spine {
  position: absolute;
  left: 18px;
  top: 28px;
  bottom: 28px;
  width: 2px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--fc-accent) 0%, var(--fc-accent-soft) 60%, transparent 100%);
}

.hero-card .fc-kicker {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  letter-spacing: 0.22em;
}

.hero-card .fc-kicker::after {
  content: '';
  width: 36px;
  height: 1px;
  background: var(--fc-rule);
}

.hero-card h1 {
  margin: 16px 0 10px;
  font-family: var(--fc-font-display);
  font-size: clamp(32px, 4vw, 44px);
  line-height: 1.02;
  letter-spacing: -0.015em;
  font-weight: 600;
  color: var(--fc-text);
}

.hero-card p {
  margin: 0;
  max-width: 720px;
  font-size: 15px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

/* Identity chips */
.identity-row {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.identity-row span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  font-weight: 500;
  color: var(--fc-text-sec);
  backdrop-filter: blur(4px);
}

.identity-row span::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--fc-accent);
  opacity: 0.75;
}

/* Tab switcher — magazine-nav style */
.tab-grid {
  margin-top: 22px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 6px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: rgba(255, 253, 249, 0.7);
  backdrop-filter: blur(6px);
}

.tab-chip {
  position: relative;
  padding: 12px 14px 14px;
  border: none;
  border-radius: 13px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--fc-duration-normal) var(--fc-ease-in-out);
}

.tab-chip strong {
  display: block;
  font-family: var(--fc-font-display);
  font-size: 16px;
  line-height: 1.1;
  font-weight: 600;
  color: var(--fc-text);
}

.tab-chip small {
  display: block;
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--fc-text-muted);
}

.tab-chip:hover { background: rgba(255, 255, 255, 0.7); }

.tab-chip.active {
  background: #fff;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 6px 18px rgba(33, 26, 20, 0.06),
    0 0 0 1px var(--fc-selected-border);
}

.tab-chip.active::after {
  content: '';
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 7px;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--fc-accent), var(--fc-accent-soft));
  animation: admin-slideIn 280ms var(--fc-ease-out);
}

/* Stats — asymmetric metric cards in the right column */
.stats-grid {
  display: grid;
  gap: 10px;
  align-content: start;
}

.stat-card {
  position: relative;
  padding: 16px 18px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 253, 249, 0.78));
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: transform var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.stat-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(33, 26, 20, 0.05);
}

.stat-card span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.stat-card span::before {
  content: '';
  width: 4px;
  height: 10px;
  border-radius: 2px;
  background: var(--fc-accent);
  opacity: 0.6;
}

.stat-card strong {
  font-family: var(--fc-font-display);
  font-size: 30px;
  line-height: 1;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--fc-text);
  letter-spacing: -0.01em;
}

.stat-card small {
  font-size: 12px;
  line-height: 1.4;
  color: var(--fc-text-muted);
}

/* Panel grids */
.panel-grid {
  margin-top: 20px;
  display: grid;
  gap: 16px;
}

.overview-grid { grid-template-columns: 1.2fr 0.9fr; }
.overview-grid > :first-child { grid-column: 1 / -1; }

.two-column { grid-template-columns: minmax(340px, 0.95fr) minmax(420px, 1.05fr); }

.room-grid { grid-template-columns: 360px minmax(0, 1fr); }
.room-grid > :first-child { grid-row: 1 / span 2; }

.single-column { grid-template-columns: 1fr; }

/* Panel cards */
.panel-card {
  position: relative;
  padding: 22px 24px 22px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 10px 24px rgba(33, 26, 20, 0.04);
  animation: admin-fadeUp 520ms var(--fc-ease-out) both;
}

.panel-highlight {
  background:
    radial-gradient(ellipse 80% 70% at 100% 0%, rgba(182, 118, 57, 0.07), transparent 60%),
    linear-gradient(180deg, #fffdf7 0%, rgba(255, 253, 249, 0.94) 100%);
}

.state-card {
  margin-top: 20px;
  padding: 38px 28px;
  border: 1px solid var(--fc-border);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.94);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.state-card h2 {
  margin: 0;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1.15;
  font-weight: 600;
  color: var(--fc-text);
}

.state-card p {
  margin: 0;
  max-width: 480px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

/* Panel head */
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--fc-rule-soft);
  margin-bottom: 18px;
}

.panel-head > :first-child {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.panel-head h2 {
  margin: 4px 0 0;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1.15;
  font-weight: 600;
  letter-spacing: -0.005em;
  color: var(--fc-text);
}

/* Overview tiles */
.overview-row,
.tile-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.tile-grid { margin-top: 16px; }

.overview-tile,
.info-tile {
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: rgba(255, 253, 249, 0.88);
}

.overview-tile span,
.info-tile span {
  display: block;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.overview-tile strong,
.info-tile strong {
  display: block;
  margin-top: 8px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1.2;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--fc-text);
  word-break: break-all;
}

.overview-tile small {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--fc-text-muted);
}

/* Empty / loading states inside panels */
.list-state {
  padding: 30px 18px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.list-state.compact { padding: 16px; }

.list-state p {
  margin: 0;
  font-size: 13.5px;
  color: var(--fc-text-sec);
}

/* Spinner */
.spinner {
  width: 30px;
  height: 30px;
  margin: 0 auto;
  border: 2px solid rgba(77, 52, 31, 0.14);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: admin-spin 0.7s linear infinite;
}

.spinner.small { width: 22px; height: 22px; }

@keyframes admin-spin { to { transform: rotate(360deg); } }
@keyframes admin-fadeUp {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes admin-slideIn {
  from { transform: scaleX(0.4); opacity: 0; }
  to   { transform: scaleX(1); opacity: 1; }
}

/* List rows — with accent left-strip when active */
.stack-list,
.account-list,
.room-list,
.message-list,
.member-list,
.log-list,
.log-preview-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stack-item,
.account-row,
.room-row {
  position: relative;
  width: 100%;
  padding: 14px 16px 14px 20px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: rgba(255, 253, 249, 0.8);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out),
    transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.stack-item::before,
.account-row::before,
.room-row::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--fc-accent);
  transform: scaleY(0);
  transform-origin: center;
  transition: transform var(--fc-duration-normal) var(--fc-ease-out);
}

.stack-item:hover,
.account-row:hover,
.room-row:hover {
  border-color: var(--fc-border-strong);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 6px 16px rgba(33, 26, 20, 0.05);
}

.account-row.active,
.room-row.active {
  border-color: var(--fc-selected-border);
  background: var(--fc-selected-bg);
  box-shadow: var(--fc-selected-shadow);
}

.account-row.active::before,
.room-row.active::before {
  transform: scaleY(1);
}

/* Avatar tiles */
.avatar,
.member-avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fffaf2;
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.35) inset,
    0 4px 10px rgba(33, 26, 20, 0.08);
}

.avatar-large {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  font-size: 22px;
}

.member-avatar-image {
  object-fit: cover;
  background: #f0e5d8;
}

/* Row typography */
.stack-copy,
.account-copy,
.room-copy,
.detail-copy,
.member-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stack-copy strong,
.account-title strong,
.room-title strong,
.member-copy strong {
  display: block;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--fc-text);
  letter-spacing: -0.002em;
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
  align-items: baseline;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

/* Semantic chips */
.chip-success,
.chip-danger,
.chip-admin,
.chip-neutral {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--fc-border);
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.35;
  color: var(--fc-text-sec);
  white-space: nowrap;
}

.chip-success::before,
.chip-danger::before,
.chip-admin::before,
.chip-neutral::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--fc-text-muted);
  opacity: 0.7;
}

.chip-success {
  background: rgba(84, 120, 76, 0.1);
  border-color: rgba(84, 120, 76, 0.22);
  color: #3e5b37;
}
.chip-success::before { background: var(--fc-success); opacity: 1; }

.chip-danger {
  background: rgba(184, 96, 75, 0.1);
  border-color: rgba(184, 96, 75, 0.22);
  color: #8e4a39;
}
.chip-danger::before { background: var(--fc-danger); opacity: 1; }

.chip-admin {
  background: var(--fc-accent-veil);
  border-color: rgba(182, 118, 57, 0.3);
  color: var(--fc-accent-strong);
}
.chip-admin::before { background: var(--fc-accent); opacity: 1; }

/* Detail hero — account/room header */
.detail-hero {
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: rgba(255, 253, 249, 0.82);
  display: flex;
  gap: 16px;
  align-items: center;
}

.detail-copy h3 {
  margin: 0;
  font-family: var(--fc-font-display);
  font-size: 20px;
  line-height: 1.2;
  font-weight: 600;
  color: var(--fc-text);
}

.detail-copy p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--fc-text-muted);
}

.detail-copy .chip-row {
  margin-top: 8px;
  justify-content: flex-start;
}

/* Sub-card — grouping within a panel */
.sub-card {
  margin-top: 20px;
  padding: 18px;
  border: 1px solid var(--fc-border);
  border-radius: 18px;
  background: rgba(255, 253, 249, 0.86);
}

.sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

/* Form fields */
.textarea,
.field input,
.field select {
  width: 100%;
  min-height: 44px;
  padding: 10px 14px;
  border: 1px solid rgba(77, 52, 31, 0.12);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--fc-text);
  font-size: 14px;
  line-height: 1.5;
  transition:
    border-color var(--fc-duration-normal) var(--fc-ease-in-out),
    background var(--fc-duration-normal) var(--fc-ease-in-out),
    box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.textarea {
  margin-top: 12px;
  min-height: 104px;
  padding: 12px 14px;
  line-height: 1.7;
  resize: vertical;
}

.textarea:hover,
.field input:hover,
.field select:hover {
  border-color: rgba(77, 52, 31, 0.18);
  background: #fff;
}

.textarea:focus,
.field input:focus,
.field select:focus {
  outline: none;
  border-color: var(--fc-accent-soft);
  background: #fff;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

.query-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.query-grid.logs { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.query-grid.compact { grid-template-columns: repeat(2, minmax(0, 1fr)); }

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.field span {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.field-span-2 { grid-column: span 2; }

.query-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 4px;
}

.query-grid .query-actions {
  grid-column: 1 / -1;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.action-row.center { justify-content: center; }

.action-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

/* Messages, members, logs */
.message-card,
.member-card,
.log-card,
.log-preview {
  position: relative;
  padding: 14px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 16px;
  background: rgba(255, 253, 249, 0.82);
}

.message-card strong,
.log-card strong,
.log-preview strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text);
}

.message-card p,
.log-card p,
.log-reason,
.log-preview p {
  margin: 6px 0 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.message-card span {
  display: block;
  margin-top: 8px;
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.log-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.log-top > :first-child {
  min-width: 0;
  flex: 1 1 auto;
}

.log-top > :first-child strong {
  display: block;
  font-family: var(--fc-font);
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text);
}

.log-top > span {
  font-size: 12px;
  color: var(--fc-text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.log-meta {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.log-detail {
  margin: 12px 0 0;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(33, 26, 20, 0.04);
  font-family: var(--fc-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--fc-text-sec);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}

.log-preview-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

/* Pager */
.pager {
  margin-top: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 16px;
  border-top: 1px solid var(--fc-rule-soft);
}

.pager span {
  font-size: 12.5px;
  letter-spacing: 0.04em;
  color: var(--fc-text-muted);
  font-variant-numeric: tabular-nums;
}

/* Focus */
.admin-back:focus-visible,
.primary-btn:focus-visible,
.secondary-btn:focus-visible,
.danger-btn:focus-visible,
.ghost-btn:focus-visible,
.inline-btn:focus-visible,
.tab-chip:focus-visible,
.stack-item:focus-visible,
.account-row:focus-visible,
.room-row:focus-visible,
.textarea:focus-visible,
.field input:focus-visible,
.field select:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (prefers-reduced-motion: reduce) {
  .spinner,
  .admin-back,
  .primary-btn,
  .secondary-btn,
  .danger-btn,
  .ghost-btn,
  .tab-chip,
  .stack-item,
  .account-row,
  .room-row,
  .stat-card,
  .panel-card,
  .admin-hero-grid {
    transition: none;
    animation: none !important;
  }
}

/* Responsive */
@media (max-width: 1120px) {
  .admin-hero-grid { grid-template-columns: 1fr; }
  .stats-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .overview-grid,
  .two-column,
  .room-grid { grid-template-columns: 1fr; }
  .room-grid > :first-child { grid-row: auto; }
  .query-grid.logs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 820px) {
  .admin-toolbar,
  .panel-head,
  .log-top,
  .pager,
  .sub-head,
  .member-main,
  .member-side,
  .detail-hero {
    flex-direction: column;
    align-items: stretch;
  }

  .chip-row {
    justify-content: flex-start;
  }

  .tab-grid,
  .overview-row,
  .tile-grid,
  .query-grid,
  .query-grid.logs,
  .query-grid.compact,
  .action-grid,
  .stats-grid {
    grid-template-columns: 1fr;
  }

  .field-span-2 { grid-column: auto; }

  .stack-item,
  .account-row,
  .room-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .stack-item .chip-row,
  .account-row .chip-row,
  .room-row .chip-row {
    grid-column: 1 / -1;
  }
}
</style>
